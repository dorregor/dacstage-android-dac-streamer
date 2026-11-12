package io.github.dacstage.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorre el camino crítico de la TV: arranque en frío → foco en play/pausa → abrir cola (↓) →
 * recorrerla (→/←) → cerrarla (Back). Ejecutar en la TV real (ver README, "Baseline Profile").
 */
@RunWith(AndroidJUnit4::class)
class TvBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        val device = device
        device.waitForIdle()
        val keys = intArrayOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK,
        )
        for (k in keys) {
            device.pressKeyCode(k)
            device.waitForIdle()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private companion object {
        const val PACKAGE = "io.github.dacstage"
    }
}
