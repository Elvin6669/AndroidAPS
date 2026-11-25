package app.aaps.implementation.elvin

import app.aaps.core.interfaces.elvin.ElvinChannel
import app.aaps.core.interfaces.notifications.AlarmSound
import dev.zacsweers.metro.*

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class UnsupportedElvinPlatform : ElvinPlatform {
    override val supported = false
    override fun status() = mapOf("Custom communication monitor" to "Android phone only in EI.1")
    override fun play(channel: ElvinChannel, sound: AlarmSound, test: Boolean) = Unit
    override fun stop(channel: ElvinChannel) = Unit
    override fun share(text: String) = Unit
    override fun openSettings(kind: String) = Unit
}
