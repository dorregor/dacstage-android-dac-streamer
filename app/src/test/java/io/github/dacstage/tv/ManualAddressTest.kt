package io.github.dacstage.tv

import io.github.dacstage.network.DEFAULT_PORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualAddressTest {
    @Test fun ipSinPuerto() = assertEquals("192.168.1.82" to DEFAULT_PORT, ManualAddress.parse(" 192.168.1.82 "))
    @Test fun ipConPuerto() = assertEquals("10.0.0.5" to 47822, ManualAddress.parse("10.0.0.5:47822"))
    @Test fun octetoFueraDeRango() = assertNull(ManualAddress.parse("192.168.1.256"))
    @Test fun puertoFueraDeRango() = assertNull(ManualAddress.parse("192.168.1.2:70000"))
    @Test fun incompleta() = assertNull(ManualAddress.parse("192.168.1"))
    @Test fun vacia() = assertNull(ManualAddress.parse(""))
}
