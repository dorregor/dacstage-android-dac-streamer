package io.github.dacstage.artwork

import io.github.dacstage.model.ArtRef
import io.github.dacstage.players.ArtCandidate

/**
 * Lado host de la cadena de carátulas (implementación: [HostArtworkResolver], agente de red/imagen).
 *
 * Orden de fuentes: URL de CDN publicada por el reproductor (probando tamaños en orden descendente)
 * → ART_URI de la sesión → bitmap embebido. Se queda con la de MAYOR resolución real medida, registra
 * en log "art <trackKey>: <w>x<h> <source>" y avisa si ninguna llega a 1000×1000. Nunca reescala para
 * aparentar más calidad. Bytes JPEG/WebP (calidad >= 90) guardados por SHA-256 para servirlos por HTTP.
 */
interface ArtworkResolver {
    suspend fun resolve(trackKey: String, candidates: List<ArtCandidate>): ArtRef?

    /** Bytes y mime de una carátula resuelta (para el servidor HTTP del host). */
    fun bytesFor(id: String): Pair<ByteArray, String>?
}
