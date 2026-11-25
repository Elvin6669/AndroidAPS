package app.aaps.implementation.elvin

import android.Manifest
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.Settings
import app.aaps.core.interfaces.elvin.ElvinChannel
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationManager as AapsNotifications
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.ui.IconsProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.aaps.core.keys.elvin.*
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.rawRes
import app.aaps.implementation.androidNotification.AlarmNotificationManager
import dev.zacsweers.metro.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AndroidElvinPlatform(
    private val context: Context,
    private val preferences: Preferences,
    private val notifications: AapsNotifications,
    private val appScope: CoroutineScope,
    private val icons: IconsProvider,
    private val holder: () -> NotificationHolder,
    private val officialAlarms: () -> AlarmNotificationManager
) : ElvinPlatform {
    override val supported = true
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val players = mutableMapOf<ElvinChannel, MediaPlayer>()
    private val ramps = mutableMapOf<ElvinChannel, Runnable>()
    private fun audio(channel: ElvinChannel) = ElvinAudioSettings.read(preferences, channel == ElvinChannel.PUMP)
    private val routes = mutableMapOf<ElvinChannel, CommunicationAlarmPolicy.AudioRoute>()
    private val sounds = mutableMapOf<ElvinChannel, AlarmSound>()
    private val previews = mutableSetOf<ElvinChannel>()
    private val audioStatus = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val vibrator get() = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var observing = false
    private var receiverRegistered = false
    private var snoozeAction: ((ElvinChannel) -> Unit)? = null
    private val notificationBase = 940_000
    /** Custom Android callbacks must not crash the process hosting the official safety alarms. */
    private fun safely(action: () -> Unit) {
        try { action() }
        catch (_: Exception) {
            audioStatus["runtime"] = "custom Android action failed; official audio untouched"
            players.keys.toList().forEach { channel -> runCatching { stopNow(channel) } }
        }
    }
    private fun onMain(action: () -> Unit) { handler.post { safely(action) } }
    private val snoozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            safely { ElvinChannel.entries.firstOrNull { it.name == intent?.getStringExtra("channel") }?.let { snoozeAction?.invoke(it) } }
        }
    }

    private fun ensureObservers() {
        if (observing) return
        observing = true
        // Never compete with an official audible safety alarm.
        appScope.launch {
            notifications.notifications.collect { list ->
                if (list.any { it.sound != null }) ElvinChannel.entries.forEach(::stop)
                else onMain {
                    val latest = notifications.notifications.value
                    ElvinChannel.entries.forEach { channel ->
                        val id = if (channel == ElvinChannel.PUMP) app.aaps.core.interfaces.notifications.NotificationId.ELVIN_PUMP_LOSS
                            else app.aaps.core.interfaces.notifications.NotificationId.ELVIN_CGM_LOSS
                        if (channel !in previews && latest.none { it.id == id }) {
                            stopNow(channel); nm.cancel(notificationBase + channel.ordinal)
                        }
                    }
                }
            }
        }
        appScope.launch {
            app.aaps.core.interfaces.elvin.ElvinTelemetry.muteGeneration.drop(1).collect {
                ElvinChannel.entries.forEach(::stop)
            }
        }
        appScope.launch {
            preferences.observe(ElvinBooleanKey.Enabled).collect { enabled ->
                if (!enabled) ElvinChannel.entries.forEach(::stop)
            }
        }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun officialAudioActive() = notifications.notifications.value.any { it.sound != null } || officialAlarms().unansweredAlarmIntent() != null
    private fun customNotificationExists(channel: ElvinChannel): Boolean {
        val id = if (channel == ElvinChannel.PUMP) app.aaps.core.interfaces.notifications.NotificationId.ELVIN_PUMP_LOSS
            else app.aaps.core.interfaces.notifications.NotificationId.ELVIN_CGM_LOSS
        return notifications.notifications.value.any { it.id == id }
    }
    override fun status(): Map<String, String> {
        val btPermission = granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN)
        val bluetooth = if (!btPermission) "unknown (Nearby devices permission missing)" else runCatching {
            context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled?.toString() ?: "adapter absent"
        }.getOrDefault("unavailable")
        return mapOf(
            "Bluetooth enabled" to bluetooth,
            "Bluetooth permissions" to btPermission.toString(),
            "Notifications enabled" to nm.areNotificationsEnabled().toString(),
            "Notification runtime permission" to (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)).toString(),
            "DND policy access" to nm.isNotificationPolicyAccessGranted.toString(),
            "DND filter" to nm.currentInterruptionFilter.toString(),
            "Android alarm stream volume" to context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM).toString(),
            "Android notification stream volume" to context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_NOTIFICATION).toString(),
            "Elvin notification channel" to (nm.getNotificationChannel("elvin-communication-v1")?.importance?.toString() ?: "not created yet"),
            "Battery optimisation exemption" to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName).toString(),
            "Exact alarm access (not used to promise custom timing)" to context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms().toString(),
            "Reboot recovery" to "Existing AAPS WorkManager scheduling; force-stop requires reopening AAPS",
            "Custom audio" to (audioStatus.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "not tested" })
        ) + ElvinChannel.entries.associate { channel ->
            val p = audio(channel)
            "$channel effective audio options" to "silent override=${p.silentOverride}; DND override=${p.dndOverride}; vibration=${p.vibration}; gain=${p.start}..${p.maximum}%; escalation=${p.escalate}/${p.minutes}min"
        }
    }

    override fun play(channel: ElvinChannel, sound: AlarmSound, test: Boolean) {
        onMain {
            ensureObservers()
            if (!preferences.get(ElvinBooleanKey.Enabled)) { stopNow(channel); return@onMain }
            // Global mute/dismissal may have raced the worker's queued playback request.
            if (!test && !customNotificationExists(channel)) { stopNow(channel); return@onMain }
            if (officialAudioActive()) {
                stopNow(channel)
                audioStatus[channel.name] = "deferred to official audible safety alarm"
                return@onMain
            }
            val dnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            val profile = audio(channel)
            val route = CommunicationAlarmPolicy.audioRoute(dnd, profile.silentOverride, profile.dndOverride, nm.isNotificationPolicyAccessGranted)
            if (route == CommunicationAlarmPolicy.AudioRoute.QUIET) {
                stopNow(channel)
                audioStatus[channel.name] = "DND sound suppressed; check custom override and policy access"
                vibrate(channel)
                return@onMain
            }
            // Reposting a repeat must not reset a ten-minute escalation every five minutes.
            if (!test && channel in players && channel !in previews && routes[channel] == route && sounds[channel] == sound) { vibrate(channel); return@onMain }
            stopNow(channel)
            val alarmStream = route == CommunicationAlarmPolicy.AudioRoute.ALARM
            try {
                val player = MediaPlayer()
                // Own it before any fallible setup so the failure path always releases it.
                players[channel] = player
                routes[channel] = route
                sounds[channel] = sound
                player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (alarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                context.resources.openRawResourceFd(sound.rawRes).use { afd ->
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                player.isLooping = true
                val started = SystemClock.elapsedRealtime()
                val start = profile.start
                val maximum = profile.maximum
                val duration = profile.minutes
                val escalate = profile.escalate
                val gain = CommunicationAlarmPolicy.volume(start, maximum, 0, duration, escalate)
                player.setVolume(gain, gain)
                if (test) previews.add(channel)
                val ramp = object : Runnable {
                    override fun run() {
                        safely {
                            if (players[channel] !== player) return@safely
                            if (!test && !customNotificationExists(channel)) { stopNow(channel); return@safely }
                            if (officialAudioActive()) {
                                stopNow(channel); audioStatus[channel.name] = "deferred to official audible safety alarm"; return@safely
                            }
                            // Permission/DND changes take effect during an already-playing alarm.
                            val dndNow = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                            val current = audio(channel)
                            if (!preferences.get(ElvinBooleanKey.Enabled) || (dndNow &&
                                    (!current.dndOverride || !nm.isNotificationPolicyAccessGranted))) {
                                stopNow(channel); return@safely
                            }
                            val currentRoute = CommunicationAlarmPolicy.audioRoute(dndNow, current.silentOverride, current.dndOverride, nm.isNotificationPolicyAccessGranted)
                            if (currentRoute != routes[channel]) { stopNow(channel); play(channel, sound, test); return@safely }
                            val elapsed = SystemClock.elapsedRealtime() - started
                            if (test && elapsed >= 30_000L) { stopNow(channel); return@safely }
                            val volume = CommunicationAlarmPolicy.volume(
                                current.start, current.maximum, elapsed, current.minutes, current.escalate)
                            runCatching { player.setVolume(volume, volume) }
                            handler.postDelayed(this, 1_000)
                        }
                    }
                }
                ramps[channel] = ramp
                player.setOnPreparedListener { prepared ->
                    safely {
                        if (players[channel] === prepared) {
                            if (!test && !customNotificationExists(channel)) { stopNow(channel); return@safely }
                            if (officialAudioActive()) {
                                stopNow(channel); return@safely
                            }
                            val current = audio(channel)
                            if (CommunicationAlarmPolicy.audioRoute(nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL,
                                    current.silentOverride, current.dndOverride, nm.isNotificationPolicyAccessGranted) == CommunicationAlarmPolicy.AudioRoute.QUIET) {
                                stopNow(channel); return@safely
                            }
                            prepared.start(); handler.post(ramp); vibrate(channel)
                            audioStatus[channel.name] = "playing; physical audibility unverified"
                        }
                    }
                }
                player.setOnErrorListener { _, _, _ ->
                    safely { audioStatus[channel.name] = "playback failed"; stopNow(channel) }; true
                }
                player.prepareAsync()
            } catch (_: Exception) {
                audioStatus[channel.name] = "playback unavailable"; stopNow(channel)
            }
        }
    }

    private fun vibrate(channel: ElvinChannel) {
        if (audio(channel).vibration) runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1))
        }
    }
    override fun stop(channel: ElvinChannel) { onMain { stopNow(channel) } }
    override fun dismiss(channel: ElvinChannel) { onMain { stopNow(channel); nm.cancel(notificationBase + channel.ordinal) } }
    private fun stopNow(channel: ElvinChannel) {
        if (audioStatus[channel.name]?.startsWith("playing") == true) audioStatus[channel.name] = "stopped"
        routes.remove(channel)
        sounds.remove(channel)
        previews.remove(channel)
        ramps.remove(channel)?.let(handler::removeCallbacks)
        players.remove(channel)?.let { player -> runCatching { player.stop() }; player.release() }
        // The short custom waveform ends by itself. Do not call the UID-wide vibrator.cancel():
        // another AAPS safety alarm can own vibration at the same time.
    }
    override fun onSnooze(action: (ElvinChannel) -> Unit) { snoozeAction = action }
    override fun notice(kind: ElvinPlatform.Notice, text: String?) = onMain {
        val id = notificationBase + 100 + kind.ordinal
        if (text == null || !preferences.get(ElvinBooleanKey.Enabled)) { nm.cancel(id); return@onMain }
        val channel = "elvin-maintenance-v1"
        nm.createNotificationChannel(NotificationChannel(channel, "Elvin backup and upload status", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.notify(id, NotificationCompat.Builder(context, channel).setSmallIcon(icons.getNotificationIcon())
            .setContentTitle(if (kind == ElvinPlatform.Notice.NIGHTSCOUT) "Elvin Nightscout monitoring" else "Elvin encrypted backup")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(holder().openAppIntent()).setAutoCancel(true).build())
    }
    override fun present(channel: ElvinChannel, text: String) {
        onMain {
            if (!preferences.get(ElvinBooleanKey.Enabled)) return@onMain
            ensureObservers()
            try {
                if (!receiverRegistered) {
                    ContextCompat.registerReceiver(context, snoozeReceiver, IntentFilter(context.packageName + ".ELVIN_SNOOZE"), ContextCompat.RECEIVER_NOT_EXPORTED)
                    receiverRegistered = true
                }
                // No channel sound/vibration: custom preferences own both. Android still controls
                // visibility and may retain user edits to this dedicated channel.
                val channelId = "elvin-communication-v1"
                nm.createNotificationChannel(NotificationChannel(channelId, "Elvin communication monitoring", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null); enableVibration(false)
                })
                val snoozeIntent = Intent(context.packageName + ".ELVIN_SNOOZE").setPackage(context.packageName).putExtra("channel", channel.name)
                val pending = PendingIntent.getBroadcast(context, channel.ordinal, snoozeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(notificationBase + channel.ordinal, NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(icons.getNotificationIcon()).setContentTitle("Elvin $channel communication")
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(holder().openAppIntent()).setOngoing(true)
                    .addAction(0, "Snooze $channel", pending).build())
            } catch (_: Exception) { audioStatus["notification"] = "unavailable; check notification permission/channel" }
        }
    }
    override fun share(text: String) = onMain {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Elvin AAPS diagnostics")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Export allowlisted diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun openSettings(kind: String) {
        val intent = when (kind) {
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "audio" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
