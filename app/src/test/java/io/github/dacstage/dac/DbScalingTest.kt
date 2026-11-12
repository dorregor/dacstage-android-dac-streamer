package io.github.dacstage.dac

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DbScalingTest {

    private fun d(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun dbToRawLittleEndian() {
        assertArrayEquals(d(0x00, 0x00), DbScaling.encodeLe(DbScaling.dbToRaw(0f)))
        assertArrayEquals(d(0x00, 0xFF), DbScaling.encodeLe(DbScaling.dbToRaw(-1f)))
        assertArrayEquals(d(0x00, 0xF6), DbScaling.encodeLe(DbScaling.dbToRaw(-10f)))
        assertArrayEquals(d(0x00, 0x81), DbScaling.encodeLe(DbScaling.dbToRaw(-127f)))
        assertArrayEquals(d(0x00, 0x80), DbScaling.encodeLe(DbScaling.dbToRaw(Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun rawToDb() {
        assertEquals(0f, DbScaling.rawToDb(DbScaling.decodeLe(d(0x00, 0x00))), 0f)
        assertEquals(-1f, DbScaling.rawToDb(DbScaling.decodeLe(d(0x00, 0xFF))), 0f)
        assertEquals(-10f, DbScaling.rawToDb(DbScaling.decodeLe(d(0x00, 0xF6))), 0f)
        assertEquals(-127f, DbScaling.rawToDb(DbScaling.decodeLe(d(0x00, 0x81))), 0f)
        assertEquals(Float.NEGATIVE_INFINITY, DbScaling.rawToDb(DbScaling.decodeLe(d(0x00, 0x80))), 0f)
        assertEquals(-0.5f, DbScaling.rawToDb(DbScaling.decodeLe(d(0x80, 0xFF))), 0f)
    }

    @Test
    fun sampleRateU32() {
        assertEquals(96_000L, DbScaling.decodeU32Le(d(0x00, 0x77, 0x01, 0x00)))
    }

    @Test
    fun parsesSmslVolumeRange() {
        // 1 subrango: MIN −127 dB, MAX 0 dB, RES 1 dB.
        val bytes = d(0x01, 0x00, 0x00, 0x81, 0x00, 0x00, 0x00, 0x01)
        val r = DbScaling.parseVolumeRange(bytes, bytes.size)!!
        assertEquals(listOf(DbRange(-127f, 0f, 1f)), r)
        assertEquals(DbRange(-127f, 0f, 1f), DbScaling.aggregate(r))
    }

    @Test
    fun rangeWithSeveralSubrangesAggregates() {
        val bytes = d(0x02, 0x00, 0x00, 0x81, 0x00, 0xC4, 0x00, 0x01, 0x00, 0xC4, 0x00, 0x00, 0x80, 0x00)
        assertEquals(2, DbScaling.subRangeCount(bytes, bytes.size))
        val agg = DbScaling.aggregate(DbScaling.parseVolumeRange(bytes, bytes.size)!!)!!
        assertEquals(DbRange(-127f, 0f, 1f), agg)
    }

    @Test
    fun incompleteOrInvalidRangeIsNull() {
        val twoSubrangesButOneSent = d(0x02, 0x00, 0x00, 0x81, 0x00, 0x00, 0x00, 0x01)
        assertNull(DbScaling.parseVolumeRange(twoSubrangesButOneSent, 8))
        assertNull(DbScaling.parseVolumeRange(d(0x01), 1))
        assertNull(DbScaling.parseVolumeRange(d(0x00, 0x00), 2))
        // RES 0 → inválido.
        assertNull(DbScaling.parseVolumeRange(d(0x01, 0x00, 0x00, 0x81, 0x00, 0x00, 0x00, 0x00), 8))
    }
}
