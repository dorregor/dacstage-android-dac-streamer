package io.github.dacstage.dac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbStreamFormatTest {

    private fun b(vararg v: Int) = v.map { it.toByte() }

    /** Interfaz AudioStreaming UAC2 con alt 0 (sin formato) + dos alternativas PCM. */
    private fun descriptors(alt1: Pair<Int, Int>, alt2: Pair<Int, Int>): ByteArray {
        val out = ArrayList<Byte>()
        out += b(9, 0x02, 0, 0, 2, 1, 0, 0x80, 50) // configuración
        out += b(9, 0x04, 1, 0, 0, 1, 2, 0x20, 0) // AS if1 alt0
        for ((alt, fmt) in listOf(1 to alt1, 2 to alt2)) {
            out += b(9, 0x04, 1, alt, 2, 1, 2, 0x20, 0) // AS if1 altN
            out += b(16, 0x24, 0x01, 2, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0) // AS_GENERAL, 2 canales
            out += b(6, 0x24, 0x02, 1, fmt.first, fmt.second) // FORMAT_TYPE I: subslot, bits
            out += b(7, 0x05, 0x01, 0x05, 0x08, 0x03, 1) // endpoint
        }
        return out.toByteArray()
    }

    @Test fun leeLosFormatosDeStreaming() {
        val f = Uac2DescriptorParser.parseStreamFormats(descriptors(2 to 16, 4 to 24))
        assertEquals(2, f.size)
        assertEquals(UsbStreamFormat(1, 1, 2, 2, 16), f[0])
        assertEquals(UsbStreamFormat(1, 2, 2, 4, 24), f[1])
    }

    @Test fun tramaDe8BytesSon24BitUtiles() {
        val f = Uac2DescriptorParser.parseStreamFormats(descriptors(2 to 16, 4 to 24))
        assertEquals(24, UsbStreamFormat.bitsForFrame(f, 8))
        assertEquals(16, UsbStreamFormat.bitsForFrame(f, 4))
    }

    @Test fun ambiguoOSinCoincidenciaNoSeAdivina() {
        val f = listOf(UsbStreamFormat(1, 1, 2, 4, 24), UsbStreamFormat(1, 2, 2, 4, 32))
        assertNull(UsbStreamFormat.bitsForFrame(f, 8))
        assertNull(UsbStreamFormat.bitsForFrame(f, 6))
        assertNull(UsbStreamFormat.bitsForFrame(f, null))
    }
}
