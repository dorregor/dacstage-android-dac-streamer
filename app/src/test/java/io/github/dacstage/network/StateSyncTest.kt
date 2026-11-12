package io.github.dacstage.network

import io.github.dacstage.model.DacState
import io.github.dacstage.model.HostState
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.TrackInfo
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateSyncTest {
    private val base = ProtocolCodecTest.sampleState()

    @Test
    fun diffContainsOnlyChangedTopLevelKeys() {
        val next = base.copy(status = PlaybackStatus.PAUSED, dac = base.dac.copy(volumeDb = -21f))
        val d = StateCodec.diff(StateCodec.encode(base), StateCodec.encode(next))
        assertEquals(setOf("status", "dac"), d.keys)
        assertEquals(next, StateCodec.decode(StateCodec.merge(StateCodec.encode(base), d)))
    }

    @Test
    fun unchangedStateGivesEmptyDiff() {
        assertTrue(StateCodec.diff(StateCodec.encode(base), StateCodec.encode(base.copy())).isEmpty())
    }

    @Test
    fun fieldsThatBecomeNullAreSentAsExplicitNull() {
        val next = base.copy(track = null, art = null, progress = null, jump = null, player = null)
        val d = StateCodec.diff(StateCodec.encode(base), StateCodec.encode(next))
        assertEquals(setOf("track", "art", "progress", "jump", "player"), d.keys)
        assertTrue(d.values.all { it == JsonNull })
        val merged = StateCodec.decode(StateCodec.merge(StateCodec.encode(base), d))
        assertEquals(next, merged)
        assertNull(merged.track)
    }

    @Test
    fun nullToValueAndNestedNullInsideObject() {
        val a = HostState(track = null, dac = DacState(volumeDb = -10f))
        val b = HostState(track = TrackInfo("x"), dac = DacState(volumeDb = null))
        val d = StateCodec.diff(StateCodec.encode(a), StateCodec.encode(b))
        assertEquals(setOf("track", "dac"), d.keys)
        assertEquals(b, StateCodec.decode(StateCodec.merge(StateCodec.encode(a), d)))
    }

    @Test
    fun assemblerAppliesPatchesAndDetectsGaps() {
        val asm = StateAssembler()
        val r0 = asm.onPatch(1, JsonObject(emptyMap()))
        assertTrue("parche antes del snapshot", r0.needsRefresh)

        val r1 = asm.onSnapshot(1, StateCodec.encode(base))
        assertEquals(base, r1.state)
        assertFalse(r1.needsRefresh)

        val s2 = base.copy(status = PlaybackStatus.PAUSED)
        val r2 = asm.onPatch(2, StateCodec.diff(StateCodec.encode(base), StateCodec.encode(s2)))
        assertEquals(s2, r2.state)
        assertFalse(r2.needsRefresh)

        val s3 = s2.copy(track = null)
        val r3 = asm.onPatch(4, StateCodec.diff(StateCodec.encode(s2), StateCodec.encode(s3)))
        assertEquals(s3, r3.state)
        assertTrue("hueco 2→4", r3.needsRefresh)

        val r4 = asm.onSnapshot(10, StateCodec.encode(base))
        assertEquals(base, r4.state)
        assertFalse(asm.onPatch(11, JsonObject(emptyMap())).needsRefresh)
    }

    @Test
    fun undecodableMergeAsksForRefresh() {
        val asm = StateAssembler()
        asm.onSnapshot(1, StateCodec.encode(base))
        val r = asm.onPatch(2, JsonObject(mapOf("status" to kotlinx.serialization.json.JsonPrimitive("BAILANDO"))))
        assertNull(r.state)
        assertTrue(r.needsRefresh)
    }
}
