package es.girarkas.giraquest.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import es.girarkas.giraquest.R
import es.girarkas.giraquest.data.InstallOrigin
import es.girarkas.giraquest.data.InstallState
import es.girarkas.giraquest.data.ObbUiState
import es.girarkas.giraquest.data.RepoEntry
import es.girarkas.giraquest.download.ThumbnailCache
import es.girarkas.giraquest.install.Installer

/**
 * Lista/galería de juegos del repo (equivale a MainForm.cs + GalleryView.cs
 * del PC). Un toque abre el detalle; doble toque instala (con confirmación);
 * pulsación larga marca/desmarca favorito.
 *
 * Carátulas (obligatorio en v0.1, como en el PC):
 *  - Vista lista: miniatura (iconUrl del repo; si no hay, miniatura del
 *    tráiler de YouTube; si tampoco, placeholder).
 *  - Vista galería: carátula grande (misma fuente, imagen 16:9).
 * Carga con Coil, con caché en disco y placeholder si falla.
 */
class RepoAdapter(
    private var items: List<Pair<RepoEntry, InstallState>>,
    private var isFavorite: (String) -> Boolean,
    private val isGallery: () -> Boolean,
    private val thumbnailCache: ThumbnailCache,
    private val onTap: (RepoEntry) -> Unit,
    private val onDoubleTap: (RepoEntry) -> Unit,
    private val onLongPress: (RepoEntry) -> Unit,
    /** Tamaño bajo demanda (v0.1.27): solo filas visibles, con
     *  concurrencia pequeña en el fragmento; nunca bloquea el scroll. */
    private val onSizeNeeded: (RepoEntry) -> Unit = {},
    /** v0.1.30: papelera de la descarga (solo se enseña en Descargados:
     *  el fragmento pone showDeleteDownload). */
    private val onDeleteDownload: (RepoEntry) -> Unit = {},
    /** v0.1.36: estado del OBB por paquete (Edu: la fila enseña
     *  "APK ✓/✗ · OBB ✓/✗"). El fragmento lo resuelve con
     *  installer.obbUiState (registro + pendiente + disco). */
    private val obbState: (String) -> ObbUiState = { ObbUiState.Unknown },
    /** v0.1.41: indica si la entrada tiene archivos descargados en disco
     *  (incluso si ya está instalada) para mostrar su papelera en Descargados. */
    private val isEntryDownloaded: (RepoEntry) -> Boolean = { false },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** v0.1.30: enseñar la papelera de la descarga en las filas (el
     *  fragmento lo activa solo con el filtro Descargados). */
    var showDeleteDownload: Boolean = false

    /** Paquetes nuevos desde la última carga del índice (marca NUEVO). */
    var newPackages: Set<String> = emptySet()

    /** Ranking de popularidad por paquete (#1 = mayor puntuación),
     *  como el PC (MainForm.cs:2752-2759: puntuaciones > 0, orden
     *  descendente, puesto = posición + 1). Lo calcula RepoFragment al
     *  cargar el índice; sin ranking (puntuación 0) no se enseña nada. */
    var popularityRank: Map<String, Int> = emptyMap()

    /** Tamaños ya conocidos por paquete (texto listo para enseñar). */
    private val sizes = mutableMapOf<String, String>()

    /** Snapshots precalculados en segundo plano para evitar stat() en onBindViewHolder. */
    private var precomputedObbStates: Map<String, ObbUiState> = emptyMap()
    private var precomputedDefaultObb: ObbUiState? = null
    private var precomputedDownloadedPkgs: Set<String>? = null
    private var precomputedOrigins: Map<String, InstallOrigin> = emptyMap()

    /** RecyclerView al que estamos asociados (para aplazar notifies). */
    private var attachedRecycler: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecycler === recyclerView) attachedRecycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /** Actualiza el tamaño de una tarjeta sin recargar la lista.
     *  v0.1.28: NUNCA notifyItemChanged en caliente. La v0.1.27 lo
     *  llamaba directo y requestSize() lo invocaba desde onBindViewHolder
     *  para cada juego descargado: RecyclerView rechaza notificar
     *  mientras calcula el layout (IllegalStateException) y la pestaña
     *  "Descargados" crasheaba (todas las filas son descargadas). Ahora
     *  el notify se aplaza al siguiente frame y se revalida la posición. */
    fun setSize(packageName: String, text: String) {
        if (sizes[packageName] == text) return
        sizes[packageName] = text
        val rv = attachedRecycler ?: return
        rv.post {
            val pos = items.indexOfFirst { it.first.packageName == packageName }
            if (pos >= 0 && pos < itemCount) {
                try {
                    notifyItemChanged(pos)
                } catch (_: Exception) {
                    // Si el adapter cambió entre tanto, se ignora: el
                    // tamaño ya está en el mapa y se enseñará al rebind.
                }
            }
        }
    }

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_GALLERY = 1

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "?"
            if (bytes == 1L) return "< 1 MB"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.0f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            return "%.2f GB".format(mb / 1024.0)
        }

        /** "#12" — el puesto en popularidad, como el PC (que sustituye
         *  la puntuación por el ranking: MainForm.cs:2899-2906). La
         *  puntuación en sí es un decimal del índice (columna 6) y no
         *  se enseña: el puesto es lo que entiende el usuario. */
        fun formatRank(rank: Int?): String =
            if (rank != null && rank > 0) "#$rank" else ""

        /** Carátula de un juego: iconUrl del repo, o miniatura del tráiler. */
        fun coverUrl(entry: RepoEntry): String? =
            entry.iconUrl?.takeIf { it.isNotBlank() }
                ?: entry.trailerThumbnailUrl
    }

    class ListVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.item_icon)
        val name: TextView = view.findViewById(R.id.item_name)
        val sub: TextView = view.findViewById(R.id.item_sub)
        val state: TextView = view.findViewById(R.id.item_state)
        val originBadge: TextView = view.findViewById(R.id.item_origin_badge)
        val meta: TextView = view.findViewById(R.id.item_meta)
        val badge: TextView = view.findViewById(R.id.item_badge_new)
        val fav: TextView = view.findViewById(R.id.item_fav)
        val trash: ImageButton = view.findViewById(R.id.item_trash)
    }

    class GalleryVH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.item_cover)
        val name: TextView = view.findViewById(R.id.item_name)
        val state: TextView = view.findViewById(R.id.item_state)
        val originBadge: TextView = view.findViewById(R.id.item_origin_badge)
        val meta: TextView = view.findViewById(R.id.item_meta)
        val badge: TextView = view.findViewById(R.id.item_badge_new)
        val fav: TextView = view.findViewById(R.id.item_fav)
        val trash: ImageButton = view.findViewById(R.id.item_trash)
    }

    fun clearSizes() {
        sizes.clear()
    }

    /** Papelera de la descarga (v0.1.30 + v0.1.41): en Descargados y si
     *  hay descarga en disco (aunque también esté instalada). */
    private fun bindTrash(button: ImageButton, entry: RepoEntry, state: InstallState) {
        val isDownloaded = precomputedDownloadedPkgs?.contains(entry.packageName) ?: isEntryDownloaded(entry)
        val visible = showDeleteDownload && (state == InstallState.DOWNLOADED || isDownloaded)
        button.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val busy = Installer.isInstalling(entry.packageName)
        button.isEnabled = !busy
        button.alpha = if (busy) 0.4f else 1f
        button.setOnClickListener {
            if (!Installer.isInstalling(entry.packageName)) onDeleteDownload(entry)
        }
    }

    fun currentItems(): List<Pair<RepoEntry, InstallState>> = items

    fun currentOrigins(): Map<String, InstallOrigin> = precomputedOrigins

    fun update(newItems: List<Pair<RepoEntry, InstallState>>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun updateFull(
        newItems: List<Pair<RepoEntry, InstallState>>,
        obbStates: Map<String, ObbUiState>,
        defaultObbState: ObbUiState,
        downloadedPkgs: Set<String>,
        origins: Map<String, InstallOrigin>,
    ) {
        items = newItems
        precomputedObbStates = obbStates
        precomputedDefaultObb = defaultObbState
        precomputedDownloadedPkgs = downloadedPkgs
        precomputedOrigins = origins
        notifyDataSetChanged()
    }

    fun updateWithDiff(
        newItems: List<Pair<RepoEntry, InstallState>>,
        diffResult: DiffUtil.DiffResult,
        obbStates: Map<String, ObbUiState>,
        defaultObbState: ObbUiState,
        downloadedPkgs: Set<String>,
        origins: Map<String, InstallOrigin>,
    ) {
        items = newItems
        precomputedObbStates = obbStates
        precomputedDefaultObb = defaultObbState
        precomputedDownloadedPkgs = downloadedPkgs
        precomputedOrigins = origins
        diffResult.dispatchUpdatesTo(this)
    }

    class DiffCallback(
        private val oldList: List<Pair<RepoEntry, InstallState>>,
        private val newList: List<Pair<RepoEntry, InstallState>>,
        private val oldOrigins: Map<String, InstallOrigin>,
        private val newOrigins: Map<String, InstallOrigin>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val a = oldList[oldPos].first
            val b = newList[newPos].first
            return a.packageName == b.packageName && a.versionCode == b.versionCode && a.repoUrl == b.repoUrl
        }
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            if (oldList[oldPos] != newList[newPos]) return false
            val pkg = newList[newPos].first.packageName
            return oldOrigins[pkg] == newOrigins[pkg]
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (isGallery()) TYPE_GALLERY else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GALLERY) {
            GalleryVH(inflater.inflate(R.layout.item_game_gallery, parent, false))
        } else {
            ListVH(inflater.inflate(R.layout.item_game, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    /** v0.1.36 (Edu): el estado de la fila es SOLO si está instalado
     *  o no, APK y OBB por separado: "APK ✓/✗ · OBB ✓/✗". Sin rutas,
     *  sin errores, sin detalles: eso va al log y a la ficha del
     *  juego al tocarlo. APK ✓ = instalada (o actualización
     *  disponible / por delante de la lista). OBB ✓ = datos copiados;
     *  "—" = el juego no tiene datos (sin OBB); ✗ = pendiente, con
     *  fallo, sin permiso o sin comprobar. */
    private fun apkObbMarks(state: InstallState, obb: ObbUiState): Pair<String, String> {
        val apkMark = when (state) {
            InstallState.INSTALLED, InstallState.UPDATE_AVAILABLE,
            InstallState.AHEAD_OF_LIST -> "✓"
            else -> "✗"
        }
        val obbMark = when (obb) {
            is ObbUiState.Copied -> "✓"
            is ObbUiState.NoObb -> "—"
            else -> "✗"
        }
        return apkMark to obbMark
    }

    private fun apkObbText(holder: RecyclerView.ViewHolder, state: InstallState, obb: ObbUiState): String {
        val (apkMark, obbMark) = apkObbMarks(state, obb)
        return holder.itemView.context.getString(R.string.state_apk_obb, apkMark, obbMark)
    }

    /** Color del estado (v0.1.36): verde APK ✓ y OBB ✓ (o sin OBB),
     *  ámbar APK ✓ pero OBB ✗, morado solo descargado, gris resto. */
    private fun apkObbColor(holder: RecyclerView.ViewHolder, state: InstallState, obb: ObbUiState): Int {
        val ctx = holder.itemView.context
        val (apkMark, obbMark) = apkObbMarks(state, obb)
        val res = when {
            apkMark == "✓" && (obbMark == "✓" || obbMark == "—") -> R.color.gira_green
            apkMark == "✓" -> R.color.gira_yellow_dark
            state == InstallState.DOWNLOADED -> R.color.gira_purple
            else -> android.R.color.darker_gray
        }
        return ContextCompat.getColor(ctx, res)
    }

    private fun bindOrigin(badge: TextView, state: InstallState, origin: InstallOrigin) {
        val isInstalled = state == InstallState.INSTALLED ||
            state == InstallState.UPDATE_AVAILABLE ||
            state == InstallState.AHEAD_OF_LIST
        if (!isInstalled || origin == InstallOrigin.NONE) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        when (origin) {
            InstallOrigin.META_STORE -> {
                badge.setText(R.string.origin_badge_meta)
                badge.setBackgroundResource(R.drawable.bg_origin_meta)
            }
            InstallOrigin.GIRAQUEST -> {
                badge.setText(R.string.origin_badge_giraquest)
                badge.setBackgroundResource(R.drawable.bg_origin_giraquest)
            }
            InstallOrigin.OTHER -> {
                badge.setText(R.string.origin_badge_other)
                badge.setBackgroundResource(R.drawable.bg_origin_other)
            }
            InstallOrigin.NONE -> badge.visibility = View.GONE
        }
    }

    /** Doble toque = instalar; un toque = detalle; larga = favorito. */
    private fun attachGestures(holder: RecyclerView.ViewHolder, entry: RepoEntry) {
        var lastTap = 0L
        holder.itemView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTap < 350) {
                onDoubleTap(entry)
            } else {
                holder.itemView.postDelayed({
                    if (System.currentTimeMillis() - lastTap >= 350) onTap(entry)
                }, 360)
            }
            lastTap = now
        }
        holder.itemView.setOnLongClickListener {
            onLongPress(entry)
            true
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val (entry, state) = items[position]
        val favVisible = isFavorite(entry.packageName)
        // GetCachedImage del PC: primero la carátula en caché local
        // (<paquete>.jpg/.png de thumbnails/); si no, iconUrl o miniatura del tráiler.
        val cached = thumbnailCache.cachedFile(entry.packageName)
        val cover: Any? = cached ?: coverUrl(entry)
        // v0.1.27: meta = puesto de popularidad (#N, como el PC) +
        // tamaño (bajo demanda, "—" mientras tanto; nunca inventado).
        // Solo las filas visibles disparan la consulta.
        val ctx = holder.itemView.context
        val rankText = formatRank(popularityRank[entry.packageName])
        val sizeText = sizes[entry.packageName]
        if (sizeText == null) onSizeNeeded(entry)
        val shownSize = sizeText ?: entry.apkSize.takeIf { it > 0 }?.let { formatSize(it) }
        val metaText = buildString {
            if (rankText.isNotEmpty()) {
                append(ctx.getString(R.string.popularity_rank, rankText))
            }
            if (shownSize != null) {
                if (isNotEmpty()) append(" · ")
                append(shownSize)
            } else {
                if (isNotEmpty()) append(" · ")
                append(ctx.getString(R.string.size_unknown))
            }
        }
        val isNew = entry.packageName in newPackages
        // v0.1.36: el estado de la fila es "APK ✓/✗ · OBB ✓/✗".
        val obb = precomputedObbStates[entry.packageName]
            ?: precomputedDefaultObb
            ?: obbState(entry.packageName)
        val origin = precomputedOrigins[entry.packageName] ?: InstallOrigin.NONE
        when (holder) {
            is ListVH -> {
                holder.name.text = entry.displayName
                holder.sub.text = holder.itemView.context.getString(
                    R.string.item_sub_format,
                    entry.versionName,
                    entry.packageName,
                    formatSize(entry.apkSize)
                )
                holder.state.text = apkObbText(holder, state, obb)
                holder.state.setTextColor(apkObbColor(holder, state, obb))
                bindOrigin(holder.originBadge, state, origin)
                holder.meta.text = metaText
                holder.meta.visibility = View.VISIBLE
                holder.badge.visibility = if (isNew) View.VISIBLE else View.GONE
                holder.badge.text = ctx.getString(R.string.badge_new)
                holder.fav.visibility = if (favVisible) View.VISIBLE else View.GONE
                holder.fav.text = "★"
                holder.icon.load(cover) {
                    placeholder(R.drawable.ic_cover_placeholder)
                    error(R.drawable.ic_cover_placeholder)
                    crossfade(true)
                }
                holder.icon.contentDescription = entry.displayName
                bindTrash(holder.trash, entry, state)
                attachGestures(holder, entry)
            }
            is GalleryVH -> {
                holder.name.text = entry.displayName
                holder.state.text = apkObbText(holder, state, obb)
                holder.state.setTextColor(apkObbColor(holder, state, obb))
                bindOrigin(holder.originBadge, state, origin)
                holder.meta.text = metaText
                holder.meta.visibility = View.VISIBLE
                holder.badge.visibility = if (isNew) View.VISIBLE else View.GONE
                holder.badge.text = ctx.getString(R.string.badge_new)
                holder.fav.visibility = if (favVisible) View.VISIBLE else View.GONE
                holder.fav.text = "★"
                holder.cover.load(cover) {
                    placeholder(R.drawable.ic_cover_placeholder)
                    error(R.drawable.ic_cover_placeholder)
                    crossfade(true)
                }
                holder.cover.contentDescription = entry.displayName
                bindTrash(holder.trash, entry, state)
                attachGestures(holder, entry)
            }
        }
    }
}
