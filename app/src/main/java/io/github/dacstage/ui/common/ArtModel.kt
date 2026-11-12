package io.github.dacstage.ui.common

import androidx.compose.runtime.Immutable
import io.github.dacstage.model.ArtRef

/** Carátula lista para Coil: [id] (= ArtRef.id) es la clave de caché; [data] lo que carga Coil. */
@Immutable
data class ArtModel(val id: String, val data: Any, val widthPx: Int, val heightPx: Int)

object ArtUrls {
    /**
     * TV: URL pública si existe; si no, servidor del host: [artBaseUrl] + [ArtRef.hostPath]. Si la base trae
     * query (token), la ruta se inserta antes de la query: "http://h:47821?t=x" + "/art/1" → "http://h:47821/art/1?t=x".
     */
    fun forTv(art: ArtRef, artBaseUrl: String?): String? {
        art.url?.takeIf { it.isNotBlank() }?.let { return it }
        val path = art.hostPath?.takeIf { it.isNotBlank() } ?: return null
        val base = artBaseUrl?.takeIf { it.isNotBlank() } ?: return null
        val q = base.indexOf('?')
        val root = (if (q >= 0) base.substring(0, q) else base).trimEnd('/')
        val query = if (q >= 0) base.substring(q) else ""
        val p = if (path.startsWith('/')) path else "/$path"
        return root + p + query
    }

    fun tvModel(art: ArtRef?, artBaseUrl: String?): ArtModel? {
        art ?: return null
        val url = forTv(art, artBaseUrl) ?: return null
        return ArtModel(art.id, url, art.widthPx, art.heightPx)
    }
}
