package io.github.dacstage.players.uapp

/**
 * PlayQueue.xml ANONIMIZADO con la estructura real que escribe UAPP (raíz PlayQueue, Queue/Item,
 * CurrentTrack, Misc). Títulos, IDs y URLs son inventados para la prueba.
 */
object UappFixtures {
    const val XML = """<?xml version="1.0" encoding="UTF-16"?>
<PlayQueue>
<Queue>
<Item Model="9" Title="Canción Uno" Artist="Artista A" AlbumArtist="Artista A" Album="Álbum &amp; Prueba" Genre="" TrackNr="1" FileName="" ArtURL="https://resources.tidal.com/images/aaaa1111/bbbb/cccc/dddd/750x750.jpg" ID="100000001" OffsetCDFramesInFile="0" DurationCDFrames="0" Duration="245.0" Seekable="true" SwitchToNextSongOnFailure="true" DecodeByAVCodec="false" DelayedAVCodecInit="false" Resolution="24" SampleRate="96000" ArtistID="1" AlbumID="2000001" EmbeddedAlbumArt="false" HasTrackRPG="false" ReplayTrackGain="0.0" HasAlbumRPG="false" ReplayAlbumGain="0.0" ExternalID=""/>
<Item Model="9" Title="Tema &quot;Dos&quot; (Remastered)" Artist="Artista B" Album="Otro Álbum" TrackNr="2" ArtURL="https://resources.tidal.com/images/eeee2222/ffff/0000/1111/750x750.jpg" ID="100000002" Duration="180.5" Resolution="16" SampleRate="44100" AlbumID="2000002"/>
<Item Model="9" Title="Pista &#x1F3B5; Tres" Artist="Artista C" Album="" TrackNr="0" ArtURL="" ID="100000003" Duration="0.0" Resolution="0" SampleRate="0"/>
</Queue>
<CurrentTrack CurrentItem="1" CurrentPos="12345"/>
<Misc ShuffleMode="0" RepeatMode="2"/>
</PlayQueue>
"""

    /** Igual que UAPP: OutputStreamWriter(..., "UTF-16") → UTF-16BE con BOM FE FF. */
    fun utf16WithBom(text: String = XML): ByteArray = text.toByteArray(Charsets.UTF_16)
}
