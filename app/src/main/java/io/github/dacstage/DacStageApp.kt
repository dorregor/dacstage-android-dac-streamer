package io.github.dacstage

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.dacstage.ui.common.ArtImages

class DacStageApp : Application(), SingletonImageLoader.Factory {
    /** Coil: memoria ≤ 15 % del heap, caché en disco de carátulas, bitmaps HARDWARE. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ArtImages.newImageLoader(context)
}
