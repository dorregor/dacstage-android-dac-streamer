package io.github.dacstage.dac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Uac2DescriptorParserTest {

    private fun d(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun withLength(vararg body: Int): ByteArray = d(body.size + 1, *body)

    private fun concat(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, b -> acc + b }

    // Descriptores estándar
    private val device = withLength(0x01, 0x00, 0x02, 0xEF, 0x02, 0x01, 0x40, 0x2A, 0x15, 0xDD, 0x85, 0x04, 0x01, 1, 2, 0, 1)
    private val config = withLength(0x02, 0x00, 0x00, 3, 1, 0, 0xC0, 0)
    private val iad = withLength(0x0B, 0, 2, 0x01, 0x00, 0x20, 0)
    private fun iface(num: Int, alt: Int, eps: Int, cls: Int, sub: Int, proto: Int) =
        withLength(0x04, num, alt, eps, cls, sub, proto, 0)

    // AudioControl class-specific (topología SMSL real: IT2 → FU10 → OT20, CS41, CX40)
    private val acHeader = withLength(0x24, 0x01, 0x00, 0x02, 0x0A, 0x40, 0x00, 0x00)
    private val clockSource41 = withLength(0x24, 0x0A, 41, 0x03, 0x07, 0x00, 0x00)
    private val clockSelector40 = withLength(0x24, 0x0B, 40, 1, 41, 0x03, 0x00)
    private val inputTerminal2 = withLength(0x24, 0x02, 2, 0x01, 0x01, 0x00, 40, 2, 0x03, 0, 0, 0, 0, 0x00, 0x00, 0)
    private val featureUnit10 = withLength(
        0x24, 0x06, 10, 2,
        0x0F, 0x00, 0x00, 0x00, // master: mute + volumen
        0x0F, 0x00, 0x00, 0x00, // L
        0x0F, 0x00, 0x00, 0x00, // R
        0x00,
    )
    private val outputTerminal20 = withLength(0x24, 0x03, 20, 0x02, 0x03, 0x00, 10, 40, 0x00, 0x00, 0)

    // AudioStreaming: subtipos 0x01/0x02 que NO deben confundirse con header / input terminal.
    private val asGeneral = withLength(0x24, 0x01, 2, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 2, 0x03, 0, 0, 0, 0)
    private val asFormat = withLength(0x24, 0x02, 0x01, 4, 32)
    private val endpointOut = withLength(0x05, 0x01, 0x05, 0x08, 0x03, 0x01)
    private val csEndpoint = withLength(0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)
    private val dfu = iface(2, 0, 0, 0xFE, 0x01, 0x01)

    private fun smsl(): ByteArray = concat(
        device, config, iad,
        iface(0, 0, 0, 0x01, 0x01, 0x20),
        acHeader, clockSource41, clockSelector40, inputTerminal2, featureUnit10, outputTerminal20,
        iface(1, 0, 0, 0x01, 0x02, 0x20),
        iface(1, 1, 2, 0x01, 0x02, 0x20), asGeneral, asFormat, endpointOut, csEndpoint,
        dfu,
    )

    @Test
    fun parsesSmslTopologyFromDescriptors() {
        val t = Uac2DescriptorParser.parse(smsl())!!
        assertEquals(0, t.audioControlInterface)
        assertEquals(10, t.featureUnitId)
        assertEquals(2, t.featureUnitChannels)
        assertTrue(t.hasMasterMute)
        assertEquals(2, t.inputTerminalId)
        assertEquals(20, t.outputTerminalId)
        assertEquals(40, t.clockEntityId)
        assertEquals(setOf(41), t.clockSources)
        assertEquals(mapOf(40 to listOf(41)), t.clockSelectors)
        // Selector de una sola entrada: no hace falta leer el pin.
        assertEquals(41, Uac2DescriptorParser.resolveClockSource(t) { error("no debe leer el pin") })
    }

    @Test
    fun ignoresClassSpecificDescriptorsOfStreamingInterface() {
        // Sin FU en AudioControl; una "FU" falsa dentro de AudioStreaming no debe usarse.
        val fakeFuInStreaming = withLength(0x24, 0x06, 99, 2, 0x0F, 0, 0, 0, 0x00)
        val fakeOtInStreaming = withLength(0x24, 0x03, 98, 0x02, 0x03, 0x00, 99, 40, 0x00, 0x00, 0)
        val raw = concat(
            device, config, iad,
            iface(0, 0, 0, 0x01, 0x01, 0x20), acHeader, clockSource41, inputTerminal2,
            iface(1, 1, 2, 0x01, 0x02, 0x20), fakeFuInStreaming, fakeOtInStreaming,
        )
        assertNull(Uac2DescriptorParser.parse(raw))
    }

    @Test
    fun prefersPlaybackOutputOverUsbStreamingOutput() {
        // Camino de grabación (IT 3 → FU 11 → OT 21 USB streaming) declarado antes que el de reproducción.
        val itMic = withLength(0x24, 0x02, 3, 0x01, 0x02, 0x00, 40, 2, 0x03, 0, 0, 0, 0, 0x00, 0x00, 0)
        val fuRec = withLength(0x24, 0x06, 11, 3, 0x0F, 0, 0, 0, 0x00)
        val otUsb = withLength(0x24, 0x03, 21, 0x01, 0x01, 0x00, 11, 40, 0x00, 0x00, 0)
        val raw = concat(
            device, config,
            iface(0, 0, 0, 0x01, 0x01, 0x20), acHeader, clockSource41, clockSelector40,
            itMic, fuRec, otUsb, inputTerminal2, featureUnit10, outputTerminal20,
        )
        val t = Uac2DescriptorParser.parse(raw)!!
        assertEquals(10, t.featureUnitId)
        assertEquals(20, t.outputTerminalId)
    }

    @Test
    fun skipsFeatureUnitWithoutMasterVolume() {
        val fuMuteOnly = withLength(0x24, 0x06, 10, 2, 0x03, 0, 0, 0, 0x0C, 0, 0, 0, 0x0C, 0, 0, 0, 0x00)
        val raw = concat(
            device, config,
            iface(0, 0, 0, 0x01, 0x01, 0x20), acHeader, clockSource41, inputTerminal2, fuMuteOnly, outputTerminal20,
        )
        assertNull(Uac2DescriptorParser.parse(raw))
    }

    @Test
    fun malformedOrEmptyInputReturnsNull() {
        assertNull(Uac2DescriptorParser.parse(null))
        assertNull(Uac2DescriptorParser.parse(ByteArray(0)))
        assertNull(Uac2DescriptorParser.parse(d(0x00, 0x04, 0x01)))
        // Truncado a mitad de la FU.
        assertNull(Uac2DescriptorParser.parse(smsl().copyOf(80)))
    }

    @Test
    fun clockSelectorWithSeveralInputsUsesPin() {
        val t = Uac2Topology(
            audioControlInterface = 0, featureUnitId = 10, featureUnitChannels = 2, hasMasterMute = true,
            inputTerminalId = 2, outputTerminalId = 20, clockEntityId = 40,
            clockSources = setOf(41, 42), clockSelectors = mapOf(40 to listOf(41, 42)), clockMultipliers = emptyMap(),
        )
        assertEquals(42, Uac2DescriptorParser.resolveClockSource(t) { 2 })
        assertNull(Uac2DescriptorParser.resolveClockSource(t) { null })
        assertNull(Uac2DescriptorParser.resolveClockSource(t) { 5 })
    }

    // --- parseControl (modelo completo) ---

    @Test
    fun parseControlKeepsSmslModel() {
        val t = Uac2DescriptorParser.parseControl(smsl())!!
        assertEquals(Uac2DescriptorParser.parse(smsl())!!, t)
        assertEquals(2, t.uacVersion)
        assertEquals(listOf(0), t.volumeChannels)
        assertEquals(listOf(0), t.muteChannels)
        assertTrue(t.volumeWritable)
        assertTrue(t.muteWritable)
    }

    @Test
    fun perChannelOnlyVolumeIsFound() {
        // Master solo mute; volumen solo en L y R.
        val fu = withLength(0x24, 0x06, 10, 2, 0x03, 0, 0, 0, 0x0C, 0, 0, 0, 0x0C, 0, 0, 0, 0x00)
        val raw = concat(
            device, config,
            iface(0, 0, 0, 0x01, 0x01, 0x20), acHeader, clockSource41, clockSelector40, inputTerminal2, fu, outputTerminal20,
        )
        assertNull(Uac2DescriptorParser.parse(raw)) // API antigua: solo master
        val t = Uac2DescriptorParser.parseControl(raw)!!
        assertEquals(10, t.featureUnitId)
        assertEquals(listOf(1, 2), t.volumeChannels)
        assertEquals(listOf(0), t.muteChannels)
        assertTrue(t.hasVolume)
        assertEquals(40, t.clockEntityId)
    }

    @Test
    fun readOnlyVolumeIsNotWritable() {
        // Master: mute 0b11, volumen 0b01 (solo lectura).
        val fu = withLength(0x24, 0x06, 10, 2, 0x07, 0, 0, 0, 0x00, 0, 0, 0, 0x00, 0, 0, 0, 0x00)
        val raw = concat(
            device, config,
            iface(0, 0, 0, 0x01, 0x01, 0x20), acHeader, clockSource41, inputTerminal2, fu, outputTerminal20,
        )
        val t = Uac2DescriptorParser.parseControl(raw)!!
        assertEquals(listOf(0), t.volumeChannels)
        assertFalse(t.volumeWritable)
        assertTrue(t.muteWritable)
    }

    @Test
    fun noFeatureUnitStillGivesControlTopology() {
        val otDirect = withLength(0x24, 0x03, 20, 0x02, 0x03, 0x00, 2, 40, 0x00, 0x00, 0)
        val raw = concat(
            device, config,
            iface(0, 0, 0, 0x01, 0x01, 0x20), acHeader, clockSource41, clockSelector40, inputTerminal2, otDirect,
        )
        assertNull(Uac2DescriptorParser.parse(raw))
        val t = Uac2DescriptorParser.parseControl(raw)!!
        assertNull(t.featureUnitId)
        assertFalse(t.hasVolume)
        assertFalse(t.hasWritableMute)
        assertEquals(40, t.clockEntityId)
        assertEquals(41, Uac2DescriptorParser.resolveClockSource(t) { error("selector de una entrada: no se lee") })
    }

    @Test
    fun parsesUac1FeatureUnit() {
        val header1 = withLength(0x24, 0x01, 0x00, 0x01, 0x28, 0x00, 1, 1)
        val it1 = withLength(0x24, 0x02, 1, 0x01, 0x01, 0, 2, 0x03, 0x00, 0, 0)
        val fu1 = withLength(0x24, 0x06, 7, 1, 1, 0x03, 0x00, 0x00, 0) // master mute+volumen, L/R nada
        val ot1 = withLength(0x24, 0x03, 3, 0x02, 0x03, 0, 7, 0)
        val raw = concat(device, config, iface(0, 0, 0, 0x01, 0x01, 0x00), header1, it1, fu1, ot1)
        assertNull(Uac2DescriptorParser.parse(raw)) // API antigua: solo UAC2
        val t = Uac2DescriptorParser.parseControl(raw)!!
        assertEquals(1, t.uacVersion)
        assertEquals(7, t.featureUnitId)
        assertEquals(2, t.featureUnitChannels)
        assertEquals(listOf(0), t.volumeChannels)
        assertEquals(listOf(0), t.muteChannels)
        assertTrue(t.volumeWritable)
        assertNull(t.clockEntityId)
    }

    @Test
    fun noAudioControlInterfaceIsNull() {
        assertNull(Uac2DescriptorParser.parseControl(concat(device, config, dfu)))
    }

    @Test
    fun requestFieldsMatchVerifiedValues() {
        assertEquals(0x0200, Uac2Requests.wValue(Uac2Requests.FU_VOLUME))
        assertEquals(0x0100, Uac2Requests.wValue(Uac2Requests.FU_MUTE))
        assertEquals(0x0A00, Uac2Requests.wIndex(10, 0))
        assertEquals(0x2900, Uac2Requests.wIndex(41, 0))
    }
}
