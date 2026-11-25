package app.aaps.implementation.elvin

import app.aaps.core.interfaces.elvin.ElvinChannel
import app.aaps.core.interfaces.notifications.AlarmSound

/** Android audio is deliberately independent of, and lower priority than, official safety audio. */
interface ElvinPlatform {
    val supported: Boolean
    fun status(): Map<String, String>
    fun play(channel: ElvinChannel, sound: AlarmSound, test: Boolean = false)
    fun stop(channel: ElvinChannel)
    fun share(text: String)
    fun openSettings(kind: String)
}
