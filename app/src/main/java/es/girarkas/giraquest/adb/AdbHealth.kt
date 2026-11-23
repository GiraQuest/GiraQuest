package es.girarkas.giraquest.adb

/**
 * Salud del ADB embebido (v0.1.1).
 *
 * La v0.1 dejaba que cualquier fallo de inicialización (Conscrypt nativo,
 * claves RSA) propagara una excepción y matara la app al arrancar. Ahora
 * los fallos se guardan aquí y el asistente los muestra como
 * "ADB no disponible: <motivo>" con un botón de reintento, sin crashear.
 */
object AdbHealth {
    /**
     * Conscrypt no se pudo registrar (v0.1.22: legado — la app ya NO
     * registra Conscrypt globalmente; queda por compatibilidad y
     * siempre es null. libadb usa su propio Conscrypt explícito).
     */
    @Volatile
    var conscryptError: Throwable? = null

    /** Las claves RSA no se pudieron crear/cargar. */
    @Volatile
    var keyStoreError: Throwable? = null

    /** Primer error de inicialización que haya, si lo hay. */
    val firstError: Throwable? get() = conscryptError ?: keyStoreError

    fun shortReason(): String? {
        val e = firstError ?: return null
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "${e.javaClass.simpleName}: $msg"
    }

    /**
     * Cadena de causas COMPLETA (v0.1.5): recorre getCause() hasta el
     * final, un nivel por línea ("Clase: mensaje"). La v0.1.4 solo
     * enseñaba la excepción de arriba (shortReason): en el fallo real
     * de Edu se veía "NoClassDefFoundError: OperatorHelper" sin decir
     * QUÉ clase faltaba debajo (era CMSObjectIdentifiers), y el
     * siguiente fallo volvía a ser un misterio. Con la cadena, el
     * motivo raíz queda en el diálogo y en el StartupLog.
     */
    fun fullChainReason(e: Throwable?): String? {
        if (e == null) return null
        val seen = HashSet<Throwable>()
        val parts = ArrayList<String>()
        var cur: Throwable? = e
        while (cur != null && seen.add(cur)) {
            val msg = cur.message?.takeIf { it.isNotBlank() }
            parts += if (msg != null) "${cur.javaClass.simpleName}: $msg"
            else cur.javaClass.simpleName
            cur = cur.cause
        }
        return parts.joinToString(" ← causado por: ")
    }

    /** Cadena completa del primer error de inicialización, si lo hay. */
    fun fullChainFirstError(): String? = fullChainReason(firstError)
}
