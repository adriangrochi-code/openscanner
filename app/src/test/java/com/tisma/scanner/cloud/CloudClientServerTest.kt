package com.tisma.scanner.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Prueba de extremo a extremo del protocolo app <-> servidor: el CloudClient real de la app
 * contra el tisma_server.py real, por HTTPS (certificado de prueba), con una subida cortada
 * a mitad que se reanuda. Se salta si en la máquina no hay python3/openssl.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudClientServerTest {

    private fun sh(vararg cmd: String, dir: File) {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "${cmd.joinToString(" ")}: $out" }
    }

    private fun available(vararg cmd: String) = runCatching { ProcessBuilder(*cmd).start().waitFor() == 0 }.getOrDefault(false)

    @Test fun uploadResumesAndJobCompletesOverHttps() {
        val serverPy = File("../server/tisma_server.py").canonicalFile
        assumeTrue("hace falta python3, openssl y el servidor", available("python3", "--version") && available("openssl", "version") && serverPy.exists())
        val dir = kotlin.io.path.createTempDirectory("tisma_e2e").toFile()
        val port = ServerSocket(0).use { it.localPort }
        sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "key.pem", "-out", "cert.pem",
            "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost", dir = dir)
        val key = "clave-e2e-" + System.nanoTime()
        File(dir, "cfg.json").writeText("""{"host":"127.0.0.1","port":$port,"api_keys":["$key"],"data_dir":"datos",
            "chunk_mb":1,"allow_without_colmap":true,"colmap":"no-existe",
            "tls_cert":"${File(dir, "cert.pem").path}","tls_key":"${File(dir, "key.pem").path}"}""")
        sh("python3", "-c", "import sys; sys.path.insert(0, '${serverPy.parent}'); from pathlib import Path; " +
            "import tisma_server; tisma_server.make_demo_scan(Path('${dir.path}'))", dir = dir)
        val zip = File(dir, "scan_demo.zip")

        // Confiar sólo en el certificado de prueba (como haría Android con el de tismaservidores.net).
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("e2e", CertificateFactory.getInstance("X.509").generateCertificate(File(dir, "cert.pem").inputStream()))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory)

        val server = ProcessBuilder("python3", serverPy.path, "--config", File(dir, "cfg.json").path)
            .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "server.log")).start()
        try {
            val client = CloudClient("https://localhost:$port", key)
            var up = false
            repeat(60) { if (!up) { up = runCatching { client.health() != null }.getOrDefault(false); if (!up) Thread.sleep(250) } }
            assertTrue("el servidor no arrancó: " + File(dir, "server.log").readText(), up)

            // Clave incorrecta: rechazada.
            val bad = runCatching { CloudClient("https://localhost:$port", "mala").create(10, "x") }.exceptionOrNull()
            assertEquals(401, (bad as CloudClient.HttpError).code)

            val (id, _) = client.create(zip.length(), "e2e")
            val chunk = 64 * 1024     // trozos pequeños para que el escaneo de prueba ocupe varios
            // Subida cortada tras el primer trozo (como si se fuera la cobertura)…
            var chunksSent = 0
            val cut = runCatching {
                client.upload(id, zip, chunk, onProgress = { _, _ -> chunksSent++ }, isCancelled = { chunksSent >= 1 })
            }.exceptionOrNull()
            assertTrue(cut is IOException)
            val partial = client.status(id).getLong("received")
            assertTrue("debe haber recibido algo pero no todo", partial in 1 until zip.length())
            // …y reanudada: sigue desde lo que el servidor ya tiene.
            client.upload(id, zip, chunk, onProgress = { _, _ -> })
            var state = ""
            repeat(240) {
                if (state != "done" && state != "error") { state = client.status(id).getString("state"); Thread.sleep(250) }
            }
            assertEquals("done", state)
            val log = File(dir, "log.txt")
            client.download(id, "log.txt", log)
            assertTrue(log.readText().contains("[hdr]"))
        } finally {
            server.destroy()
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
            dir.deleteRecursively()
        }
    }

    @Test fun refusesPlainHttp() {
        val e = runCatching { CloudClient("http://tismaservidores.net", "k") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
