package io.github.dacstage.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class LineReaderTest {
    @Test
    fun readsLinesCrLfAndUtf8() {
        val r = BoundedLineReader(ByteArrayInputStream("uno\r\ndós ñ €\n\ntres".toByteArray()), 1024)
        assertEquals("uno", r.readLine())
        assertEquals("dós ñ €", r.readLine())
        assertEquals("", r.readLine())
        assertNull("línea sin \\n al final = EOF", r.readLine())
    }

    @Test(expected = LineTooLongException::class)
    fun rejectsLinesOverLimit() {
        val r = BoundedLineReader(ByteArrayInputStream(("a".repeat(70_000) + "\n").toByteArray()), 64 * 1024)
        r.readLine()
    }

    @Test
    fun longLinesAcrossBuffersUnderLimit() {
        val big = "x".repeat(100_000)
        val r = BoundedLineReader(ByteArrayInputStream("$big\nfin\n".toByteArray()), 200_000)
        assertEquals(big, r.readLine())
        assertEquals("fin", r.readLine())
    }
}
