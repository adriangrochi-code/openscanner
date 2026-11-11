package com.tisma.scanner.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextsTest {
    @Test fun singularOnlyForOne() {
        assertEquals("1 foto", count(1, "foto", "fotos"))
        assertEquals("0 fotos", count(0, "foto", "fotos"))
        assertEquals("2 fotogramas", count(2, "fotograma", "fotogramas"))
        assertEquals("1 descartada", count(1, "descartada", "descartadas"))
    }

    @Test fun theSendHintNamesTheButtonOfEachMode() {
        // En Vídeo 360 la cámara no tiene ☁ Enviar: el botón está en «Tu pieza».
        assertEquals(true, "Crear modelo final en Tisma Cloud" in sendHint(videoMode = true))
        assertEquals(false, "☁ Enviar" in sendHint(videoMode = true))
        assertEquals(true, "☁ Enviar" in sendHint(videoMode = false))
    }

    @Test fun thePublicationsCodeIsShown() {
        assertEquals("Código de la publicación: lm_0a1b2c3d4e5f", publishedCodeLine("lm_0a1b2c3d4e5f"))
    }
}
