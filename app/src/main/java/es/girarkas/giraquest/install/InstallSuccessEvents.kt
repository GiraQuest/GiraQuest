package es.girarkas.giraquest.install

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Evento global de instalación completada con éxito para mostrar la
 * alerta animada cartoon + sonido en MainActivity.
 */
object InstallSuccessEvents {

    data class Event(
        val gameName: String,
        val detail: String,
        val packageName: String = "",
    )

    private val _flow = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val flow = _flow.asSharedFlow()

    @Volatile
    private var lastKey: String = ""
    @Volatile
    private var lastAtMs: Long = 0L

    fun emit(gameName: String, detail: String, packageName: String = "") {
        val cleanName = gameName.trim().ifBlank { "Juego instalado" }
        val key = packageName.ifBlank { cleanName }
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAtMs < 3_000L) return
        lastKey = key
        lastAtMs = now
        _flow.tryEmit(Event(cleanName, detail.trim(), packageName))
    }
}
