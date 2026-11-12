package io.github.dacstage.ui.common

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/** Tamaños de decodificación (px). La TV pinta la carátula a ~378 dp × 2,0 ≈ 756 px. */
object ArtSizes {
    const val THUMB_PX = 128
    const val PALETTE_PX = 64
}

object ArtImages {
    /** ImageLoader de la app: memoria ≤ 15 % del heap, disco para carátulas y miniaturas de la cola. */
    fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("art_cache").toOkioPath())
                .maxSizeBytes(48L * 1024 * 1024)
                .build()
        }
        .allowHardware(true)
        .build()

    /** Carátula principal: clave de memoria/disco = ArtRef.id; nunca se escala hacia arriba (INEXACT). */
    fun art(context: Context, m: ArtModel, sizePx: Int): ImageRequest = ImageRequest.Builder(context)
        .data(m.data)
        .memoryCacheKey(m.id)
        .diskCacheKey(m.id)
        .size(sizePx)
        .precision(Precision.INEXACT)
        .allowHardware(true)
        .build()

    /** Miniatura de cola (~128 px). */
    fun thumb(context: Context, url: String, sizePx: Int = ArtSizes.THUMB_PX): ImageRequest = ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey("$url@$sizePx")
        .diskCacheKey(url)
        .size(sizePx)
        .precision(Precision.INEXACT)
        .allowHardware(true)
        .build()

    /** Copia de software de ~64 px solo para la paleta (sale de la caché de disco tras la principal). */
    private fun paletteRequest(context: Context, m: ArtModel): ImageRequest = ImageRequest.Builder(context)
        .data(m.data)
        .diskCacheKey(m.id)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .size(ArtSizes.PALETTE_PX)
        .precision(Precision.INEXACT)
        .allowHardware(false)
        .build()

    private val paletteCache = LruCache<String, ArtColors>(48)

    fun cachedColors(id: String): ArtColors? = paletteCache.get(id)

    /** Paleta fuera del hilo principal, cacheada por ArtRef.id. */
    suspend fun colorsFor(context: Context, loader: ImageLoader, m: ArtModel): ArtColors? {
        paletteCache.get(m.id)?.let { return it }
        val r = loader.execute(paletteRequest(context, m)) as? SuccessResult ?: return null
        val colors = withContext(Dispatchers.Default) {
            val bmp = r.image.toBitmap()
            val p = Palette.from(bmp).maximumColorCount(16).generate()
            ArtColors.derive(
                ArtSwatches(
                    vibrant = p.vibrantSwatch?.rgb,
                    darkVibrant = p.darkVibrantSwatch?.rgb,
                    muted = p.mutedSwatch?.rgb,
                    darkMuted = p.darkMutedSwatch?.rgb,
                    dominant = p.dominantSwatch?.rgb,
                ),
            )
        }
        paletteCache.put(m.id, colors)
        return colors
    }
}

/** Imagen ya decodificada; [preview] = miniatura de la cola mientras llega la alta. */
@Immutable
class ArtFrame(val id: String, val bitmap: ImageBitmap, val preview: Boolean)

@Stable
class ArtworkState internal constructor(
    internal val frameState: MutableState<ArtFrame?>,
    internal val colorsState: MutableState<ArtColors>,
) {
    val frame: ArtFrame? get() = frameState.value
    val colors: ArtColors get() = colorsState.value
}

/**
 * Carga progresiva: miniatura de la cola (si existe) → carátula alta. Mantiene la anterior hasta que la
 * nueva está decodificada (sin parpadeos) y después calcula la paleta.
 */
@Composable
fun rememberArtwork(model: ArtModel?, thumbUrl: String?, sizePx: Int): ArtworkState {
    val context = LocalPlatformContext.current
    val state = remember {
        ArtworkState(mutableStateOf(null), mutableStateOf(model?.let { ArtImages.cachedColors(it.id) } ?: ArtColors.Default))
    }
    LaunchedEffect(model?.id, model?.data, sizePx) {
        if (model == null) {
            state.frameState.value = null
            state.colorsState.value = ArtColors.Default
            return@LaunchedEffect
        }
        val loader = SingletonImageLoader.get(context)
        val current = state.frameState.value
        var loaded = current != null && current.id == model.id && !current.preview
        if (!loaded) {
            coroutineScope {
                val hq = async { loader.execute(ArtImages.art(context, model, sizePx)) }
                val thumbJob = thumbUrl?.let { url ->
                    launch {
                        val t = loader.execute(ArtImages.thumb(context, url)) as? SuccessResult ?: return@launch
                        if (!hq.isCompleted) state.frameState.value = ArtFrame(model.id, t.image.toBitmap().asImageBitmap(), true)
                    }
                }
                val r = hq.await()
                thumbJob?.cancel()
                if (r is SuccessResult) {
                    state.frameState.value = ArtFrame(model.id, r.image.toBitmap().asImageBitmap(), false)
                    loaded = true
                } else if (state.frameState.value?.id != model.id) {
                    state.frameState.value = null
                }
            }
        }
        if (loaded) ArtImages.colorsFor(context, loader, model)?.let { state.colorsState.value = it }
    }
    return state
}
