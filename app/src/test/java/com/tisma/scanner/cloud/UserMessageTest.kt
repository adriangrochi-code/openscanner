package com.tisma.scanner.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Sin red, la persona veía el texto de Java en inglés («Unable to resolve host…») al entrar, en Imprimir o Publicar. */
@RunWith(RobolectricTestRunner::class)
class UserMessageTest {
    @Test fun networkFailuresInSpanish() {
        val offline = "Sin conexión con Tisma Cloud: revisa la red e inténtalo de nuevo"
        assertEquals(offline, CloudClient.userMessage(UnknownHostException("Unable to resolve host \"cloud.tisma\": No address associated with hostname")))
        assertEquals(offline, CloudClient.userMessage(ConnectException("failed to connect to cloud.tisma/10.0.0.1 (port 443)")))
        assertEquals("Tisma Cloud tarda en responder: inténtalo de nuevo en un rato", CloudClient.userMessage(SocketTimeoutException("timeout")))
        assertTrue("segura" in CloudClient.userMessage(javax.net.ssl.SSLHandshakeException("Chain validation failed")))
    }

    @Test fun ownMessagesPassAsTheyAre() {
        assertEquals("Correo o contraseña incorrectos",
            CloudClient.userMessage(CloudClient.HttpError(401, null, "Correo o contraseña incorrectos")))
        assertEquals("sin conexión con el servidor tras varios intentos",
            CloudClient.userMessage(IOException("sin conexión con el servidor tras varios intentos")))
        assertEquals("El servidor debe usar https://", CloudClient.userMessage(IllegalArgumentException("El servidor debe usar https://")))
    }
}
