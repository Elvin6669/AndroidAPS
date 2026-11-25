package app.aaps.implementation.elvin

import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.elvin.*
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.notifications.*
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.elvin.*
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/** Thin adapter over AAPS storage/crypto/sync. No network polling, new crypto, or pump commands. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class ElvinMaintenanceImpl(
    private val preferences: Preferences,
    private val config: Config,
    private val date: DateUtil,
    private val persistence: PersistenceLayer,
    private val exports: () -> ImportExportPrefs,
    private val passwordStore: () -> ExportPasswordDataStore,
    private val automation: () -> Automation,
    private val nightscout: () -> NSClientRepository,
    private val notifications: NotificationManager,
    private val platform: ElvinPlatform,
    private val scope: CoroutineScope
) : ElvinMaintenance {
    private val output = MutableStateFlow("Backup/Nightscout adapter has not sampled yet")
    override val status: StateFlow<String> = output
    private val lock = Mutex()
    private var work: Job? = null
    private var backupStatus = "No Elvin backup completed in this process"
    private var nsStatus = "Nightscout custom monitoring disabled"
    private fun enabled() = preferences.get(ElvinBooleanKey.Enabled) && config.APS && config.platform == AppPlatform.Android
    private fun number(key: ElvinIntKey) = preferences.get(key).coerceIn(key.min, key.max)
    private fun put(key: ElvinMaintenanceLongKey, value: Long) {
        if (enabled() && preferences.get(key) != value) preferences.put(key, value)
    }
    private fun render() {
        if (!enabled()) { output.value = "Elvin maintenance disabled; existing AAPS sync and Automation remain unchanged"; return }
        fun time(value: Long) = if (value > 0) kotlin.time.Instant.fromEpochMilliseconds(value).toString() else "not observed"
        output.value = "$nsStatus\n$backupStatus\nLast completed encrypted backup: ${time(preferences.get(ElvinMaintenanceLongKey.LastBackup))}"
    }
    override fun onSample(periodic: Boolean) = launch(periodic, false)
    override fun backupNow() = launch(false, true)
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launch(periodic: Boolean, manualBackup: Boolean) {
        if (!enabled()) {
            work?.cancel()
            notifications.dismiss(NotificationId.ELVIN_NIGHTSCOUT_STALLED)
            notifications.dismiss(NotificationId.ELVIN_BACKUP_STATUS)
            platform.notice(ElvinPlatform.Notice.NIGHTSCOUT, null)
            platform.notice(ElvinPlatform.Notice.BACKUP, null)
            output.value = "Elvin maintenance disabled. Existing AAPS automation/backups/sync remain unchanged."
            return
        }
        if (!lock.tryLock()) return
        work = scope.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
            try {
                currentCoroutineContext().ensureActive()
                checkUploads()
                if (manualBackup || (periodic && preferences.get(ElvinBooleanKey.AutoBackup))) backup(manualBackup)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { backupStatus = "Custom maintenance unavailable; credentials and exception contents withheld" }
            finally {
                try { render() } catch (_: Exception) { output.value = "Elvin maintenance status unavailable" }
                lock.unlock()
            }
        }
    }
    private fun checkUploads() {
        val on = preferences.get(ElvinBooleanKey.NightscoutMonitoring)
        if (!on) {
            nsStatus = "Nightscout custom monitoring disabled"
            put(ElvinMaintenanceLongKey.NsPendingSince, 0)
            notifications.dismiss(NotificationId.ELVIN_NIGHTSCOUT_STALLED)
            platform.notice(ElvinPlatform.Notice.NIGHTSCOUT, null)
            return
        }
        val repo = nightscout()
        val observed = ElvinTelemetry.nightscout.value
        if (observed.lastAttempt > 0) {
            put(ElvinMaintenanceLongKey.LastNsSuccess, observed.lastSuccess)
            put(ElvinMaintenanceLongKey.LastNsAttempt, observed.lastAttempt)
            put(ElvinMaintenanceLongKey.NsFailures, observed.consecutiveFailures.toLong())
        }
        val now = date.now()
        val queue = repo.queueSize.value.coerceAtLeast(0)
        val success = preferences.get(ElvinMaintenanceLongKey.LastNsSuccess)
        val failure = preferences.get(ElvinMaintenanceLongKey.NsFailures)
        val decision = ElvinMaintenancePolicy.upload(now, true, repo.urlUpdate.value.isNotBlank(),
            preferences.get(BooleanKey.NsClientUploadData), queue,
            preferences.get(ElvinMaintenanceLongKey.LastNsAttempt), success, failure,
            preferences.get(ElvinMaintenanceLongKey.NsPendingSince), preferences.get(ElvinMaintenanceLongKey.LastNsWarning),
            number(ElvinIntKey.NightscoutTimeout), number(ElvinIntKey.NightscoutRepeat), repo.uploadsPaused())
        put(ElvinMaintenanceLongKey.NsPendingSince, decision.pendingSince)
        val last = if (success > 0) kotlin.time.Instant.fromEpochMilliseconds(success).toString() else "not observed"
        nsStatus = "${decision.reason}; queue=$queue; consecutive failed responses=$failure; last confirmed upload=$last"
        if (!enabled()) return
        if (decision.warn) {
            put(ElvinMaintenanceLongKey.LastNsWarning, now)
            notifications.post(NotificationId.ELVIN_NIGHTSCOUT_STALLED, TextRef.Literal(decision.reason))
            platform.notice(ElvinPlatform.Notice.NIGHTSCOUT, decision.reason)
        } else if (decision.pendingSince == 0L) {
            put(ElvinMaintenanceLongKey.LastNsWarning, 0)
            notifications.dismiss(NotificationId.ELVIN_NIGHTSCOUT_STALLED)
            platform.notice(ElvinPlatform.Notice.NIGHTSCOUT, null)
        }
    }
    private suspend fun backup(manual: Boolean) {
        val now = date.now()
        if (!manual && !ElvinMaintenancePolicy.backupDue(now, preferences.get(ElvinMaintenanceLongKey.LastBackup),
                preferences.get(ElvinMaintenanceLongKey.LastBackupAttempt), number(ElvinIntKey.BackupHours))) return
        if (!manual && automation().events.value.any { it.isEnabled && !it.userAction && it.containsSettingsExport }) {
            backupStatus = "Existing enabled Automation settings-export event detected: Elvin schedule deferred to prevent duplicate backups"
            return
        }
        put(ElvinMaintenanceLongKey.LastBackupAttempt, now)
        val exporter = exports()
        val store = passwordStore()
        if (!exporter.isMasterPasswordSet() || !store.exportPasswordStoreEnabled()) {
            failedBackup("Configure AAPS master password and enable unattended encrypted export in Maintenance first")
            return
        }
        val (password, expired, nearExpiry) = store.getPasswordFromDataStore()
        if (password.isEmpty() || expired) {
            failedBackup("Cached export password missing/expired: perform a standard encrypted export to refresh it")
            return
        }
        val cfg = exporter.getExportConfig()
        if (!cfg.settingsLocal && !cfg.settingsCloud) {
            failedBackup("No encrypted backup destination enabled in Maintenance")
            return
        }
        currentCoroutineContext().ensureActive()
        if (!enabled()) return
        val result = try { withTimeout(120_000) { exporter.executeAutomaticExport(password) } }
            catch (_: TimeoutCancellationException) { failedBackup("Encrypted backup timed out; review storage/cloud connectivity"); return }
        currentCoroutineContext().ensureActive()
        if (!enabled()) return
        val anySuccess = result.localSuccess == true || result.cloudSuccess == true
        val complete = anySuccess && result.localSuccess != false && result.cloudSuccess != false
        if (anySuccess) put(ElvinMaintenanceLongKey.LastBackup, date.now())
        backupStatus = "Encrypted backup: local=${result.localSuccess ?: "not requested"}; cloud=${result.cloudSuccess ?: "not requested"}" +
            if (nearExpiry) "; cached password nearing expiry" else ""
        if (!complete) failedBackup("$backupStatus; review destination settings/permissions")
        else {
            notifications.dismiss(NotificationId.ELVIN_BACKUP_STATUS)
            platform.notice(ElvinPlatform.Notice.BACKUP, null)
        }
    }
    private fun failedBackup(message: String) {
        if (!enabled()) return
        backupStatus = message
        notifications.post(NotificationId.ELVIN_BACKUP_STATUS, TextRef.Literal(message))
        platform.notice(ElvinPlatform.Notice.BACKUP, message)
    }
    override suspend fun profiles(): String = withContext(Dispatchers.Default) {
        val days = number(ElvinIntKey.ProfileHistoryDays)
        val start = date.now() - days * 86_400_000L
        val requested = persistence.getProfileSwitchesIncludingInvalidFromTime(start, false).take(100).map {
            it.timestamp to "${kotlin.time.Instant.fromEpochMilliseconds(it.timestamp)} REQUESTED ${it.profileName}; ${it.percentage}%; duration=${it.duration / 60_000} min; valid=${it.isValid}"
        }
        val effective = persistence.getEffectiveProfileSwitchesIncludingInvalidFromTime(start, false).take(100).map {
            it.timestamp to "${kotlin.time.Instant.fromEpochMilliseconds(it.timestamp)} EFFECTIVE ${it.originalProfileName}; ${it.originalPercentage}%; valid=${it.isValid}"
        }
        "Existing AAPS profile history — latest 100 requested/effective records over $days days. Read-only; no profile or dosing edits.\n" +
            (requested + effective).sortedByDescending { it.first }.take(100).joinToString("\n") { it.second }.ifEmpty { "No records in this window" }
    }
    override fun reset() {
        work?.cancel()
        ElvinMaintenanceLongKey.entries.forEach(preferences::remove)
        notifications.dismiss(NotificationId.ELVIN_NIGHTSCOUT_STALLED)
        notifications.dismiss(NotificationId.ELVIN_BACKUP_STATUS)
        platform.notice(ElvinPlatform.Notice.NIGHTSCOUT, null)
        platform.notice(ElvinPlatform.Notice.BACKUP, null)
        backupStatus = "Elvin schedule reset; existing backup files, passwords and Automation events preserved"
        nsStatus = "Nightscout custom monitoring disabled"
        render()
    }
    internal suspend fun awaitIdle() { work?.join() }
}
