package io.github.dacstage.players.uapp

/**
 * Datos técnicos que UAPP deja en su log (UAPP.txt). Solo se extraen valores; el texto (que contiene
 * URLs con tokens de TIDAL) nunca se guarda, registra ni envía.
 */
data class UappLogInfo(
    /** Formato del stream abierto, por la extensión de su URL ("FLAC"). null si no es concluyente. */
    val codec: String?,
    /** Bytes por trama USB que UAPP preparó para el DAC ("Prepare: … frame size = 8"). */
    val usbFrameBytes: Int? = null,
    /** Frecuencia que UAPP fijó en el DAC en la última preparación ("USB audio sample rate = 96000"). */
    val usbSampleRateHz: Int? = null,
    /** UAPP intentó fijar esa frecuencia y el DAC la rechazó ("Couldn't set sample rate"): sigue en la anterior. */
    val sampleRateSetFailed: Boolean = false,
)

object UappLogParser {
    private val STREAM_URL = Regex("""HTTP seek \d+ for (https?://[^\s?]+)""")
    private val FRAME_SIZE = Regex("""Prepare: maxPacketSize = \d+, minPacketSize = \d+ bytes, frame size = (\d+)""")
    private val USB_RATE = Regex("""USB audio sample rate = (\d{4,6})""")
    private val RATE_FAILED = Regex("""Couldn't set sample rate: res = -?\d+, val = (\d{4,6})""")
    /** El aviso de fallo va justo antes de la línea de frecuencia de esa misma preparación. */
    private const val FAIL_WINDOW_CHARS = 400

    private val EXTENSIONS = mapOf(
        "flac" to "FLAC",
        "mp3" to "MP3",
        "wav" to "WAV",
        "aif" to "AIFF",
        "aiff" to "AIFF",
        "dsf" to "DSD",
        "dff" to "DSD",
        "ogg" to "OGG",
        "opus" to "OPUS",
        // mp4/m4a/ts no se traducen: el contenedor no dice el códec (puede ser AAC, FLAC o ALAC).
    )

    fun parse(text: String): UappLogInfo {
        // Las dos últimas aperturas de stream (la actual y, a veces, la siguiente precargada) deben
        // coincidir en formato; si no, no se afirma nada.
        val urls = STREAM_URL.findAll(text).map { it.groupValues[1] }.toList().takeLast(2)
        val codecs = urls.map { codecOf(it) }.distinct()
        val codec = codecs.singleOrNull()
        val frame = FRAME_SIZE.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..64 }
        val rateMatch = USB_RATE.findAll(text).lastOrNull()
        val rate = rateMatch?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8_000..1_536_000 }
        val failed = rateMatch != null && RATE_FAILED.findAll(text).any { f ->
            f.range.first < rateMatch.range.first &&
                rateMatch.range.first - f.range.first <= FAIL_WINDOW_CHARS &&
                f.groupValues[1] == rateMatch.groupValues[1]
        }
        return UappLogInfo(codec, frame, rate, failed)
    }

    internal fun codecOf(url: String): String? {
        val last = url.substringAfterLast('/')
        val ext = last.substringAfterLast('.', "").lowercase()
        return EXTENSIONS[ext]
    }
}
