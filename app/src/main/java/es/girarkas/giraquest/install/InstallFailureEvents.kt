package es.girarkas.giraquest.install

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Evento global de instalación fallida para mostrar la alerta animada
 * cartoon + sonido + motivo del fallo en MainActivity.
 */
object InstallFailureEvents {

    data class Event(
        val gameName: String,
        val reason: String,
        val packageName: String = "",
    )

    private val _flow = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val flow = _flow.asSharedFlow()

    @Volatile
    private var lastKey: String = ""
    @Volatile
    private var lastAtMs: Long = 0L

    fun emit(gameName: String, reason: String, packageName: String = "") {
        val cleanName = gameName.trim().ifBlank { "Instalación" }
        val cleanReason = reason.trim().ifBlank { "Error desconocido durante la instalación." }
        val key = "${packageName.ifBlank { cleanName }}|${cleanReason.take(80)}"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAtMs < 3_000L) return
        lastKey = key
        lastAtMs = now
        _flow.tryEmit(Event(cleanName, cleanReason, packageName))
    }
}
