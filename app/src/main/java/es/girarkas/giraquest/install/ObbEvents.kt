package es.girarkas.giraquest.install

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Eventos de OBB (v0.1.27): el temporizador de MainActivity, el
 * reintento manual y la copia tras el fallback completan OBBs FUERA del
 * flujo de instalación que los empezó. Antes la única señal era un
 * Toast que se perdía (así Edu creyó que los 3 .obb de BulletStorm no
 * se habían copiado, cuando sí se copiaron a las 15:32:32). Ahora el
 * evento llega a la UI (lista, ficha, Instalados) para actualizar el
 * estado persistente EN SU SITIO, y el notificador avisa con una
 * notificación de sistema.
 */
object ObbEvents {

    sealed class Event {
        /** Todos los .obb de un juego ya están en /Android/obb/<paquete>/. */
        data class Completed(
            val packageName: String,
            val gameName: String,
            val files: Int,
            val bytes: Long,
        ) : Event()

        /** La copia del OBB falló con un motivo concreto. */
        data class Failed(
            val packageName: String,
            val gameName: String,
            val reason: String,
        ) : Event()

        /** La APK está instalada pero el OBB sigue pendiente. */
        data class Pending(
            val packageName: String,
            val gameName: String,
            val reason: String,
        ) : Event()
    }

    private val _flow = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val flow: SharedFlow<Event> = _flow

    fun emit(e: Event) {
        _flow.tryEmit(e)
    }
}
