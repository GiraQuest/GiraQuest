package es.girarkas.giraquest.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ADB embebido (Vía B, estilo LADB) — el corazón del gestor.
 *
 * La app lleva dentro su propio cliente ADB (libadb-android, Java puro,
 * pairing SPAKE2/TLS en Android 11+) y habla con el adbd del propio
 * dispositivo por depuración inalámbrica. NO depende de Shizuku ni de
 * ninguna segunda app (Shizuku queda solo como fallback documentado en el
 * README; no se instalan las dos vías a la vez, son incompatibles).
 *
 * API según el README de libadb-android 3.1.1:
 *  - subclase de AbsAdbConnectionManager con getPrivateKey()/getCertificate()
 *  - pair(host, port, pairingCode) para el emparejamiento único
 *  - connect(host, port) / connectTls(context, timeout) para reconectar
 *  - openStream("shell:") -> AdbStream para ejecutar comandos
 *
 * Con el shell (UID 2000) la app puede: `pm install`, `pm uninstall`,
 * `mkdir`/`cp`/`rm` en /sdcard/Android/obb y /sdcard/Android/data, y
 * concederse WRITE_SECURE_SETTINGS para re-activar la depuración
 * inalámbrica tras reinicios.
 */
class GiraAdbManager private constructor(
    context: Context,
) : AbsAdbConnectionManager() {

    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)

    // ------------------------------------------------------------------
    // Claves RSA perezosas (v0.1.1).
    // La v0.1 hacía AdbKeyStore.loadOrCreate() AQUÍ, en el constructor, en
    // el hilo principal: generar RSA 2048 + certificado BouncyCastle en el
    // arranque. Si eso lanzaba (proveedor "BC" distinto en cada Android,
    // SHA512withRSA no soportado, etc.), la app crasheaba al abrir sin
    // llegar a mostrar nada. Ahora las claves se crean/cargan solo cuando
    // hacen falta y fuera del hilo principal (ensureKeys), y un fallo se
    // guarda en initError/AdbHealth en vez de propagarse.
    // ------------------------------------------------------------------
    @Volatile
    private var keysValue: AdbKeyStore.Keys? = null

    @Volatile
    var initError: Throwable? = null
        private set

    @Volatile
    var keysInitialized = false
        private set

    /**
     * Huella SHA-256 de la clave pública (formato adb, v0.1.22): se
     * registra al cargar las claves y en cada intento de conexión. Si la
     * huella es la misma en cada arranque, la clave NO cambia y el
     * diálogo de autorización no puede ser por clave nueva.
     */
    @Volatile
    var keyFingerprint: String? = null
        private set

    /**
     * El móvil rechazó la clave (v0.1.22): el emparejamiento ya no vale
     * (p. ej. se desactivó y reactivó la depuración inalámbrica y el
     * adbd borró sus claves autorizadas). Mientras esté activo, NO se
     * reintenta ninguna conexión: cada intento nuevo volvería a disparar
     * el diálogo del sistema. Solo se limpia al emparejar de nuevo o al
     * conectar con éxito.
     */
    @Volatile
    var authRejected: Boolean = false
        private set

    @Volatile
    var authRejectedReason: String? = null
        private set

    /** ¿Están las claves listas y sin error? No dispara la creación. */
    val isAvailable: Boolean get() = keysInitialized && initError == null

    /**
     * Crea/carga las claves RSA. Lento (RSA 2048): llamar FUERA del hilo
     * principal (p. ej. desde Dispatchers.IO). Idempotente.
     */
    fun ensureKeys() {
        if (keysInitialized) return
        synchronized(this) {
            if (keysInitialized) return
            try {
                val store = AdbKeyStore(appContext)
                val existed = store.keysExist()
                keysValue = store.loadOrCreate()
                keyFingerprint = keysValue?.certificate?.let { store.fingerprint(it) }
                // v0.1.22: la huella queda en el log en cada arranque.
                // "Cargadas de disco" = la clave es la misma de siempre
                // (el móvil NO debería pedir autorización de nuevo);
                // "CREADAS NUEVAS" = la clave cambió y el emparejamiento
                // anterior ya no vale (hay que re-emparejar).
                StartupLog.mark(
                    appContext,
                    "adb keys: ${if (existed) "cargadas de disco" else "CREADAS NUEVAS (no existían en disco)"}, " +
                        "huella ${keyFingerprint ?: "?"}"
                )
            } catch (e: Throwable) {
                initError = e
                AdbHealth.keyStoreError = e
                // v0.1.5: cadena de causas COMPLETA en el StartupLog (la
                // v0.1.4 solo dejaba "NoClassDefFoundError: OperatorHelper"
                // sin el motivo raíz debajo).
                StartupLog.mark(appContext, "adb keys FAILED: ${AdbHealth.fullChainReason(e)}")
            } finally {
                keysInitialized = true
            }
        }
    }

    /** Reintentar la creación de claves tras un fallo (borra el estado). */
    fun retryKeys() {
        synchronized(this) {
            keysInitialized = false
            initError = null
            AdbHealth.keyStoreError = null
            keysValue = null
        }
        ensureKeys()
    }

    @Volatile
    var connected: Boolean = false
        private set

    init {
        // setApi solo guarda el valor en libadb (getProtocolVersion: mismo
        // resultado para API >= 28), pero se limita a 32 por si algún
        // dispositivo con API mayor lo tratara distinto. Nunca debe lanzar.
        try {
            setApi(minOf(Build.VERSION.SDK_INT, 32))
        } catch (e: Throwable) {
            initError = e
        }
        // v0.1.22: que un rechazo de clave FALLE RÁPIDO en vez de esperar
        // al diálogo. Por defecto libadb (mThrowOnUnauthorised=false),
        // cuando el adbd rechaza la clave, envía la clave pública y SE
        // QUEDA ESPERANDO a que el usuario acepte el diálogo de
        // autorización; la app lo envuelve en withTimeout(20s), abandona,
        // y el siguiente intento abre OTRA conexión y vuelve a disparar
        // el diálogo — en bucle (el síntoma de Edu 05:15: la ventana del
        // depurador inalámbrico no para de salir). Con true, connect()
        // lanza AdbAuthenticationFailedException en cuanto el par rechaza
        // la clave, y la app lo registra, marca authRejected y deja de
        // machacar conexiones hasta re-emparejar. El emparejamiento en
        // sí (pair SPAKE2) no usa este flag.
        // v0.1.25, EXCEPCIÓN: el PRIMER connect TCP 5555 (Quest) usa
        // setThrowOnUnauthorised(false) temporalmente
        // (connectTcpFirstTime): con true, libadb falla rápido ANTES de
        // mandar su RSAPUBKEY y el visor nunca muestra el diálogo de
        // autorización. Tras ese primer connect se restaura true.
        try {
            setThrowOnUnauthorised(true)
        } catch (_: Throwable) {
        }
        // v0.1.23: tope REAL de libadb. Por defecto mTimeout =
        // Long.MAX_VALUE: AdbConnection.connect() ->
        // waitForConnection(Long.MAX_VALUE) espera PARA SIEMPRE a que el
        // adbd responda al CNXN. Y el withTimeout(20s) de Kotlin NO lo
        // interrumpe: connect() es una llamada bloqueante que nunca
        // suspende, así que la excepción de timeout solo se entregaría
        // cuando connect() volviera — que es nunca si el adbd no
        // responde (log de Edu 06:00:22: la última línea es "adb
        // connect: huella …" y nada después; la UI se quedó en
        // "Extrayendo…" y el install no avanzó ni cayó al fallback).
        // Con setTimeout, el propio waitForConnection devuelve false a
        // los 20 s. Verificado en el bytecode de libadb 3.1.1
        // (AdbConnection.waitForConnection: bucle con deadline real).
        try {
            setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        // v0.1.22: NADA de Conscrypt aquí, NI en ensureAdbReady().
        // Registrar el proveedor TLS de Conscrypt de forma global
        // (Security.insertProviderAt(..., 1)) lo ponía el primero de la
        // JVM y TODAS las TLS del proceso — incluida OkHttp — pasaban
        // por el Conscrypt empaquetado: al descargar un juego desde cero
        // (05:22) OkHttp fallaba con NoClassDefFoundError:
        // okhttp3.internal.platform.ConscryptPlatform, mientras que
        // fetchMeta a las 05:10 (antes de emparejar, sin Conscrypt
        // registrado) descargó 65 MB bien. libadb NO lo necesita: su
        // SslUtils.getSslContext() crea el SSLContext con el proveedor
        // Conscrypt EXPLÍCITO por reflexión (y si falla, usa el de la
        // plataforma en API 29+). La .so y el AAR siguen en el build
        // para ese uso interno; globalmente, la app usa el TLS de la
        // plataforma (AndroidOpenSSL) y OkHttp se fuerza a él en
        // HttpClients.
    }

    /**
     * Prepara el ADB bajo demanda (v0.1.2): crea o carga las claves
     * RSA. Llamar FUERA del hilo principal y solo cuando el usuario
     * lo pide (emparejar / reintentar). Nunca lanza: un fallo queda
     * en initError / AdbHealth.
     *
     * v0.1.22: YA NO registra Conscrypt globalmente (ver el comentario
     * del init): eso rompía las descargas por OkHttp. libadb usa su
     * propio Conscrypt explícito para el TLS del ADB.
     */
    fun ensureAdbReady() {
        AdbHealth.conscryptError = null
        ensureKeys()
    }

    override fun getPrivateKey(): PrivateKey {
        ensureKeys()
        return keysValue?.privateKey
            ?: throw IllegalStateException("Claves ADB no disponibles", initError)
    }

    override fun getCertificate(): Certificate {
        ensureKeys()
        return keysValue?.certificate
            ?: throw IllegalStateException("Claves ADB no disponibles", initError)
    }

    override fun getDeviceName(): String = "GiraQuest"

    companion object {
        /**
         * v0.1.18: timeout de conexión ADB. libadb trae por defecto
         * `mTimeout = Long.MAX_VALUE`: un `connect()` a un endpoint
         * muerto (p. ej. mDNS devolvió un puerto ya cerrado, o la IP no
         * responde) se queda colgado minutos sin registro ni salida — en
         * el log de Edu (02:27) "buscando por mDNS…" fue la última línea
         * y la instalación parecía en bucle. 20 s es de sobra en red local.
         * v0.1.23: este timeout se aplica AHORA con setTimeout() en el
         * init (antes solo lo envolvía un withTimeout de Kotlin, que NO
         * puede interrumpir una llamada bloqueante que nunca suspende:
         * el log 06:00:22 terminó en "adb connect: huella …" sin ninguna
         * línea después). El watchdog CONNECT_WATCHDOG_MS cubre además
         * un bloqueo en el `new Socket()` del constructor.
         */
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** Sondeo de copias desacopladas: cada cuánto se mira si terminó (v0.1.21). */
        private const val DETACHED_POLL_MS = 5_000L

        /** Tope de una copia desacoplada de OBB (~1 GB, v0.1.21). */
        private const val DETACHED_OBB_TIMEOUT_MS = 10 * 60_000L

        /** Tope del staging desacoplado de la APK (120 MB, v0.1.21). */
        private const val DETACHED_STAGE_TIMEOUT_MS = 3 * 60_000L

        /** Lanzamientos de una copia desacoplada antes de darla por imposible (v0.1.21). */
        private const val DETACHED_MAX_LAUNCHES = 5

        /**
         * Sondeos seguidos con el transporte muerto antes de abandonar
         * el intento (v0.1.22): con el transporte muerto, sondear cada
         * 2 s sin parar machaca el adbd con reconexiones y no aporta
         * nada (el trabajo, si llegó a lanzarse, sigue en curso en el
         * móvil y el temporizador lo comprobará por tamaños).
         */
        private const val DETACHED_MAX_DEAD_POLLS = 6

        /**
         * Tope del watchdog del connect (v0.1.23): el setTimeout de
         * libadb cubre la espera del CNXN, pero NO el `new Socket()` del
         * constructor de AdbConnection (sin timeout). El watchdog ejecuta
         * el connect() bloqueante en un hilo aparte y lo abandona si no
         * vuelve en este tiempo.
         */
        private const val CONNECT_WATCHDOG_MS = 25_000L

        /**
         * Tope del PRIMER connect TCP 5555 (v0.1.25, Quest): el visor
         * muestra el aviso de autorización y Edu tiene que aceptarlo a
         * mano. 60 s dan tiempo de sobra para leer el aviso y pulsar
         * "Permitir". El watchdog va 5 s por encima para no cortar el
         * connect antes de que libadb agote su propio timeout.
         */
        private const val TCP_FIRST_CONNECT_TIMEOUT_MS = 60_000L
        private const val TCP_FIRST_CONNECT_WATCHDOG_MS = 65_000L

        @Volatile
        private var INSTANCE: GiraAdbManager? = null

        fun getInstance(context: Context): GiraAdbManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: GiraAdbManager(context).also { INSTANCE = it }
            }

        /**
         * ¿Es este mensaje un error de TRANSPORTE ADB (el socket murió),
         * y no un error de `pm`? Un pm fallido deja "Failure
         * [INSTALL_FAILED_…]" en stdout (code 0, isError false); un
         * transporte muerto lanza IOException al abrir/leer el stream
         * ("Stream closed", "Socket closed", "Broken pipe"…). Solo los
         * de transporte se reintentan o caen al instalador del sistema.
         */
        fun isTransportErrorText(msg: String?): Boolean {
            if (msg.isNullOrBlank()) return false
            val m = msg.lowercase()
            return "stream closed" in m || "socket closed" in m ||
                "socket is closed" in m || "broken pipe" in m ||
                "connection reset" in m || "connection abort" in m ||
                "software caused connection abort" in m ||
                "econnreset" in m || "epipe" in m
        }
    }

    /** "host:puerto" guardado (o "TLS" si no hay puerto), para el log. */
    fun describeEndpoint(): String {
        if (settings.adbMode == "tcp") {
            return "TCP ${settings.adbTcpHost}:${settings.adbTcpPort}"
        }
        val port = settings.adbConnectPort
        return if (port > 0) "${settings.adbConnectHost}:$port" else "TLS (sin host guardado)"
    }

    /** ¿Está la app en modo TCP 5555 (Quest) en vez de TLS con código? (v0.1.25) */
    val isTcpMode: Boolean get() = settings.adbMode == "tcp"

    /**
     * Prueba barata de vida REAL (v0.1.16): `echo ok` con timeout corto.
     * El flag `connected` y el isConnected() de libadb solo miran el
     * estado LOCAL del socket (isClosed/isConnected de java.net.Socket):
     * un socket a medio morir (el peer cayó, cambió el WiFi, adbd se
     * reinició, HyperOS mató la conexión) sigue diciendo "conectado"
     * hasta que se intenta leer o escribir. Solo un comando real
     * distingue una conexión viva de una muerta.
     */
    suspend fun probe(): Boolean {
        val res = exec("echo ok", timeoutMs = 10_000)
        return !res.isError && res.output.trim() == "ok"
    }

    /**
     * ¿Está la depuración inalámbrica activa, leída DIRECTA del
     * sistema (v0.1.25)? Leer Settings.Global no necesita ningún
     * permiso; escribirlo sí (WRITE_SECURE_SETTINGS, ver
     * enableWirelessDebuggingDirect). Sirve para saber si el modo
     * TLS se apagó (sueño/reinicio) sin gastar un connect.
     */
    fun isWirelessDebuggingOnDirect(): Boolean {
        return try {
            Settings.Global.getInt(appContext.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Re-activa la depuración inalámbrica DIRECTA, en el propio
     * proceso (v0.1.25, patrón probado por Oculus Wireless ADB):
     * `Settings.Global.putInt(cr, "adb_wifi_enabled", 1)`.
     *
     * Requiere WRITE_SECURE_SETTINGS concedido (pm grant al
     * emparejar; el permiso se declara en el manifest desde la
     * v0.1.25, sin eso el grant fallaba en silencio). Si no está
     * concedido, lanza SecurityException y se devuelve false con
     * su línea en el log — sin crashear.
     *
     * Es la salida del círculo vicioso del `enableWirelessDebugging()`
     * por shell: ese necesita ADB vivo para escribir, justo cuando
     * el ADB está muerto porque la depuración se apagó (sueño o
     * reinicio del visor). Esta vía no necesita ADB.
     */
    fun enableWirelessDebuggingDirect(): Boolean {
        return try {
            Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 1)
            val on = isWirelessDebuggingOnDirect()
            StartupLog.mark(
                appContext,
                "adb wifi directo: Settings.Global adb_wifi_enabled=1 escrito, ahora=${if (on) "ACTIVA" else "sigue APAGADA"}"
            )
            on
        } catch (e: SecurityException) {
            StartupLog.mark(
                appContext,
                "adb wifi directo FALLÓ: sin permiso WRITE_SECURE_SETTINGS (${e.message?.take(150)}) — empareja por el Wizard para concederlo"
            )
            false
        } catch (e: Throwable) {
            StartupLog.mark(
                appContext,
                "adb wifi directo FALLÓ: ${e.javaClass.simpleName}: ${e.message?.take(150)}"
            )
            false
        }
    }

    /**
     * Verifica vida real antes de instalar (v0.1.16) y reconecta si hace
     * falta. Devuelve true si la conexión está viva al final.
     *  1) Si el flag dice conectado, probe real (`echo ok`).
     *  2) Si el probe falla, disconnect() (close() de libadb: limpia la
     *     AdbConnection muerta; la clave privada NO se destruye — el
     *     destroy() de BCRSAPrivateCrtKey lanza DestroyFailedException
     *     y libadb lo ignora, verificado en JVM con bcprov 1.78.1) y
     *     connectSaved() con el endpoint guardado.
     *  3) Si sigue sin haber conexión, false (el llamador prueba mDNS).
     */
    suspend fun ensureLive(): Boolean {
        // v0.1.22: si la clave fue rechazada, no tiene sentido probar
        // nada: cada intento nuevo dispararía el diálogo del sistema.
        if (authRejected) {
            StartupLog.mark(
                appContext,
                "adb: clave rechazada por el móvil (${authRejectedReason ?: "?"}); sin reintentos hasta re-emparejar"
            )
            return false
        }
        if (connected) {
            if (probe()) return true
            StartupLog.mark(appContext, "adb: probe falló, la conexión murió; reconectando…")
            disconnect()
        }
        if (connectSaved()) {
            if (probe()) {
                StartupLog.mark(appContext, "adb: reconectado (${describeEndpoint()})")
                return true
            }
            StartupLog.mark(
                appContext,
                "adb: reconexión OK pero el probe falla (${describeEndpoint()})"
            )
        } else {
            StartupLog.mark(appContext, "adb: connectSaved() falló (${describeEndpoint()})")
        }
        return false
    }

    // ------------------------------------------------------------------
    // Emparejamiento y conexión
    // ------------------------------------------------------------------

    /**
     * Empareja con el adbd local usando el código de 6 dígitos que muestra
     * Ajustes. El código NO se detecta solo: lo escribe el usuario una vez
     * (vía notificación con RemoteInput); el puerto sí se descubre solo por
     * mDNS (AdbPortDiscovery). Tras emparejar, las claves RSA quedan
     * guardadas y no hace falta el código nunca más.
     */
    suspend fun pairDevice(host: String, port: Int, code: String): Boolean =
        pairDeviceDetailed(host, port, code).ok

    /**
     * Resultado detallado de un intento de emparejar (v0.1.6). La v0.1.5
     * devolvía solo true/false y el diálogo mostraba "Código incorrecto
     * o caducado" para CUALQUIER fallo — pero libadb lanza IOException
     * tanto si el puerto está muerto (conexión rechazada) como si el
     * código no vale (el intercambio SPAKE2 falla): son indistinguibles
     * desde la excepción de pair(). Por eso se hace antes una
     * comprobación TCP: si no hay nada escuchando en ese puerto, el
     * fallo es de CONEXIÓN (puerto obsoleto / diálogo de Ajustes
     * cerrado), no del código.
     */
    enum class PairFailure { CONNECTION, AUTH }

    data class PairOutcome(
        val ok: Boolean,
        val failure: PairFailure? = null,
        val reason: String? = null,
    )

    suspend fun pairDeviceDetailed(host: String, port: Int, code: String): PairOutcome =
        withContext(Dispatchers.IO) {
            // v0.1.23: si un connect se colgó, el hilo huérfano retiene
            // el mLock de libadb y pair() se bloquearía igual: no intentar.
            if (connectHung) {
                return@withContext PairOutcome(
                    false,
                    PairFailure.CONNECTION,
                    "el connect ADB anterior se colgó (hilo huérfano); reinicia la app y vuelve a emparejar",
                )
            }
            // 1) ¿Hay algo escuchando en ese puerto? Si no, el puerto es
            // de un diálogo de Ajustes ya cerrado (cada vez que se abre
            // "Emparejar dispositivo con código", Android genera código
            // NUEVO y puerto NUEVO). No es un problema del código.
            try {
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 3_000) }
            } catch (e: Throwable) {
                return@withContext PairOutcome(
                    false,
                    PairFailure.CONNECTION,
                    AdbHealth.fullChainReason(e),
                )
            }
            // 2) El puerto responde: el intercambio SPAKE2/TLS con el
            // código. Si falla aquí, el código es incorrecto o caducó.
            try {
                // super.pair: el método de libadb (SPAKE2/TLS). El nombre es
                // pairDevice porque `pair` ya existe en la superclase
                // (no-suspend) y un `suspend fun pair` chocaría con él.
                super.pair(host, port, code)
                // v0.1.22: emparejar de nuevo limpia el rechazo de clave
                // (la misma clave queda autorizada otra vez).
                authRejected = false
                authRejectedReason = null
                settings.adbPaired = true
                settings.adbPairingHost = host
                settings.adbPairingPort = port
                PairOutcome(true)
            } catch (e: Throwable) {
                PairOutcome(false, PairFailure.AUTH, AdbHealth.fullChainReason(e))
            }
        }

    /**
     * Marca la clave como rechazada por el móvil (v0.1.22): el
     * emparejamiento ya no vale y hay que re-emparejar por el Wizard.
     * Se limpia `adbPaired` para que el asistente vuelva a mostrar el
     * paso de emparejar en vez de "Emparejado — pulsa Reconectar".
     */
    private fun markAuthRejected(reason: String) {
        authRejected = true
        authRejectedReason = reason
        settings.adbPaired = false
        connected = false
        StartupLog.mark(
            appContext,
            "adb connect: CLAVE RECHAZADA por el móvil (huella ${keyFingerprint ?: "?"}): $reason. " +
                "El emparejamiento ya no vale: re-empareja por el Wizard con un código nuevo. " +
                "Sin más intentos de conexión hasta entonces."
        )
    }

    /**
     * Resultado de un connect() bloqueante de libadb ejecutado con
     * watchdog (v0.1.23). `tookMs` distingue un timeout de libadb
     * (connect devolvió false tras ~20 s) de un fallo inmediato.
     */
    private sealed class ConnectAttempt {
        object Ok : ConnectAttempt()
        data class Failed(val error: Throwable?, val tookMs: Long) : ConnectAttempt()
        object Hung : ConnectAttempt()
    }

    /**
     * ¿Un connect anterior se colgó dentro de libadb? (v0.1.23). El hilo
     * huérfano retiene el mLock de libadb (connect() es synchronized),
     * así que getAdbConnection()/disconnect()/connect() se bloquearían
     * para siempre: hasta reiniciar la app el ADB queda muerto, pero el
     * flujo SIGUE (fallback) en vez de colgarse en silencio.
     */
    @Volatile
    private var connectHung = false

    /**
     * Ejecuta el connect() bloqueante de libadb en un hilo aparte con
     * tope real (v0.1.23). Kotlin withTimeout NO puede interrumpir una
     * llamada bloqueante que nunca suspende — el withTimeout(20s) de la
     * v0.1.18 era papel mojado mientras el hilo estaba bloqueado dentro
     * de libadb. El setTimeout() del init cubre la espera del CNXN; este
     * watchdog cubre además un posible bloqueo en el `new Socket()` del
     * constructor (sin timeout). Si el hilo sigue vivo tras el tope, se
     * abandona huérfano y se marca connectHung.
     */
    private fun connectWithWatchdog(
        host: String,
        port: Int,
        watchdogMs: Long = CONNECT_WATCHDOG_MS,
    ): ConnectAttempt {
        if (connectHung) return ConnectAttempt.Failed(null, 0)
        val started = System.currentTimeMillis()
        var result: Boolean? = null
        var error: Throwable? = null
        val t = Thread({
            try {
                result = connect(host, port)
            } catch (e: Throwable) {
                error = e
            }
        }, "adb-connect-watchdog")
        t.isDaemon = true
        t.start()
        t.join(watchdogMs)
        val tookMs = System.currentTimeMillis() - started
        if (t.isAlive) {
            connectHung = true
            StartupLog.mark(
                appContext,
                "adb connect: COLGADO >${watchdogMs / 1000}s dentro de libadb " +
                    "(hilo huérfano abandonado; el ADB queda bloqueado hasta reiniciar la app)"
            )
            return ConnectAttempt.Hung
        }
        return if (error != null) ConnectAttempt.Failed(error, tookMs)
        else if (result == true) ConnectAttempt.Ok
        else ConnectAttempt.Failed(null, tookMs)
    }

    /**
     * Registra un connect fallido (o colgado) y devuelve false
     * (v0.1.23). Un connect que devolvió false tras ~20 s es el TIMEOUT
     * de libadb (el adbd no respondió al CNXN), no un fallo inmediato:
     * queda así en el log en vez del genérico "FALLÓ".
     */
    private fun handleConnectFailure(where: String, error: Throwable?, tookMs: Long): Boolean {
        connected = false
        when (error) {
            is AdbAuthenticationFailedException -> {
                markAuthRejected("el adbd no reconoce la clave (AdbAuthenticationFailedException)")
            }
            is AdbPairingRequiredException -> {
                markAuthRejected("el adbd exige emparejamiento (AdbPairingRequiredException)")
            }
            null -> {
                if (tookMs >= CONNECT_TIMEOUT_MS - 2_000) {
                    StartupLog.mark(
                        appContext,
                        "adb $where TIMEOUT ${tookMs / 1000}s: el adbd no respondió al CNXN " +
                            "(sesión de depuración degradada o diálogo de autorización sin responder)"
                    )
                } else {
                    StartupLog.mark(
                        appContext,
                        "adb $where FALLÓ (${describeEndpoint()}): connect devolvió false sin excepción (${tookMs}ms)"
                    )
                }
            }
            else -> {
                StartupLog.mark(
                    appContext,
                    "adb $where FALLÓ (${describeEndpoint()}): ${error.javaClass.simpleName}: ${error.message?.take(200)}"
                )
            }
        }
        return false
    }

    /** Reconecta sin código (emparejamiento ya hecho). */
    suspend fun connectSaved(): Boolean = withContext(Dispatchers.IO) {
        // v0.1.22: clave rechazada = no conectar (cada intento dispararía
        // el diálogo del sistema otra vez).
        if (authRejected) return@withContext false
        // v0.1.23: connect colgado antes = no intentar (el hilo huérfano
        // retiene el mLock y el intento se bloquearía igual).
        if (connectHung) {
            StartupLog.mark(
                appContext,
                "adb connectSaved: sin intentar (el connect anterior se colgó; reinicia la app para reintentar ADB)"
            )
            return@withContext false
        }
        // v0.1.25: modo TCP 5555 (Quest) — el endpoint guardado es el
        // TCP, no el TLS. El primer connect TCP lo hace el Wizard con
        // su ventana de 60 s; aquí solo se reconecta si ya está
        // autorizado (connectTcp devuelve false si no lo está).
        if (isTcpMode) {
            return@withContext connectTcp(settings.adbTcpHost, settings.adbTcpPort)
        }
        // La huella queda en cada intento: así el log demuestra si la
        // clave es la misma que se emparejó o si cambió.
        ensureKeys()
        StartupLog.mark(
            appContext,
            "adb connect: huella ${keyFingerprint ?: "?"} → ${describeEndpoint()}"
        )
        val host = settings.adbConnectHost
        val port = settings.adbConnectPort
        if (port > 0) {
            when (val attempt = connectWithWatchdog(host, port)) {
                is ConnectAttempt.Ok -> {
                    connected = true
                    // Conectar con éxito prueba que la clave está autorizada.
                    authRejected = false
                    authRejectedReason = null
                    true
                }
                is ConnectAttempt.Hung -> {
                    connected = false
                    false
                }
                is ConnectAttempt.Failed -> handleConnectFailure(
                    "connectSaved (${describeEndpoint()})",
                    attempt.error,
                    attempt.tookMs,
                )
            }
        } else {
            try {
                val ok = connectTls(appContext, 10_000)
                connected = ok
                if (ok) {
                    authRejected = false
                    authRejectedReason = null
                }
                ok
            } catch (e: AdbAuthenticationFailedException) {
                markAuthRejected("el adbd no reconoce la clave (AdbAuthenticationFailedException)")
                false
            } catch (e: AdbPairingRequiredException) {
                markAuthRejected("el adbd exige emparejamiento (AdbPairingRequiredException)")
                false
            } catch (e: Exception) {
                StartupLog.mark(
                    appContext,
                    "adb connectSaved FALLÓ (${describeEndpoint()}): ${e.javaClass.simpleName}: ${e.message?.take(200)}"
                )
                connected = false
                false
            }
        }
    }

    /** Conecta a un endpoint concreto (descubierto por mDNS) y lo guarda. */
    suspend fun connectTo(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (authRejected) return@withContext false
        // v0.1.23: connect colgado antes = no intentar (el hilo huérfano
        // retiene el mLock y el intento se bloquearía igual).
        if (connectHung) {
            StartupLog.mark(
                appContext,
                "adb connectTo: sin intentar (el connect anterior se colgó; reinicia la app para reintentar ADB)"
            )
            return@withContext false
        }
        ensureKeys()
        StartupLog.mark(
            appContext,
            "adb connect: huella ${keyFingerprint ?: "?"} → $host:$port"
        )
        when (val attempt = connectWithWatchdog(host, port)) {
            is ConnectAttempt.Ok -> {
                connected = true
                authRejected = false
                authRejectedReason = null
                settings.adbConnectHost = host
                settings.adbConnectPort = port
                true
            }
            is ConnectAttempt.Hung -> {
                connected = false
                false
            }
            is ConnectAttempt.Failed -> handleConnectFailure(
                "connectTo ($host:$port)",
                attempt.error,
                attempt.tookMs,
            )
        }
    }

    // ------------------------------------------------------------------
    // Modo TCP 5555 "Wireless ADB" (v0.1.25, Quest)
    // ------------------------------------------------------------------

    /**
     * Resultado del PRIMER connect TCP (v0.1.25). Hay que distinguir
     * tres casos que desde fuera se parecen (connect no devuelve true):
     *  - WaitingTimeout: el visor no aceptó el aviso en 60 s (o no lo
     *    mostró porque "Wireless ADB" está apagado). NO es un rechazo
     *    de clave: reintentar vuelve a mostrar el aviso.
     *  - Denied: el visor cerró la conexión durante la autorización
     *    (el usuario pulsó Cancelar, o la clave no se aceptó). Tampoco
     *    es "clave rechazada" en el sentido TLS: no se marca
     *    authRejected ni se limpia el emparejamiento.
     *  - Failed: nada escucha en ese host:puerto, transporte muerto, etc.
     */
    sealed class TcpFirstConnectOutcome {
        object Ok : TcpFirstConnectOutcome()
        data class WaitingTimeout(val tookMs: Long) : TcpFirstConnectOutcome()
        data class Denied(val detail: String) : TcpFirstConnectOutcome()
        data class Failed(val detail: String) : TcpFirstConnectOutcome()
        object Hung : TcpFirstConnectOutcome()
    }

    /**
     * Conecta en modo TCP a un endpoint ya autorizado (v0.1.25): la
     * clave ya está en /data/misc/adb/adb_keys del visor, así que usa
     * el comportamiento normal (setThrowOnUnauthorised(true), 20 s).
     * Guarda el endpoint y marca el modo TCP como autorizado.
     * Si aún no está autorizado (primera vez), devuelve false sin
     * intentar: el primer connect lo hace el Wizard con su ventana
     * de 60 s (connectTcpFirstTime), no un flujo en segundo plano.
     */
    suspend fun connectTcp(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (connectHung) {
            StartupLog.mark(
                appContext,
                "adb connectTcp: sin intentar (el connect anterior se colgó; reinicia la app para reintentar ADB)"
            )
            return@withContext false
        }
        if (!settings.adbTcpAuthorized) {
            StartupLog.mark(
                appContext,
                "adb connectTcp: TCP sin autorizar aún ($host:$port) — usa el Wizard (Wireless ADB) para aceptar el aviso en el visor"
            )
            return@withContext false
        }
        ensureKeys()
        StartupLog.mark(
            appContext,
            "adb tcp connect: huella ${keyFingerprint ?: "?"} → $host:$port"
        )
        when (val attempt = connectWithWatchdog(host, port)) {
            is ConnectAttempt.Ok -> {
                connected = true
                settings.adbTcpHost = host
                settings.adbTcpPort = port
                settings.adbMode = "tcp"
                true
            }
            is ConnectAttempt.Hung -> {
                connected = false
                false
            }
            is ConnectAttempt.Failed -> {
                // v0.1.25: un rechazo en TCP NO marca authRejected (ese
                // flag es del emparejamiento TLS y bloquearía todos los
                // reintentos): aquí solo se registra el motivo.
                connected = false
                StartupLog.mark(
                    appContext,
                    "adb tcp connect FALLÓ ($host:$port): " +
                        (attempt.error?.let { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }
                            ?: "connect devolvió false sin excepción (${attempt.tookMs}ms)")
                )
                false
            }
        }
    }

    /**
     * PRIMER connect TCP (v0.1.25, Quest): el visor muestra el aviso
     * "¿Permitir la depuración USB?" y Edu tiene que aceptarlo a mano.
     *
     * Con setThrowOnUnauthorised(true) (el valor normal desde la
     * v0.1.22) libadb falla RÁPIDO en cuanto el visor pide autorización,
     * ANTES de mandar su RSAPUBKEY — el visor nunca llega a mostrar el
     * diálogo y el connect muere sin que Edu pueda aceptar nada
     * (verificado en el bytecode de libadb 3.1.1 por la investigación
     * de la v0.1.25). Por eso el primer connect usa
     * setThrowOnUnauthorised(false) + timeout de 60 s: libadb manda su
     * clave, el visor muestra el aviso y el connect se queda esperando
     * a que Edu pulse "Permitir". Después se restaura el comportamiento
     * normal (true + 20 s) para los connects siguientes.
     *
     * NUNCA marca authRejected: "esperando el aviso" y "clave
     * rechazada" son cosas distintas en el primer connect TCP, y el
     * flag bloquearía todos los reintentos hasta re-emparejar por TLS.
     */
    suspend fun connectTcpFirstTime(host: String, port: Int): TcpFirstConnectOutcome =
        withContext(Dispatchers.IO) {
            if (connectHung) {
                return@withContext TcpFirstConnectOutcome.Failed(
                    "el connect anterior se colgó; reinicia la app para reintentar ADB"
                )
            }
            ensureKeys()
            StartupLog.mark(
                appContext,
                "adb tcp primer connect: huella ${keyFingerprint ?: "?"} → $host:$port " +
                    "(60s para aceptar el aviso en el visor)"
            )
            try {
                setThrowOnUnauthorised(false)
            } catch (_: Throwable) {
            }
            try {
                setTimeout(TCP_FIRST_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
            val attempt = try {
                connectWithWatchdog(host, port, TCP_FIRST_CONNECT_WATCHDOG_MS)
            } finally {
                // Restaurar el comportamiento normal para los connects
                // siguientes, pase lo que pase en este primero.
                try {
                    setThrowOnUnauthorised(true)
                } catch (_: Throwable) {
                }
                try {
                    setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) {
                }
            }
            when (attempt) {
                is ConnectAttempt.Ok -> {
                    connected = true
                    settings.adbTcpHost = host
                    settings.adbTcpPort = port
                    settings.adbMode = "tcp"
                    settings.adbTcpAuthorized = true
                    settings.adbPaired = true
                    authRejected = false
                    authRejectedReason = null
                    StartupLog.mark(
                        appContext,
                        "adb tcp: visor AUTORIZÓ la clave ($host:$port) — modo TCP guardado"
                    )
                    TcpFirstConnectOutcome.Ok
                }
                is ConnectAttempt.Hung -> {
                    connected = false
                    TcpFirstConnectOutcome.Hung
                }
                is ConnectAttempt.Failed -> {
                    connected = false
                    val err = attempt.error
                    when {
                        err is AdbAuthenticationFailedException ->
                            TcpFirstConnectOutcome.Denied(
                                "el visor rechazó la clave (AdbAuthenticationFailedException)"
                            )
                        err is AdbPairingRequiredException ->
                            TcpFirstConnectOutcome.Denied(
                                "el visor exige emparejamiento (AdbPairingRequiredException)"
                            )
                        err == null && attempt.tookMs >= TCP_FIRST_CONNECT_TIMEOUT_MS - 2_000 ->
                            TcpFirstConnectOutcome.WaitingTimeout(attempt.tookMs)
                        err != null ->
                            TcpFirstConnectOutcome.Failed(
                                "${err.javaClass.simpleName}: ${err.message?.take(200)}"
                            )
                        else ->
                            TcpFirstConnectOutcome.Failed(
                                "connect devolvió false sin excepción (${attempt.tookMs}ms)"
                            )
                    }.also { outcome ->
                        StartupLog.mark(
                            appContext,
                            "adb tcp primer connect: $outcome"
                        )
                    }
                }
            }
        }

    /**
     * v0.1.20: el cierre YA NO bloquea al llamador. `AdbConnection.close()`
     * (fuente libadb 3.1.1, verificada) hace `mConnectionThread.join()`
     * SIN timeout: si el hilo lector está atascado (p. ej. en el handshake
     * TLS o en una lectura que no nota el cierre del socket), close()
     * cuelga PARA SIEMPRE — en el log de Edu (04:05:07) un echo fallido
     * dejó 3,5 min sin líneas hasta que reinició la app. El cierre corre
     * en un hilo daemon y se espera como mucho 3 s: el socket se cierra
     * lo primero dentro de close() (`AdbConnection.isConnected()` es
     * `!mSocket.isClosed() && mSocket.isConnected()`, así que pasa a
     * false al instante) y si el hilo lector no muere, queda huérfano
     * sin bloquear la instalación. Se cierra el objeto AdbConnection
     * capturado, NO el close() del manager: ese pone `mAdbConnection =
     * null` al terminar, y si llega tarde anularía la conexión NUEVA que
     * se cree después. (El close() del manager además destruye la clave
     * privada — que de todas formas sobrevive, checkpoint-016 — así que
     * no se pierde nada saltándoselo: las claves persisten para el
     * reconnect.)
     */
    override fun disconnect() {
        connected = false
        // v0.1.23: si un connect se colgó, el hilo huérfano retiene el
        // mLock de libadb y getAdbConnection() (synchronized) se
        // bloquearía PARA SIEMPRE. No tocar nada: el ADB queda muerto
        // hasta reiniciar la app, pero no se cuelga el flujo.
        if (connectHung) return
        val conn = try {
            getAdbConnection()
        } catch (_: Throwable) {
            null
        }
        if (conn == null) return
        val closer = Thread({
            try {
                conn.close()
            } catch (_: Throwable) {
            }
        }, "adb-close")
        closer.isDaemon = true
        closer.start()
        try {
            closer.join(3_000)
        } catch (_: InterruptedException) {
        }
        if (closer.isAlive) {
            StartupLog.mark(
                appContext,
                "adb disconnect: el hilo lector no murió en 3s, cierre en segundo plano " +
                    "(la instalación no se bloquea)"
            )
        }
    }

    // ------------------------------------------------------------------
    // Ejecución de comandos shell (una orden = un stream "shell:<cmd>")
    // ------------------------------------------------------------------

    /**
     * Ejecuta un comando shell y devuelve su salida (stdout+stderr) cuando
     * el comando termina (el stream se cierra). Equivale a `adb shell <cmd>`.
     *
     * v0.1.16: cada comando queda en el StartupLog (comando, duración,
     * código, salida/error truncados) y, si el transporte muere a mitad
     * ("Stream closed" y familia), se reconecta y se reintenta UNA vez.
     * Antes el fallo de pm install era invisible en el log y no se
     * reintentaba nada: el flag `connected` quedaba obsoleto y el
     * siguiente exec() moría igual.
     */
    suspend fun exec(cmd: String, timeoutMs: Long = 60_000): ShellResult =
        withContext(Dispatchers.IO) { execInternal(cmd, timeoutMs, allowRetry = true) }

    private suspend fun execInternal(
        cmd: String,
        timeoutMs: Long,
        allowRetry: Boolean,
    ): ShellResult {
        val started = System.currentTimeMillis()
        // v0.1.22: clave rechazada = no intentar nada (cada intento
        // dispararía el diálogo del sistema).
        if (authRejected) {
            return ShellResult("", "ADB rechazado: clave no autorizada, re-empareja por el Wizard", -1)
        }
        if (!connected) {
            // Limpiar la conexión muerta antes de reconectar: si el
            // socket está a medio morir, connect() se niega a crear uno
            // nuevo porque isConnected() aún dice true.
            disconnect()
            if (!connectSaved()) {
                StartupLog.mark(
                    appContext,
                    "adb exec: sin conexión, no se pudo reconectar: ${cmd.take(80)}"
                )
                return ShellResult("", "ADB no conectado", -1)
            }
            StartupLog.mark(
                appContext,
                "adb exec: reconectado antes de: ${cmd.take(80)}"
            )
        }
        try {
            val res = readShellStream(cmd, timeoutMs)
            val ms = System.currentTimeMillis() - started
            StartupLog.mark(
                appContext,
                "adb exec OK (${ms}ms): ${cmd.take(80)} -> ${res.output.take(200)}"
            )
            return res
        } catch (e: AdbAuthenticationFailedException) {
            // El stream se abrió sobre una conexión cuya clave el par
            // acabó rechazando: mismo tratamiento que en connect().
            markAuthRejected("el adbd rechazó la clave al abrir el stream (AdbAuthenticationFailedException)")
            return ShellResult("", "ADB rechazado: clave no autorizada, re-empareja por el Wizard", -1)
        } catch (e: AdbPairingRequiredException) {
            markAuthRejected("el adbd exige emparejamiento al abrir el stream (AdbPairingRequiredException)")
            return ShellResult("", "ADB rechazado: emparejamiento requerido, re-empareja por el Wizard", -1)
        } catch (e: Exception) {
            connected = false
            val ms = System.currentTimeMillis() - started
            val msg = e.message ?: "Error shell"
            StartupLog.mark(
                appContext,
                "adb exec FALLÓ (${ms}ms): ${cmd.take(80)} -> " +
                    "${e.javaClass.simpleName}: ${msg.take(200)}"
            )
            if (allowRetry && isTransportErrorText(msg)) {
                StartupLog.mark(
                    appContext,
                    "adb exec: error de transporte, reconectando y reintentando una vez: ${cmd.take(80)}"
                )
                disconnect()
                if (connectSaved()) {
                    return execInternal(cmd, timeoutMs, allowRetry = false)
                }
                StartupLog.mark(
                    appContext,
                    "adb exec: la reconexión falló, no se reintenta: ${cmd.take(80)}"
                )
            }
            return ShellResult("", msg, -1)
        }
    }

    /**
     * Abre el stream "shell:<cmd>" y lee hasta EOF (v0.1.22: extraído
     * de execInternal para compartirlo con execPoll). Lanza si el
     * transporte muere; el llamador decide si reconecta o no.
     *
     * v0.1.24: TOPE REAL. El `withTimeout(timeoutMs)` anterior era
     * papel mojado aquí: `input.read()` es una lectura BLOQUEANTE que
     * nunca suspende, así que la excepción de timeout solo se entregaría
     * cuando read() volviera — que es nunca si el socket queda a medio
     * abrir (el adbd acepta la conexión TLS pero no responde al shell ni
     * lo cierra). Eso es EXACTAMENTE el "bucle" de Edu al reinstalar
     * (log 10:23:40.606: última línea "adb connect: huella …" y 107 s
     * de silencio después — ni COLGADO del watchdog, ni fallo, ni
     * fallback; el hilo estaba dentro de read()). El mismo defecto
     * estructural que el connect de libadb (checkpoint-025) y que la
     * extracción (v0.1.23): withTimeout no interrumpe llamadas
     * bloqueantes. Ahora la lectura corre en un hilo daemon con
     * join(timeoutMs): si no vuelve, se abandona huérfano, se marca la
     * conexión como muerta y se lanza IOException (error de transporte:
     * el llamador reintenta o cae al fallback). El hilo huérfano muere
     * solo cuando el socket se cierre (disconnect()) o el proceso.
     */
    private suspend fun readShellStream(cmd: String, timeoutMs: Long): ShellResult {
        var result: ShellResult? = null
        var error: Throwable? = null
        val t = Thread({
            try {
                val stream: AdbStream = openStream("shell:$cmd")
                val out = ByteArrayOutputStream()
                stream.openInputStream().use { input ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                }
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                result = ShellResult(String(out.toByteArray(), Charsets.UTF_8).trim(), "", 0)
            } catch (e: Throwable) {
                error = e
            }
        }, "adb-read-shell")
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        if (t.isAlive) {
            // La lectura no volvió en timeoutMs: el socket quedó a medio
            // abrir. No se puede interrumpir el read(): se abandona el
            // hilo y se devuelve error de transporte.
            connected = false
            StartupLog.mark(
                appContext,
                "adb exec: lectura colgada >${timeoutMs / 1000}s en: ${cmd.take(80)} " +
                    "(socket a medio abrir, hilo huérfano abandonado; el ADB se da por muerto)"
            )
            throw java.io.IOException("Stream colgado: sin respuesta en ${timeoutMs}ms")
        }
        error?.let { throw it }
        return result ?: ShellResult("", "sin salida", -1)
    }

    /**
     * Sondeo corto SIN reconectar y SIN log (v0.1.22). Devuelve la salida
     * o null si no hay conexión o el transporte murió al sondear.
     *
     * La v0.1.21 sondeaba con execQuick: cada sondeo muerto marcaba
     * connected=false y el siguiente sondeo RECONECTABA (disconnect +
     * connectSaved + reintento) — decenas de conexiones nuevas por
     * minuto, cada una pudiendo despertar el diálogo del sistema de
     * depuración inalámbrica. Aquí el sondeo usa SOLO la conexión viva:
     * si está muerta, se devuelve null y el llamador hace backoff; la
     * reconexión la decide el llamador, no cada sondeo.
     */
    private suspend fun execPoll(cmd: String, timeoutMs: Long = 30_000): String? {
        if (!connected || authRejected) return null
        return try {
            readShellStream(cmd, timeoutMs).output
        } catch (_: AdbAuthenticationFailedException) {
            markAuthRejected("el adbd rechazó la clave al sondear (AdbAuthenticationFailedException)")
            null
        } catch (_: AdbPairingRequiredException) {
            markAuthRejected("el adbd exige emparejamiento al sondear (AdbPairingRequiredException)")
            null
        } catch (_: Exception) {
            connected = false
            null
        }
    }

    /** Versión rápida para comandos que deben responder en segundos. */
    suspend fun execQuick(cmd: String): ShellResult = exec(cmd, timeoutMs = 30_000)

    /** Ejecuta un comando UNA vez, sin el reintento de transporte de exec(). */
    suspend fun execOnce(cmd: String, timeoutMs: Long = 60_000): ShellResult =
        withContext(Dispatchers.IO) { execInternal(cmd, timeoutMs, allowRetry = false) }

    // ------------------------------------------------------------------
    // Operaciones de alto nivel (espejo de ADB.cs / Sideloader.cs del PC)
    // ------------------------------------------------------------------

    /**
     * Copia la APK a /data/local/tmp antes de instalar (v0.1.20). Causa
     * probada en el log 04:08:43: `cmd package install` SÍ devolvió
     * salida — el system_server NO puede leer la APK desde Descargas
     * (contexto SELinux FUSE: "System server has no access to read file
     * context u:object_r:fuse:s0... Unable to open file"). /data/local/tmp
     * es donde `adb install` deja la APK (no es FUSE y es el sitio donde
     * el instalador espera encontrarla): ese error desaparece. La copia
     * la hace el propio shell con `cp` (operación local en el dispositivo,
     * no viaja por el canal ADB — enviar 120 MB por stdin con
     * `pm install -S` moriría igual que el resto por el transporte
     * inestable) y se verifica el tamaño antes de instalar. Si el staging
     * falla por lo que sea, se devuelve null y se instala desde la ruta
     * original (no se empeora nada).
     */
    suspend fun stageApkForInstall(apkDevicePath: String): String? {
        // Ya está en el sitio bueno: no hay nada que preparar.
        if (apkDevicePath.startsWith("/data/local/tmp/")) return apkDevicePath
        val pkg = File(apkDevicePath).parentFile?.name
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() } ?: "giraquest"
        val staged = "/data/local/tmp/giraquest-$pkg.apk"
        val expectedSize = try {
            File(apkDevicePath).length()
        } catch (_: Exception) {
            -1L
        }
        if (expectedSize <= 0) {
            StartupLog.mark(
                appContext,
                "adb stageApk: no se puede leer el tamaño de $apkDevicePath, sin staging"
            )
            return null
        }
        // v0.1.21: copia DESACOPLADA (lanzamiento instantáneo + sondeo).
        // El cp en primer plano moría con el transporte a los ~300 ms
        // (log 04:34:23 de Edu, dos intentos): el staging a /data/local/tmp
        // nunca llegó a probarse. Si la desacoplada falla, se instala
        // desde la ruta original como hasta ahora.
        if (!stageApkDetached(apkDevicePath, staged, expectedSize)) {
            StartupLog.mark(
                appContext,
                "adb stageApk: staging desacoplado FALLÓ — instalando desde la ruta original"
            )
            return null
        }
        StartupLog.mark(
            appContext,
            "adb stageApk: APK copiada a $staged ($expectedSize bytes, " +
                "system_server sí puede leerla)"
        )
        return staged
    }

    /** Borra la copia preparada en /data/local/tmp (mejor esfuerzo, sin reconectar: v0.1.22). */
    private suspend fun cleanupStaged(stagedPath: String, originalPath: String) {
        if (stagedPath == originalPath) return
        try {
            execPoll("rm -f \"$stagedPath\"")
        } catch (_: Exception) {
        }
    }

    /**
     * Instala un APK vía shell, probando variantes genuinamente distintas
     * (v0.1.18). Causa probada en el log 02:56 de Edu: `pm install -r -g`
     * muere SIEMPRE con "Stream closed" ~40 ms después de un probe `echo
     * ok` OK en la MISMA conexión, y el reintento en conexión nueva muere
     * igual (36 ms). La investigación (fuente de libadb 3.1.1 + mock-adbd
     * en JVM con un cliente de protocolo idéntico) demuestra que el
     * cliente es correcto: contra un adbd bien portado, echo→pm→echo en
     * la misma conexión funciona; el fallo es que el adbd del móvil
     * (Xiaomi/HyperOS, depuración inalámbrica) acepta el OPEN del stream
     * de pm install y lo cierra al instante SIN salida — antes de que pm
     * pueda imprimir nada. Un pm fallido de verdad dejaría
     * "Failure [INSTALL_FAILED_…]" en stdout; aquí no hay ni un byte.
     * Como el cliente no puede arreglar el adbd, se prueban otras vías
     * de shell que llegan al mismo instalador por otro camino:
     *  1) `pm install -r -g` (la de siempre);
     *  2) `cmd package install -r -g` (mismo PackageManagerShellCommand
     *     por dentro, otro binario: si algo filtra el literal `pm
     *     install`, esta puede pasar);
     *  3) `pm install -r` sin `-g` (sin conceder permisos en la
     *     instalación; si `-g` es lo que mata la sesión, esta pasa).
     *
     * v0.1.20: la APK se prepara ANTES en /data/local/tmp (ver
     * stageApkForInstall: el system_server no lee Descargas por SELinux
     * FUSE) y cada variante se prueba en la MISMA conexión que dejó el
     * probe (execOnce reusa la conexión viva) — si el transporte muere
     * sin salida, se reconecta y se reintenta LA MISMA variante una vez
     * antes de pasar a la siguiente. Antes se avanzaba directamente a la
     * siguiente variante: con conexiones nuevas muriendo ~la mitad de las
     * veces (log 04:08/04:09), la escalera se gastaba en muertes de
     * transporte sin probar de verdad cada comando. Solo se avanza si el
     * intento murió SIN salida (error de transporte): si pm imprimió
     * algo, es una respuesta real (éxito o INSTALL_FAILED_…) y se
     * devuelve tal cual.
     */
    suspend fun installApk(apkDevicePath: String): ShellResult {
        // -r = reinstalar conservando datos, -g = conceder permisos de runtime.
        val stagedPath = stageApkForInstall(apkDevicePath) ?: apkDevicePath
        val attempts = listOf(
            "pm install -r -g \"$stagedPath\"",
            "cmd package install -r -g \"$stagedPath\"",
            "pm install -r \"$stagedPath\"",
        )
        var last = ShellResult("", "sin intentos", -1)
        for ((i, cmd) in attempts.withIndex()) {
            var res = execOnce(cmd, timeoutMs = 300_000)
            if (res.output.isBlank() && isTransportErrorText(res.error)) {
                // Murió sin salida: reconectar y reintentar LA MISMA
                // variante una vez (execOnce reconecta solo al ver
                // connected=false) antes de darla por perdida.
                StartupLog.mark(
                    appContext,
                    "adb installApk: intento ${i + 1} murió sin salida, " +
                        "reconectando y reintentando la misma variante"
                )
                res = execOnce(cmd, timeoutMs = 300_000)
            }
            last = res
            if (installSucceeded(res)) {
                if (i > 0) {
                    StartupLog.mark(
                        appContext,
                        "adb installApk: intento ${i + 1} OK (${cmd.take(30)}) tras fallar los anteriores"
                    )
                }
                cleanupStaged(stagedPath, apkDevicePath)
                return res
            }
            if (!isTransportErrorText(res.error) || res.output.isNotBlank()) {
                // pm habló (Failure […] o cualquier salida): respuesta
                // real, no tiene sentido probar otra variante.
                StartupLog.mark(
                    appContext,
                    "adb installApk: intento ${i + 1} falló con salida de pm, sin más variantes: ${res.combined.take(200)}"
                )
                cleanupStaged(stagedPath, apkDevicePath)
                return res
            }
            StartupLog.mark(
                appContext,
                "adb installApk: intento ${i + 1} murió sin salida (transporte ADB), " +
                    if (i + 1 < attempts.size) "probando variante ${i + 2}" else "sin más variantes"
            )
        }
        cleanupStaged(stagedPath, apkDevicePath)
        return last
    }

    fun installSucceeded(result: ShellResult): Boolean =
        result.output.contains("Success", ignoreCase = true)

    /** `pm uninstall <paquete>` + borrar OBB y datos (como UninstallGame del PC). */
    suspend fun uninstallGame(packageName: String): ShellResult {
        require(packageName.isNotBlank() && !packageName.contains("/") && !packageName.contains("..")) {
            "Nombre de paquete no válido: $packageName"
        }
        val res = execQuick("pm uninstall $packageName")
        // Aunque el pm falle (p. ej. app de sistema), intentar limpiar restos.
        execQuick("rm -rf /sdcard/Android/obb/$packageName")
        execQuick("rm -rf /sdcard/Android/data/$packageName")
        return res
    }

    // ------------------------------------------------------------------
    // Copias DESACOPLADAS del transporte ADB (v0.1.21)
    // ------------------------------------------------------------------

    /**
     * v0.1.21: el transporte ADB de este Xiaomi muere en casi cada
     * comando que no es instantáneo (log 04:34 de Edu: echo OK 15-20 ms;
     * mkdir -p muere a los 21-31 ms; cp de 120 MB muere a los ~300 ms;
     * pm install muere a los 40-68 ms). Una copia en primer plano (OBB
     * ~1 GB, staging de APK 120 MB) necesita el stream vivo SEGUNDOS:
     * casi nunca sobrevive. Pero LANZAR la copia es instantáneo (~20 ms,
     * como un echo): el shell arranca el trabajo en segundo plano y
     * sale; la copia sigue viva sin ningún stream (setsid = sesión
     * nueva, nohup = inmune a SIGHUP, redirecciones a /dev/null = sin
     * SIGPIPE). Después se sondea el avance con comandos cortos
     * (stat/ls), cada uno una moneda al aire, pero con minutos de
     * margen: basta que UNO pase.
     *
     * El sync service de ADB (push) NO se usa: libadb 3.1.1 no trae
     * clase de sync (verificado en el AAR) y, aunque se implementara el
     * protocolo a mano, el push necesita el stream vivo TODA la
     * transferencia — el mismo problema que el cp en primer plano. El
     * nohup es estrictamente mejor aquí.
     */

    /** Escapa una ruta para usarla entre comillas dobles dentro de un script sh (que a su vez va entre comillas simples en el lanzamiento). */
    private fun shDq(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace("\"", "\\\"")
            .replace("$", "\\\$")
            .replace("`", "\\`")

    /** Base de los marcadores de un trabajo desacoplado en /data/local/tmp. */
    private fun detachedJobBase(kind: String, packageName: String): String {
        val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "/data/local/tmp/rq-$kind-$safe"
    }

    /**
     * Lanza un script en segundo plano, desacoplado del stream ADB.
     * Devuelve true si el exec sobrevivió (el trabajo casi seguro
     * arrancó); false si el transporte murió antes (el trabajo PUEDE
     * haber arrancado igual: el sondeo lo dirá por los marcadores).
     * Sin reintento interno: relanzar a ciegas duplicaría el trabajo.
     *
     * v0.1.22: el pre-check de herramientas (`detachedTools()`) YA NO
     * existe: era un exec más antes del lanzamiento y moría por
     * transporte (log 05:13:40: 31 ms), gastando el único comando que
     * quizá pasaba. La variante usada (setsid+nohup / nohup / plano)
     * la decide el propio comando de lanzamiento y queda en su salida
     * (VARIANT:...) y en el log, sin exec extra.
     */
    private suspend fun launchDetached(innerScript: String): Boolean {
        val line = "if command -v setsid >/dev/null 2>&1 && command -v nohup >/dev/null 2>&1; then " +
            "setsid nohup sh -c '$innerScript' >/dev/null 2>&1 < /dev/null & echo VARIANT:setsid-nohup; " +
            "elif command -v nohup >/dev/null 2>&1; then " +
            "nohup sh -c '$innerScript' >/dev/null 2>&1 < /dev/null & echo VARIANT:nohup; " +
            "else sh -c '$innerScript' >/dev/null 2>&1 < /dev/null & echo VARIANT:plain-bg; fi; echo LAUNCHED"
        val res = execOnce(line, timeoutMs = 30_000)
        val variant = Regex("VARIANT:(\\S+)").find(res.output)?.groupValues?.get(1)
        if (!res.isError) {
            StartupLog.mark(
                appContext,
                "adb detached: lanzamiento con ${variant ?: "?"} (exec OK)"
            )
        } else {
            StartupLog.mark(
                appContext,
                "adb detached: lanzamiento (${variant ?: "variante desconocida, el exec murió"}) " +
                    "murió por transporte — el trabajo puede haber arrancado igual, se sondea"
            )
        }
        return !res.isError
    }

    /** Borra los marcadores de un trabajo (mejor esfuerzo, sin reconectar: v0.1.22). */
    private suspend fun cleanupJobMarkers(jobBase: String) {
        try {
            execPoll("rm -f \"$jobBase.started\" \"$jobBase.status\" \"$jobBase.list\"")
        } catch (_: Exception) {
        }
    }

    /** Estado de un sondeo de copia de OBB. */
    private data class ObbPollState(
        val status: Int?,
        val started: Boolean,
        val totalBytes: Long,
        val finalSizes: Map<String, Long>,
    )

    private fun parseObbPoll(output: String): ObbPollState {
        var status: Int? = null
        var started = false
        var total = 0L
        val finals = mutableMapOf<String, Long>()
        for (raw in output.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("STATUS:") -> status = line.removePrefix("STATUS:").trim().toIntOrNull()
                line == "STARTED" -> started = true
                line.contains(' ') -> {
                    val idx = line.lastIndexOf(' ')
                    val size = line.substring(idx + 1).toLongOrNull() ?: continue
                    val name = line.substring(0, idx).substringAfterLast('/')
                    total += size
                    if (name.endsWith(".obb", ignoreCase = true)) finals[name] = size
                }
            }
        }
        return ObbPollState(status, started, total, finals)
    }

    /** Script del trabajo desacoplado de OBB: marca started, crea la carpeta, copia cada .obb (temp+rename) y deja status+listado. */
    private fun obbJobScript(stagingDir: String, targetDir: String, jobBase: String, jobTag: String): String {
        val s = shDq(stagingDir)
        val t = shDq(targetDir)
        val j = shDq(jobBase)
        return "echo started > \"$j.started\"; " +
            "mkdir -p \"$t\"; " +
            "find \"$s\" -type f -iname \"*.obb\" | while read -r f; do " +
            "b=\$(basename \"\$f\"); " +
            "cp \"\$f\" \"$t/.rq-$jobTag-\$b.tmp\" && mv \"$t/.rq-$jobTag-\$b.tmp\" \"$t/\$b\"; " +
            "done; " +
            "echo \$? > \"$j.status\"; " +
            "ls -1 \"$t\" > \"$j.list\" 2>/dev/null"
    }

    /**
     * Copia el OBB con un trabajo desacoplado (v0.1.21, primaria;
     * v0.1.22: lanzamiento primero + sondeo sin reconectar + backoff).
     * El lanzamiento es instantáneo; el sondeo verifica tamaños.
     */
    private suspend fun copyObbDetached(
        packageName: String,
        stagingDeviceDir: String,
        obbFiles: List<File>,
    ): ObbCopyOutcome {
        val target = "/sdcard/Android/obb/$packageName"
        val expected = obbFiles.associate { it.name to it.length() }
        val jobBase = detachedJobBase("obb", packageName)
        val pollCmd = "test -f \"$jobBase.status\" && echo STATUS:\$(cat \"$jobBase.status\"); " +
            "test -f \"$jobBase.started\" && echo STARTED; " +
            "stat -c '%n %s' \"$target\"/*.obb \"$target\"/.rq-*.tmp 2>/dev/null"
        var launches = 0
        var deadPolls = 0
        var backoffMs = 2_000L
        var lastTotal: Long? = null
        var staticPolls = 0
        val deadline = System.currentTimeMillis() + DETACHED_OBB_TIMEOUT_MS

        // Un lanzamiento de trabajo desacoplado (v0.1.22): loguea y
        // cuenta. Devuelve false si se acabaron los lanzamientos.
        suspend fun launchOnce(): Boolean {
            if (launches >= DETACHED_MAX_LAUNCHES) return false
            val tag = System.currentTimeMillis().toString()
            val script = obbJobScript(stagingDeviceDir, target, jobBase, tag)
            val launched = launchDetached(script)
            launches++
            StartupLog.mark(
                appContext,
                "adb copyObb desacoplada: lanzamiento $launches/$DETACHED_MAX_LAUNCHES " +
                    "(exec ${if (launched) "OK" else "murió por transporte — el trabajo puede haber arrancado igual, se sondea"})"
            )
            return true
        }

        // v0.1.22: LANZAR PRIMERO, sin condicionarlo a un sondeo previo
        // ni a ningún pre-check. En la v0.1.21 el lanzamiento solo
        // ocurría tras un sondeo con éxito que mostrara !started &&
        // total==0; con el transporte muerto (log 05:13:40: el pre-check
        // murió a los 31 ms y cada sondeo a los ~2 s) el sondeo nunca
        // tenía éxito y el trabajo JAMÁS arrancaba: la app solo sondeaba
        // el archivo de estado de un trabajo que no existía. El
        // lanzamiento es un exec instantáneo (~20 ms como un echo) y
        // `launchDetached` ya usa execOnce (sin reintento): si el
        // transporte muere al lanzar, el trabajo PUEDE haber arrancado
        // igual y el sondeo lo dirá por los marcadores.
        launchOnce()
        while (System.currentTimeMillis() < deadline) {
            if (authRejected) {
                StartupLog.mark(
                    appContext,
                    "adb copyObb desacoplada: clave rechazada, sin sondear más (re-empareja por el Wizard)"
                )
                return ObbCopyOutcome(false, launches > 0, "clave ADB rechazada: re-empareja por el Wizard")
            }
            val out = execPoll(pollCmd)
            if (out == null) {
                // v0.1.22: sondeo muerto SIN reconectar (execPoll no
                // reconecta) y con backoff exponencial (2s→30s). En la
                // v0.1.21 cada sondeo muerto reconectaba y reintentaba
                // cada ~2 s — decenas de conexiones nuevas por minuto,
                // cada una pudiendo despertar el diálogo del sistema de
                // depuración inalámbrica (el síntoma de Edu 05:15) y
                // machacando un transporte ya muerto. Tras N sondeos
                // muertos seguidos se abandona el intento con UNA sola
                // línea: el trabajo, si llegó a lanzarse, sigue en curso
                // en el móvil y el temporizador de OBB lo comprobará por
                // tamaños.
                deadPolls++
                if (deadPolls >= DETACHED_MAX_DEAD_POLLS) {
                    StartupLog.mark(
                        appContext,
                        "adb copyObb desacoplada: transporte muerto tras $deadPolls sondeos seguidos " +
                            "(backoff hasta ${backoffMs / 1000}s); el trabajo puede seguir en curso, queda pendiente " +
                            "(lanzamientos: $launches)"
                    )
                    return ObbCopyOutcome(
                        false, launches > 0,
                        "transporte ADB muerto: no se pudo confirmar la copia desacoplada " +
                            "(lanzamientos: $launches; el trabajo puede seguir en curso en el móvil)"
                    )
                }
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 30_000L)
                continue
            }
            deadPolls = 0
            backoffMs = 2_000L
            val state = parseObbPoll(out)
            if (state.status != null) {
                cleanupJobMarkers(jobBase)
                return if (state.status == 0 && expected.all { (n, s) -> state.finalSizes[n] == s }) {
                    StartupLog.mark(
                        appContext,
                        "adb copyObb desacoplada: OBB completado (${expected.size} .obb, ${expected.values.sum()} bytes)"
                    )
                    ObbCopyOutcome(true, true, "OBB copiado (${expected.size} archivos, ${expected.values.sum()} bytes)")
                } else {
                    ObbCopyOutcome(
                        false, true,
                        "la copia desacoplada terminó con status=${state.status}; en destino ${state.finalSizes}; esperados $expected"
                    )
                }
            }
            if (!state.started && state.totalBytes == 0L) {
                // El sondeo SÍ respondió y demuestra que el trabajo no
                // arrancó (el lanzamiento inicial no llegó al móvil):
                // relanzar hasta el tope. `attempted=false` aquí: los
                // sondeos demuestran que el trabajo nunca arrancó.
                if (!launchOnce()) {
                    return ObbCopyOutcome(
                        false, false,
                        "la copia desacoplada no llegó a arrancar tras $launches lanzamientos (el transporte muere al lanzar)"
                    )
                }
                delay(2_000)
                continue
            }
            if (state.totalBytes == lastTotal) staticPolls++ else {
                staticPolls = 0
                lastTotal = state.totalBytes
            }
            if (staticPolls >= 2) {
                StartupLog.mark(
                    appContext,
                    "adb copyObb desacoplada: sin avance 10s (total ${state.totalBytes} bytes), el trabajo murió a mitad — relanzando"
                )
                if (!launchOnce()) {
                    return ObbCopyOutcome(
                        false, true,
                        "la copia desacoplada se quedó parada tras $launches lanzamientos"
                    )
                }
                staticPolls = 0
                lastTotal = null
                delay(2_000)
            } else {
                delay(DETACHED_POLL_MS)
            }
        }
        return ObbCopyOutcome(
            false, true,
            "timeout ${DETACHED_OBB_TIMEOUT_MS / 60_000} min esperando la copia desacoplada (puede seguir en curso; queda pendiente)"
        )
    }

    /**
     * Staging de la APK con copia desacoplada (v0.1.21; v0.1.22:
     * lanzamiento primero + sondeo sin reconectar + backoff, igual que
     * copyObbDetached). El cp en primer plano moría con el transporte
     * a los ~300 ms (log 04:34:23, dos intentos). Aquí el lanzamiento
     * es instantáneo y se sondea el tamaño hasta que coincide con el
     * esperado.
     */
    private suspend fun stageApkDetached(
        apkDevicePath: String,
        stagedPath: String,
        expectedSize: Long,
    ): Boolean {
        val jobBase = detachedJobBase("stage", File(apkDevicePath).parentFile?.name ?: "apk")
        val script = "echo started > \"${shDq("$jobBase.started")}\"; " +
            "cp \"${shDq(apkDevicePath)}\" \"${shDq("$stagedPath.tmp")}\" && mv \"${shDq("$stagedPath.tmp")}\" \"${shDq(stagedPath)}\"; " +
            "echo \$? > \"${shDq("$jobBase.status")}\""
        val pollCmd = "test -f \"$jobBase.status\" && echo STATUS:\$(cat \"$jobBase.status\"); " +
            "test -f \"$jobBase.started\" && echo STARTED; " +
            "stat -c 'staged %s' \"$stagedPath\" 2>/dev/null; " +
            "stat -c 'tmp %s' \"$stagedPath.tmp\" 2>/dev/null"
        var launches = 0
        var deadPolls = 0
        var backoffMs = 2_000L
        var lastSize: Long? = null
        var staticPolls = 0
        val deadline = System.currentTimeMillis() + DETACHED_STAGE_TIMEOUT_MS

        suspend fun launchOnce(): Boolean {
            if (launches >= DETACHED_MAX_LAUNCHES) return false
            val launched = launchDetached(script)
            launches++
            StartupLog.mark(
                appContext,
                "adb stageApk desacoplado: lanzamiento $launches/$DETACHED_MAX_LAUNCHES (exec ${if (launched) "OK" else "murió por transporte"})"
            )
            return true
        }

        // v0.1.22: lanzar primero (mismo motivo que en copyObbDetached:
        // condicionar el lanzamiento a un sondeo con éxito dejaba el
        // trabajo sin arrancar cuando el transporte estaba muerto).
        launchOnce()
        while (System.currentTimeMillis() < deadline) {
            if (authRejected) {
                StartupLog.mark(
                    appContext,
                    "adb stageApk desacoplado: clave rechazada, sin sondear más (re-empareja por el Wizard)"
                )
                return false
            }
            val out = execPoll(pollCmd)
            if (out == null) {
                // v0.1.22: sin reconectar y con backoff exponencial
                // (2s→30s); tras N sondeos muertos seguidos se abandona
                // con UNA sola línea (el trabajo puede seguir en curso;
                // stageApkForInstall instala desde la ruta original).
                deadPolls++
                if (deadPolls >= DETACHED_MAX_DEAD_POLLS) {
                    StartupLog.mark(
                        appContext,
                        "adb stageApk desacoplado: transporte muerto tras $deadPolls sondeos seguidos " +
                            "(lanzamientos: $launches); instalando desde la ruta original"
                    )
                    return false
                }
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 30_000L)
                continue
            }
            deadPolls = 0
            backoffMs = 2_000L
            var status: Int? = null
            var started = false
            var stagedSize: Long? = null
            var tmpSize: Long? = null
            for (raw in out.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("STATUS:") -> status = line.removePrefix("STATUS:").trim().toIntOrNull()
                    line == "STARTED" -> started = true
                    line.startsWith("staged ") -> stagedSize = line.removePrefix("staged ").trim().toLongOrNull()
                    line.startsWith("tmp ") -> tmpSize = line.removePrefix("tmp ").trim().toLongOrNull()
                }
            }
            if (stagedSize == expectedSize) {
                cleanupJobMarkers(jobBase)
                return true
            }
            if (status != null) {
                cleanupJobMarkers(jobBase)
                StartupLog.mark(
                    appContext,
                    "adb stageApk desacoplado: terminó con status=$status, tamaño $stagedSize (esperado $expectedSize)"
                )
                return false
            }
            val current = (stagedSize ?: 0L) + (tmpSize ?: 0L)
            if (!started && current == 0L) {
                // El sondeo respondió y demuestra que el trabajo no
                // arrancó: relanzar hasta el tope.
                if (!launchOnce()) return false
                delay(2_000)
                continue
            }
            if (current == lastSize) staticPolls++ else {
                staticPolls = 0
                lastSize = current
            }
            if (staticPolls >= 3) {
                if (!launchOnce()) return false
                StartupLog.mark(
                    appContext,
                    "adb stageApk desacoplado: sin avance 6s, relanzamiento $launches/$DETACHED_MAX_LAUNCHES"
                )
                staticPolls = 0
                lastSize = null
                delay(2_000)
            } else {
                delay(2_000)
            }
        }
        StartupLog.mark(appContext, "adb stageApk desacoplado: timeout ${DETACHED_STAGE_TIMEOUT_MS / 1000}s")
        return false
    }

    /**
     * Copia el OBB en primer plano con UN SOLO comando (fallback de la
     * desacoplada, v0.1.21): mkdir + cp de cada .obb (temp+rename) +
     * verificación, todo en un exec, hasta 5 intentos con conexión nueva
     * cada vez. Un solo comando = una sola oportunidad de morir, en vez
     * de 4 execs seguidos (ls, mkdir, cp, stat) como en la v0.1.20.
     */
    private suspend fun copyObbSingleCommand(
        packageName: String,
        stagingDeviceDir: String,
        obbFiles: List<File>,
    ): ObbCopyOutcome {
        val target = "/sdcard/Android/obb/$packageName"
        val expected = obbFiles.associate { it.name to it.length() }
        val copies = obbFiles.joinToString(" && ") { obb ->
            val tmp = ".rq-fg-${obb.name}.tmp"
            "cp \"${shDq(obb.absolutePath)}\" \"$target/$tmp\" && mv \"$target/$tmp\" \"$target/${shDq(obb.name)}\""
        }
        val cmd = "mkdir -p \"$target\" && $copies; echo STATUS:\$?; stat -c '%n %s' \"$target\"/*.obb 2>/dev/null"
        var anyRan = false
        repeat(5) { attempt ->
            val res = execOnce(cmd, timeoutMs = 600_000)
            if (res.isError) {
                StartupLog.mark(
                    appContext,
                    "adb copyObb primer plano: intento ${attempt + 1}/5 FALLÓ (transporte): ${res.combined.take(150)}"
                )
                return@repeat
            }
            anyRan = true
            val state = parseObbPoll(res.output)
            if (state.status == 0 && expected.all { (n, s) -> state.finalSizes[n] == s }) {
                return ObbCopyOutcome(true, true, "OBB copiado en primer plano (${expected.size} archivos)")
            }
            StartupLog.mark(
                appContext,
                "adb copyObb primer plano: intento ${attempt + 1}/5 con status=${state.status}, en destino ${state.finalSizes}"
            )
        }
        return ObbCopyOutcome(
            false, anyRan,
            "la copia en primer plano no completó el OBB en 5 intentos"
        )
    }

    /**
     * Copia SOLO los archivos .obb del staging a /sdcard/Android/obb/<paquete>/.
     *
     * v0.1.21: la copia en primer plano con varios execs seguidos (ls,
     * mkdir, cp, stat) no sobrevive al transporte de este Xiaomi (log
     * 04:34: mkdir muere a los 31 ms, cp a los 319 ms). Ahora la primaria
     * es la copia DESACOPLADA (lanzamiento instantáneo + sondeo) y el
     * fallback es UN SOLO comando en primer plano (hasta 5 intentos).
     */
    suspend fun copyObb(packageName: String, stagingDeviceDir: String): ObbCopyOutcome {
        require(packageName.isNotBlank() && !packageName.contains("/") && !packageName.contains(".."))
        val obbFiles = try {
            File(stagingDeviceDir).walkTopDown()
                .filter { it.isFile && it.extension.equals("obb", true) }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
        if (obbFiles.isEmpty()) {
            StartupLog.mark(
                appContext,
                "adb copyObb: $stagingDeviceDir no trae ningún archivo .obb"
            )
            return ObbCopyOutcome(false, false, "el staging $stagingDeviceDir no trae ningún archivo .obb")
        }
        // 1) Desacoplada (primaria).
        val detached = copyObbDetached(packageName, stagingDeviceDir, obbFiles)
        if (detached.ok) return detached
        StartupLog.mark(
            appContext,
            "adb copyObb: copia desacoplada FALLÓ (${detached.detail.take(200)}), probando en primer plano (un solo comando, hasta 5 intentos)"
        )
        // 2) Un solo comando en primer plano (fallback).
        return copyObbSingleCommand(packageName, stagingDeviceDir, obbFiles)
    }


    /** Concederse WRITE_SECURE_SETTINGS a uno mismo vía shell (una vez con ADB). */
    suspend fun grantWriteSecureSettings(): Boolean {
        val res = execQuick("pm grant es.girarkas.giraquest android.permission.WRITE_SECURE_SETTINGS")
        val ok = !res.isError
        if (ok) {
            settings.writeSecureSettingsGranted = true
            StartupLog.mark(
                appContext,
                "adb grant: WRITE_SECURE_SETTINGS concedido (pm grant OK) — la app ya puede re-activar la depuración sola"
            )
        } else {
            // v0.1.25: el resultado quedaba en silencio y el grant
            // fallaba sin que nadie lo supiera (hasta la v0.1.24 el
            // permiso ni siquiera estaba declarado en el manifest, así
            // que el pm grant no podía funcionar). Queda en el log con
            // su motivo para no perseguirlo a ciegas.
            StartupLog.mark(
                appContext,
                "adb grant FALLÓ: WRITE_SECURE_SETTINGS no concedido: ${res.combined.take(200)}"
            )
        }
        return ok
    }

    /** Re-activar la depuración inalámbrica tras un reinicio (requiere el permiso anterior). */
    suspend fun enableWirelessDebugging(): Boolean {
        val res = execQuick("settings put global adb_wifi_enabled 1")
        val ok = !res.isError
        StartupLog.mark(
            appContext,
            "adb wifi por shell: adb_wifi_enabled=1 ${if (ok) "OK" else "FALLÓ: ${res.combined.take(150)}"}"
        )
        return ok
    }

    /** ¿Está la depuración inalámbrica activa? (vía settings global). */
    suspend fun isWirelessDebuggingOn(): Boolean {
        val res = execQuick("settings get global adb_wifi_enabled")
        return res.output.trim() == "1"
    }

    // ------------------------------------------------------------------
    // Explorador sin límites vía shell ADB (UID 2000, v0.1.50)
    // ------------------------------------------------------------------

    data class AdbFileEntry(
        val name: String,
        val fullPath: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    fun clearAuthRejection() {
        authRejected = false
        authRejectedReason = null
    }

    /**
     * Conecta bajo demanda para el explorador de archivos si ya hubo un
     * emparejamiento previo (TLS o TCP). Nunca bloquea si la clave fue
     * rechazada o si nunca se emparejó todavía.
     */
    suspend fun ensureConnectedForExplorer(allowMdnsDiscovery: Boolean = true): Boolean {
        if (connectHung || authRejected) return false
        if (!isTcpMode && !settings.adbPaired && settings.adbConnectPort <= 0) return false
        if (isTcpMode && !settings.adbTcpAuthorized) return false

        if (connected && probe()) return true
        disconnect()
        if (connectSaved() && probe()) return true

        if (!isTcpMode && settings.writeSecureSettingsGranted && !isWirelessDebuggingOnDirect()) {
            if (enableWirelessDebuggingDirect()) {
                delay(1_500)
                if (connectSaved() && probe()) return true
            }
        }
        if (!allowMdnsDiscovery) return false

        val ep = withTimeoutOrNull(4_500) {
            if (isTcpMode) AdbPortDiscovery.discoverTcp(appContext, 4_000)
            else AdbPortDiscovery.discoverConnect(appContext, 4_000)
        } ?: return false

        disconnect()
        val ok = if (isTcpMode) connectTcp(ep.host, ep.port) else connectTo(ep.host, ep.port)
        return ok && probe()
    }

    suspend fun listDirShell(dirPath: String): List<AdbFileEntry>? {
        if (!connected && !ensureConnectedForExplorer()) return null
        val candidates = if (dirPath.startsWith("/storage/emulated/0")) {
            listOf(dirPath, "/sdcard" + dirPath.removePrefix("/storage/emulated/0"))
        } else {
            listOf(dirPath)
        }
        for (cand in candidates) {
            val q = shDq(cand.trimEnd('/').ifEmpty { "/" })
            val cmd = "d=\"$q\"; if [ ! -d \"\$d\" ]; then echo \"__NOT_DIR__\"; exit 1; fi; echo \"__OK__\"; ls -lAn \"\$d\""
            val res = execQuick(cmd)
            val lines = res.output.lines()
            val okIdx = lines.indexOfFirst { it.trim() == "__OK__" }
            if (okIdx < 0) continue

            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val lineRegex = Regex(
                "^([dl\\-cbps])\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+(.+)$"
            )
            val base = dirPath.trimEnd('/')
            val out = mutableListOf<AdbFileEntry>()
            for (i in (okIdx + 1) until lines.size) {
                val line = lines[i].trimEnd()
                if (line.isBlank() || line.startsWith("total ")) continue
                val m = lineRegex.matchEntire(line) ?: continue
                val typeChar = m.groupValues[1]
                val size = m.groupValues[2].toLongOrNull() ?: 0L
                val dateStr = m.groupValues[3]
                val rawName = m.groupValues[4]
                val name = if (typeChar == "l") rawName.substringBefore(" -> ") else rawName
                if (name == "." || name == "..") continue
                val mtime = try {
                    dateFmt.parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
                val isDir = typeChar == "d" || typeChar == "l"
                out += AdbFileEntry(
                    name = name,
                    fullPath = if (base.isEmpty()) "/$name" else "$base/$name",
                    isDirectory = isDir,
                    size = size,
                    lastModified = mtime,
                )
            }
            return out
        }
        return null
    }

    suspend fun mkdirShell(dirPath: String): Boolean {
        if (!connected && !ensureConnectedForExplorer()) return false
        val res = execQuick("mkdir -p \"${shDq(dirPath)}\" && echo \"__OK__\"")
        return !res.isError && res.output.lines().any { it.trim() == "__OK__" }
    }

    suspend fun existsShell(path: String): Boolean {
        if (!connected && !ensureConnectedForExplorer()) return false
        val res = execQuick("[ -e \"${shDq(path)}\" ] && echo \"__YES__\"")
        return !res.isError && res.output.lines().any { it.trim() == "__YES__" }
    }

    suspend fun uniqueDestShell(dirPath: String, name: String): String {
        val baseDir = dirPath.trimEnd('/')
        var candidate = "$baseDir/$name"
        if (!existsShell(candidate)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (i <= 200) {
            val candName = "$stem ($i)$ext"
            candidate = "$baseDir/$candName"
            if (!existsShell(candidate)) return candName
            i++
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    suspend fun copyShell(srcPath: String, destDirPath: String, destName: String): Boolean {
        if (!connected && !ensureConnectedForExplorer()) return false
        val destPath = "${destDirPath.trimEnd('/')}/$destName"
        val res = exec(
            "mkdir -p \"${shDq(destDirPath)}\" && cp -r \"${shDq(srcPath)}\" \"${shDq(destPath)}\" && echo \"__OK__\"",
            timeoutMs = 300_000,
        )
        return !res.isError && res.output.lines().any { it.trim() == "__OK__" }
    }

    suspend fun moveShell(srcPath: String, destDirPath: String, destName: String): Boolean {
        if (!connected && !ensureConnectedForExplorer()) return false
        val destPath = "${destDirPath.trimEnd('/')}/$destName"
        val res = exec(
            "mkdir -p \"${shDq(destDirPath)}\" && (mv \"${shDq(srcPath)}\" \"${shDq(destPath)}\" || (cp -r \"${shDq(srcPath)}\" \"${shDq(destPath)}\" && rm -rf \"${shDq(srcPath)}\")) && echo \"__OK__\"",
            timeoutMs = 300_000,
        )
        return !res.isError && res.output.lines().any { it.trim() == "__OK__" }
    }

    suspend fun renameShell(srcPath: String, newPath: String): Boolean {
        if (!connected && !ensureConnectedForExplorer()) return false
        val res = execQuick("mv \"${shDq(srcPath)}\" \"${shDq(newPath)}\" && echo \"__OK__\"")
        return !res.isError && res.output.lines().any { it.trim() == "__OK__" }
    }

    suspend fun deleteShell(targetPath: String): Boolean {
        val norm = targetPath.trim().trimEnd('/')
        val protectedRoots = setOf(
            "", "/sdcard", "/storage", "/storage/emulated",
            "/storage/emulated/0", "/storage/emulated/0/Android", "/sdcard/Android"
        )
        if (norm in protectedRoots) return false
        if (!connected && !ensureConnectedForExplorer()) return false
        val res = execQuick("rm -rf \"${shDq(targetPath)}\" && echo \"__OK__\"")
        return !res.isError && res.output.lines().any { it.trim() == "__OK__" }
    }

    suspend fun stageRestrictedFileToCache(srcPath: String, cacheFile: File): Boolean {
        if (!connected && !ensureConnectedForExplorer()) return false
        withContext(Dispatchers.IO) {
            cacheFile.parentFile?.mkdirs()
            cacheFile.delete()
        }
        val res = exec(
            "cp \"${shDq(srcPath)}\" \"${shDq(cacheFile.absolutePath)}\" && echo \"__OK__\"",
            timeoutMs = 180_000,
        )
        if (res.isError || !res.output.lines().any { it.trim() == "__OK__" }) return false
        return withContext(Dispatchers.IO) { cacheFile.exists() && cacheFile.isFile }
    }

    data class ShellResult(val output: String, val error: String, val code: Int) {
        val isError: Boolean get() = code != 0 || error.isNotBlank()
        val combined: String get() = listOf(output, error).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Resultado de una copia de OBB (v0.1.21). `attempted` = la copia llegó a arrancar de verdad (no murió el transporte antes de lanzar). */
    data class ObbCopyOutcome(val ok: Boolean, val attempted: Boolean, val detail: String)
}
