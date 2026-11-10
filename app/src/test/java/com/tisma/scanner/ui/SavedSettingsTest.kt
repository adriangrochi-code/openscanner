package com.tisma.scanner.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lo que la persona eligió en ⚙ sigue elegido al volver a abrir la app (antes volvía todo a los de fábrica). */
@RunWith(RobolectricTestRunner::class)
class SavedSettingsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("prueba", Context.MODE_PRIVATE)

    @Test fun choicesSurviveAReopen() {
        val chosen = UiState().copy(videoMode = false, hdr = false, autoTrigger = true, deblurMotion = false,
            useDepthAi = false, jpegQuality = 80, partDepthCm = 7, torch = true)
        SavedSettings.save(prefs, chosen)
        val reopened = SavedSettings.load(prefs, UiState())
        assertEquals(SavedSettings.of(chosen), SavedSettings.of(reopened))
        assertEquals(false, reopened.videoMode)
        assertEquals(80, reopened.jpegQuality)
        assertEquals("la linterna no se enciende sola al abrir", false, reopened.torch)
    }

    @Test fun nothingSavedKeepsTheDefaults() {
        prefs.edit().clear().commit()
        assertEquals(SavedSettings.of(UiState()), SavedSettings.of(SavedSettings.load(prefs, UiState())))
    }
}
