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
}
