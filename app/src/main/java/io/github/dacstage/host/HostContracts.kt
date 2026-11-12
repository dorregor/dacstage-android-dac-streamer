package io.github.dacstage.host

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.HostState
import io.github.dacstage.model.PlayerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Núcleo del host: combina reproductor activo + DAC + carátula en un único [HostState] y ejecuta
 * comandos. Lo consumen el servidor de red (comandos de la TV) y la UI del host.
 */
interface HostController {
    val state: StateFlow<HostState>
    suspend fun execute(command: Command, origin: CommandOrigin): CommandResult
}

/** Ajustes mínimos del host (solo editables en el host). */
class HostSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("host_settings", Context.MODE_PRIVATE)

    private val _capDb = MutableStateFlow(prefs.getFloat(KEY_CAP_DB, Float.NaN).takeUnless { it.isNaN() })
    /** Tope absoluto en dB. null = usar el defecto (maxDb del DAC − [DEFAULT_CAP_BELOW_MAX_DB]). */
    val capDb: StateFlow<Float?> = _capDb.asStateFlow()

    private val _hostName = MutableStateFlow(prefs.getString(KEY_HOST_NAME, null) ?: Build.MODEL ?: "Host")
    val hostName: StateFlow<String> = _hostName.asStateFlow()

    private val _passiveSpecs = MutableStateFlow(prefs.getBoolean(KEY_PASSIVE_SPECS, false))
    /** Lectura pasiva de specs de HiBy: desactivada por defecto. */
    val passiveSpecsEnabled: StateFlow<Boolean> = _passiveSpecs.asStateFlow()

    private val _uappAdbEnabled = MutableStateFlow(prefs.getBoolean(KEY_UAPP_ADB, false))
    /** Lectura de la cola de UAPP vía depuración inalámbrica local: opcional y explícita. */
    val uappAdbEnabled: StateFlow<Boolean> = _uappAdbEnabled.asStateFlow()

    private val _lyricsEnabled = MutableStateFlow(prefs.getBoolean(KEY_LYRICS, false))
    /** Letras desde LRCLIB (envía título/artista/álbum/duración). Desactivado por defecto. */
    val lyricsEnabled: StateFlow<Boolean> = _lyricsEnabled.asStateFlow()

    private val _bluetoothRemote = MutableStateFlow(prefs.getBoolean(KEY_BT_REMOTE, true))
    /** Aceptar controles remotos por Bluetooth (sin pasar por el router). */
    val bluetoothRemote: StateFlow<Boolean> = _bluetoothRemote.asStateFlow()

    private val _musicBrainzEnabled = MutableStateFlow(prefs.getBoolean(KEY_MUSICBRAINZ, false))
    /** Año, sello y portada desde MusicBrainz + Cover Art Archive. Desactivado por defecto. */
    val musicBrainzEnabled: StateFlow<Boolean> = _musicBrainzEnabled.asStateFlow()

    private val _tvDisplay = MutableStateFlow(
        io.github.dacstage.model.TvDisplayPrefs(
            earthView = prefs.getBoolean(KEY_TV_PHOTOS, true),
            photoIntervalSec = prefs.getInt(KEY_TV_PHOTO_INTERVAL, io.github.dacstage.model.TvDisplayPrefs.DEFAULT_PHOTO_INTERVAL_SEC),
            idleSec = prefs.getInt(KEY_TV_IDLE, io.github.dacstage.model.TvDisplayPrefs.DEFAULT_IDLE_SEC),
            showArtwork = prefs.getBoolean(KEY_TV_SHOW_ART, true),
            showSongInfo = prefs.getBoolean(KEY_TV_SHOW_SONG, true),
            showPhotoInfo = prefs.getBoolean(KEY_TV_SHOW_PHOTO, true),
            lyricsShowArtwork = prefs.getBoolean(KEY_TV_LYRICS_SHOW_ART, true),
            lyricsShowSongInfo = prefs.getBoolean(KEY_TV_LYRICS_SHOW_SONG, true),
            lyricsShowPhotoInfo = prefs.getBoolean(KEY_TV_LYRICS_SHOW_PHOTO, true),
            showTech = prefs.getBoolean(KEY_TV_SHOW_TECH, true),
            lyricsShowTech = prefs.getBoolean(KEY_TV_LYRICS_SHOW_TECH, false),
            backdropOpacity = prefs.getFloat(KEY_TV_BACKDROP, io.github.dacstage.model.TvDisplayPrefs.DEFAULT_BACKDROP),
            textScale = prefs.getFloat(KEY_TV_TEXT_SCALE, 1f),
        ),
    )
    /** Reposo de la TV con fotos de Earth View (las descarga la TV de Google) y cada cuánto cambian. */
    val tvDisplay: StateFlow<io.github.dacstage.model.TvDisplayPrefs> = _tvDisplay.asStateFlow()

    init {
        android.util.Log.i("HostSettings", "reposo TV cargado: ${_tvDisplay.value}")
    }

    fun setTvPhotosEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TV_PHOTOS, enabled).apply()
        _tvDisplay.value = _tvDisplay.value.copy(earthView = enabled)
    }

    fun setTvPhotoIntervalSec(seconds: Int) {
        val s = seconds.coerceIn(30, 3600)
        prefs.edit().putInt(KEY_TV_PHOTO_INTERVAL, s).apply()
        _tvDisplay.value = _tvDisplay.value.copy(photoIntervalSec = s)
    }

    /** Elementos visibles del reposo sin letra ([lyrics] = false) o con letra. */
    fun setTvShow(
        lyrics: Boolean,
        artwork: Boolean? = null,
        songInfo: Boolean? = null,
        photoInfo: Boolean? = null,
        tech: Boolean? = null,
    ) {
        val cur = _tvDisplay.value
        val next = if (lyrics) cur.copy(
            lyricsShowArtwork = artwork ?: cur.lyricsShowArtwork,
            lyricsShowSongInfo = songInfo ?: cur.lyricsShowSongInfo,
            lyricsShowPhotoInfo = photoInfo ?: cur.lyricsShowPhotoInfo,
            lyricsShowTech = tech ?: cur.lyricsShowTech,
        ) else cur.copy(
            showArtwork = artwork ?: cur.showArtwork,
            showSongInfo = songInfo ?: cur.showSongInfo,
            showPhotoInfo = photoInfo ?: cur.showPhotoInfo,
            showTech = tech ?: cur.showTech,
        )
        prefs.edit()
            .putBoolean(KEY_TV_SHOW_ART, next.showArtwork)
            .putBoolean(KEY_TV_SHOW_SONG, next.showSongInfo)
            .putBoolean(KEY_TV_SHOW_PHOTO, next.showPhotoInfo)
            .putBoolean(KEY_TV_LYRICS_SHOW_ART, next.lyricsShowArtwork)
            .putBoolean(KEY_TV_LYRICS_SHOW_SONG, next.lyricsShowSongInfo)
            .putBoolean(KEY_TV_LYRICS_SHOW_PHOTO, next.lyricsShowPhotoInfo)
            .putBoolean(KEY_TV_SHOW_TECH, next.showTech)
            .putBoolean(KEY_TV_LYRICS_SHOW_TECH, next.lyricsShowTech)
            .apply()
        _tvDisplay.value = next
    }

    fun setTvTextScale(value: Float) {
        val v = value.coerceIn(io.github.dacstage.model.TvDisplayPrefs.MIN_TEXT_SCALE, io.github.dacstage.model.TvDisplayPrefs.MAX_TEXT_SCALE)
        if (v == _tvDisplay.value.textScale) return
        prefs.edit().putFloat(KEY_TV_TEXT_SCALE, v).apply()
        _tvDisplay.value = _tvDisplay.value.copy(textScale = v)
    }

    fun setTvBackdropOpacity(value: Float) {
        val v = value.coerceIn(0f, io.github.dacstage.model.TvDisplayPrefs.MAX_BACKDROP)
        if (v == _tvDisplay.value.backdropOpacity) return
        prefs.edit().putFloat(KEY_TV_BACKDROP, v).apply()
        _tvDisplay.value = _tvDisplay.value.copy(backdropOpacity = v)
    }

    fun setTvIdleSec(seconds: Int) {
        val s = seconds.coerceIn(30, 3600)
        prefs.edit().putInt(KEY_TV_IDLE, s).apply()
        _tvDisplay.value = _tvDisplay.value.copy(idleSec = s)
    }

    fun setBluetoothRemote(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BT_REMOTE, enabled).apply(); _bluetoothRemote.value = enabled
    }

    fun setLyricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRICS, enabled).apply(); _lyricsEnabled.value = enabled
    }

    fun setMusicBrainzEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MUSICBRAINZ, enabled).apply(); _musicBrainzEnabled.value = enabled
    }

    fun setCapDb(db: Float?) {
        prefs.edit().apply { if (db == null) remove(KEY_CAP_DB) else putFloat(KEY_CAP_DB, db) }.apply()
        _capDb.value = db
    }

    fun setHostName(name: String) {
        prefs.edit().putString(KEY_HOST_NAME, name).apply(); _hostName.value = name
    }

    fun setPassiveSpecsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PASSIVE_SPECS, enabled).apply(); _passiveSpecs.value = enabled
    }

    fun setUappAdbEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UAPP_ADB, enabled).apply(); _uappAdbEnabled.value = enabled
    }

    /** Consentimiento persistente para claimInterface(true) con música sonando, por DAC + reproductor. */
    fun hasForceConsent(dacKey: String, player: PlayerId): Boolean =
        prefs.getBoolean(consentKey(dacKey, player), false)

    fun setForceConsent(dacKey: String, player: PlayerId, granted: Boolean) {
        prefs.edit().apply {
            if (granted) putBoolean(consentKey(dacKey, player), true) else remove(consentKey(dacKey, player))
        }.apply()
    }

    private fun consentKey(dacKey: String, player: PlayerId) = "force_consent:$dacKey:${player.name}"

    companion object {
        const val DEFAULT_CAP_BELOW_MAX_DB = 10f
        private const val KEY_CAP_DB = "cap_db"
        private const val KEY_HOST_NAME = "host_name"
        private const val KEY_PASSIVE_SPECS = "passive_specs"
        private const val KEY_UAPP_ADB = "uapp_adb"
        private const val KEY_BT_REMOTE = "bt_remote"
        private const val KEY_LYRICS = "info_lyrics"
        private const val KEY_MUSICBRAINZ = "info_musicbrainz"
        private const val KEY_TV_PHOTOS = "tv_photos"
        private const val KEY_TV_PHOTO_INTERVAL = "tv_photo_interval"
        private const val KEY_TV_IDLE = "tv_idle"
        private const val KEY_TV_SHOW_ART = "tv_show_art"
        private const val KEY_TV_SHOW_SONG = "tv_show_song"
        private const val KEY_TV_SHOW_PHOTO = "tv_show_photo"
        private const val KEY_TV_LYRICS_SHOW_ART = "tv_lyrics_show_art"
        private const val KEY_TV_LYRICS_SHOW_SONG = "tv_lyrics_show_song"
        private const val KEY_TV_LYRICS_SHOW_PHOTO = "tv_lyrics_show_photo"
        private const val KEY_TV_SHOW_TECH = "tv_show_tech"
        private const val KEY_TV_LYRICS_SHOW_TECH = "tv_lyrics_show_tech"
        private const val KEY_TV_BACKDROP = "tv_backdrop"
        private const val KEY_TV_TEXT_SCALE = "tv_text_scale"
    }
}
