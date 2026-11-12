package io.github.dacstage.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.File

/** Carátulas sintéticas para el modo demo (solo debug): JPEG 1000 px + miniatura 120 px en caché. */
internal object DemoArt {
    data class Files(val full: File, val thumb: File)

    private val palettes = listOf(
        intArrayOf(0xFF1B2A6B.toInt(), 0xFFE94F37.toInt(), 0xFFF6C85F.toInt()),
        intArrayOf(0xFF0B3D2E.toInt(), 0xFF2BB673.toInt(), 0xFFE8F7EE.toInt()),
        intArrayOf(0xFF3A0C3E.toInt(), 0xFFB24592.toInt(), 0xFFF15F79.toInt()),
        intArrayOf(0xFF102027.toInt(), 0xFF37474F.toInt(), 0xFF4FC3F7.toInt()),
        intArrayOf(0xFF4E2A04.toInt(), 0xFFD9822B.toInt(), 0xFFFFE0B2.toInt()),
        intArrayOf(0xFF0D0D0D.toInt(), 0xFF8E8E8E.toInt(), 0xFFF5F5F5.toInt()),
    )

    fun ensure(context: Context, index: Int): Files {
        val dir = File(context.cacheDir, "demo_art").apply { mkdirs() }
        val full = File(dir, "cover_$index.jpg")
        val thumb = File(dir, "cover_${index}_t.jpg")
        if (!full.exists() || !thumb.exists()) {
            val bmp = draw(index, 1000)
            full.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val small = Bitmap.createScaledBitmap(bmp, 120, 120, true)
            thumb.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            small.recycle()
        }
        return Files(full, thumb)
    }

    private fun draw(index: Int, size: Int): Bitmap {
        val p = palettes[index % palettes.size]
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, s, s, p[0], p[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, paint)
        paint.shader = RadialGradient(s * 0.68f, s * 0.36f, s * 0.42f, p[2], p[2] and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(s * 0.68f, s * 0.36f, s * 0.42f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.012f
        paint.color = 0x55FFFFFF
        for (i in 1..6) c.drawCircle(s * 0.3f, s * 0.72f, s * 0.05f * i * (1 + index % 3) / 2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xCCFFFFFF.toInt()
        c.drawRect(s * 0.08f, s * 0.08f, s * 0.08f + s * 0.18f, s * 0.08f + s * 0.018f, paint)
        return bmp
    }
}
