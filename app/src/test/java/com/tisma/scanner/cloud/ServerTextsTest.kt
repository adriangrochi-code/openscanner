package com.tisma.scanner.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Tisma Cloud contesta en inglés (`detail`) a todos sus clientes: la app lo muestra en español, de tú. */
@RunWith(RobolectricTestRunner::class)  // org.json es el de Android
class ServerTextsTest {
    private fun text(code: Int, detail: String?) =
        CloudClient.HttpError(code, detail?.let { JSONObject().put("detail", it) }).message

    @Test fun knownTextsInSpanish() {
        assertEquals("La cuenta está deshabilitada", text(401, "The account is disabled"))
        assertEquals("Confirma tu correo con el código que te mandamos", text(403, "E-mail not verified"))
        assertEquals("Demasiados intentos: espera un minuto", text(429, "Too many login attempts, wait"))
        assertEquals("Ya se están mandando tres escaneos: termina o borra uno", text(429, "Three scans are being sent already"))
        assertEquals("Actualiza Tisma Scanner: este escaneo trae la ubicación del teléfono",
            text(422, "The scan carries the absolute position of the phone (gps.csv)"))
        assertEquals("Ya publicaste 20 modelos hoy", text(429, "Too many publications today"))
        assertEquals("El nombre tiene caracteres no permitidos", text(422, "name has control or direction characters"))
        assertEquals("El texto es demasiado largo", text(422, "description is at most 500 characters"))
    }

    @Test fun numbersAreKept() {
        assertEquals("El escaneo pasa de 12 GB", text(413, "The scan is larger than 12 GB"))
        assertEquals("Tus archivos en Tisma Cloud pasan de 2.5 GB: borra alguno", text(413, "The files of the account are over 2.5 GB"))
    }

    @Test fun theSameStartWithAnotherCodeIsNotTranslatedAsIt() {
        // 401 sin texto conocido: la sesión venció (en todo menos al entrar).
        assertEquals("La sesión venció o se cerró: entra de nuevo", text(401, "Token expired"))
        // Un texto que no está en la lista se muestra tal cual, mejor que nada.
        assertEquals("Something new", text(400, "Something new"))
        assertEquals("HTTP 500", text(500, null))
    }

    @Test fun theTextsAreInTu() {
        val all = listOf(401 to "Wrong e-mail or password", 429 to "Too many wrong passwords", 409 to "An account with this e-mail exists",
            503 to "The e-mail could not be sent", 409 to "The model is being changed at the same time").map { (c, d) -> text(c, d)!! }
        val voseo = Regex("(?<!\\p{L})(esperá|elegí|entrá|probá|terminá|borrá|actualizá|confirmá)(?!\\p{L})")
        all.forEach { assertEquals(it, null, voseo.find(it)?.value) }
    }
}
