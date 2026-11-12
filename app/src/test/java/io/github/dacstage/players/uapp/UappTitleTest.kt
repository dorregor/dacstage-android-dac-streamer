package io.github.dacstage.players.uapp

import org.junit.Assert.assertEquals
import org.junit.Test

class UappTitleTest {
    @Test fun stripsUappTrackNumber() {
        assertEquals("Holy, Holy", UappAdapter.stripTrackNumber("3. Holy, Holy"))
        assertEquals("Fracaso", UappAdapter.stripTrackNumber("12. Fracaso"))
        // Sin número delante, o si solo quedaría el número: tal cual.
        assertEquals("1984", UappAdapter.stripTrackNumber("1984"))
        assertEquals("3. ", UappAdapter.stripTrackNumber("3. "))
        assertEquals("No. 1 Party Anthem", UappAdapter.stripTrackNumber("No. 1 Party Anthem"))
    }
}
