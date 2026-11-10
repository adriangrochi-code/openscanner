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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Prueba de extremo a extremo del protocolo app <-> Tisma Cloud: el CloudClient real de la app contra
 * tools/fake_tisma_cloud.py (que contesta como la API de escaneos de Tisma Cloud), por HTTPS con un
 * certificado de prueba: entrar, una subida cortada a mitad que se reanuda, el trabajo en la cola y el
 * zip del resultado. Se salta si en la máquina no hay python3/openssl.
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

    private fun demoScan(dest: File) {
        ZipOutputStream(dest.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("scan_demo/session.json")); z.write("{}".toByteArray()); z.closeEntry()
            // Bytes al azar: no se comprimen, así el zip ocupa varios trozos de 64 KB.
            val random = java.util.Random(7)
            for (i in 0 until 4) {
                z.putNextEntry(ZipEntry("scan_demo/images/0/$i.jpg"))
                z.write(ByteArray(100_000).also { random.nextBytes(it) })
                z.closeEntry()
            }
        }
    }

    @Test fun signInUploadResumesAndResultArrivesOverHttps() {
        val fake = File("../tools/fake_tisma_cloud.py").canonicalFile
        assumeTrue("hace falta python3, openssl y el servidor de prueba", available("python3", "--version") && available("openssl", "version") && fake.exists())
        val dir = kotlin.io.path.createTempDirectory("tisma_e2e").toFile()
        val port = ServerSocket(0).use { it.localPort }
        sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "key.pem", "-out", "cert.pem",
            "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost", dir = dir)
        val zip = File(dir, "scan_demo.zip").also { demoScan(it) }
        assertTrue("el escaneo de prueba tiene que ocupar varios trozos", zip.length() > 3 * 64 * 1024)

        // Confiar sólo en el certificado de prueba (como haría Android con el de tismaservidores.net).
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("e2e", CertificateFactory.getInstance("X.509").generateCertificate(File(dir, "cert.pem").inputStream()))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory)

        val chunk = 64 * 1024     // trozos pequeños para que el escaneo de prueba ocupe varios
        // Las 3 primeras consultas del trabajo fallan como una red mala: corte, 503, corte.
        val server = ProcessBuilder("python3", fake.path, "--port", "$port", "--cert", File(dir, "cert.pem").path,
            "--key", File(dir, "key.pem").path, "--chunk", "$chunk", "--flaky-jobs", "3")
            .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "server.log")).start()
        try {
            val base = "https://localhost:$port"
            var login: Pair<String, String>? = null
            repeat(60) {
                if (login == null) {
                    login = runCatching { CloudClient.login(base, "e2e@tisma.test", "clave-e2e", "prueba") }.getOrNull()
                    if (login == null) Thread.sleep(250)
                }
            }
            assertTrue("el servidor no arrancó: " + File(dir, "server.log").readText(), login != null)
            assertEquals("e2e@tisma.test", login!!.second)

            // Contraseña o token incorrectos: rechazados, con el texto para la persona.
            val wrong = runCatching { CloudClient.login(base, "e2e@tisma.test", "otra", "prueba") }.exceptionOrNull()
            assertEquals(401, (wrong as CloudClient.HttpError).code)
            val bad = runCatching { CloudClient(base, "tu_malo").create(10, "x") }.exceptionOrNull()
            assertEquals(401, (bad as CloudClient.HttpError).code)
            assertTrue(bad.message!!.contains("entrá de nuevo"))

            val client = CloudClient(base, login!!.first, retryBaseMs = 50)
            val (id, size) = client.create(zip.length(), "e2e.zip")
            assertEquals(chunk, size)
            // Subida cortada tras el primer trozo (como si se fuera la cobertura)…
            var chunksSent = 0
            val cut = runCatching {
                client.upload(id, zip, chunk, onProgress = { _, _ -> chunksSent++ }, isCancelled = { chunksSent >= 1 })
            }.exceptionOrNull()
            assertTrue(cut is IOException)
            val partial = client.scanStatus(id).getLong("received")
            assertTrue("debe haber recibido algo pero no todo", partial in 1 until zip.length())
            // …y reanudada: sigue desde lo que el servidor ya tiene, y queda en la cola.
            val jobId = client.upload(id, zip, chunk, onProgress = { _, _ -> })
            assertEquals(jobId, client.scanStatus(id).getString("job_id"))
            // El seguimiento del trabajo aguanta los cortes y llega al final pasando por la cola.
            val seen = mutableListOf<String>()
            val done = client.waitForJob(jobId, pollMs = 10, onStatus = { seen += it.getString("status") })
            assertEquals("done", done.getString("status"))
            assertEquals(listOf("queued", "running", "done"), seen.distinct())
            val stl = File(dir, "pieza.stl")
            val report = File(dir, "informe.json")
            val got = client.downloadResult(jobId, mapOf("pieza.stl" to stl, "pieza_informe.json" to report))
            assertEquals(2, got.size)
            assertTrue(stl.readText().startsWith("solid"))
            assertTrue(report.readText().contains("dimensions_mm_xyz"))
            // Nada más del zip se escribe (log.txt no se pidió).
            assertTrue(!File(dir, "log.txt").exists())

            // «Imprimir»: la pieza del trabajo queda en la cuenta, sin subir nada; repetirlo da el mismo ítem.
            val item = client.keepFile(jobId, "Caja de prueba")
            assertTrue(item.getString("id").startsWith("af_"))
            assertEquals(item.getString("id"), client.keepFile(jobId).getString("id"))
            // «Publicar»: sin la clave de par registrada, 409; con ella, la firma del manifiesto vale y sale lm_….
            val key = LibraryPublish.PeerKey.create()
            val body = LibraryPublish.body(item, key, "Caja de prueba", "Tester", "CC-BY-4.0", "40 × 30 × 20 mm", ByteArray(100))
            assertEquals(409, (runCatching { client.publishModel(body) }.exceptionOrNull() as CloudClient.HttpError).code)
            client.registerPeerKey(key.publicB64)
            assertTrue(client.publishModel(body).getString("id").startsWith("lm_"))
            // Firmado con otra clave (o el manifiesto tocado): 422.
            val other = LibraryPublish.body(item, LibraryPublish.PeerKey.create(), "Caja", "Tester", "MIT", "", null)
            assertEquals(422, (runCatching { client.publishModel(other) }.exceptionOrNull() as CloudClient.HttpError).code)

            // El envío entero (CloudSend, lo que corre en CloudSendWorker): cortado tras el primer trozo…
            val kept = mutableMapOf<String, String>()
            val store = object : CloudSend.Store {
                override fun get(key: String) = kept[key]
                override fun put(key: String, value: String?) { if (value == null) kept.remove(key) else kept[key] = value }
            }
            var pieces = 0
            val cutSend = runCatching {
                CloudSend(client, store, pollMs = 10).run(zip, onStatus = { t, _ -> if (t.startsWith("Subiendo")) pieces++ },
                    isCancelled = { pieces >= 1 })
            }.exceptionOrNull()
            assertTrue(cutSend is IOException)
            val uploadId = kept.values.single()
            val before = client.scanStatus(uploadId).getLong("received")
            assertTrue(before in 1 until zip.length())
            // …y retomado: sigue la misma subida (no empieza otra), y baja la pieza con sus medidas.
            val texts = mutableListOf<String>()
            val result = CloudSend(client, store, pollMs = 10).run(zip, onStatus = { t, _ -> texts += t })
            assertEquals(zip.length(), client.scanStatus(uploadId).getLong("received"))
            assertTrue(result.stl.readText().startsWith("solid"))
            assertTrue(result.summary, result.summary.startsWith("40") && result.summary.endsWith("cerrada"))
            assertTrue(texts.any { it.startsWith("Procesando en la nube") })
            assertTrue("nada pendiente tras terminar", kept.isEmpty())

            // Tisma Cloud sin lugar en disco (507): la subida para enseguida (sin minutos de reintentos cortos),
            // queda guardada, y la vez siguiente sigue desde lo recibido.
            val zip2 = File(dir, "scan_lleno.zip").also { zip.copyTo(it) }
            var n2 = 0
            runCatching { CloudSend(client, store, 10).run(zip2, onStatus = { t, _ -> if (t.startsWith("Subiendo")) n2++ },
                isCancelled = { n2 >= 1 }) }
            val id2 = kept.values.single()
            val got2 = client.scanStatus(id2).getLong("received")
            sh("python3", "-c", "import json,ssl,urllib.request;ctx=ssl.create_default_context(cafile='cert.pem');" +
                "urllib.request.urlopen(urllib.request.Request('$base/test/full',data=json.dumps({'n':1}).encode()),context=ctx)",
                dir = dir)
            val t0 = System.currentTimeMillis()
            val full = runCatching { CloudSend(client, store, 10).run(zip2, onStatus = { _, _ -> }) }.exceptionOrNull()
            assertEquals(CloudClient.SERVER_FULL, (full as CloudClient.HttpError).code)
            assertTrue("para enseguida", System.currentTimeMillis() - t0 < 5_000)
            assertTrue(full.message!!.contains("no tiene lugar"))
            assertEquals("lo subido se conserva", got2, client.scanStatus(id2).getLong("received"))
            assertEquals(id2, kept.values.single())
            val result2 = CloudSend(client, store, 10).run(zip2, onStatus = { _, _ -> })
            assertEquals(zip2.length(), client.scanStatus(id2).getLong("received"))
            assertTrue(result2.stl.exists() && kept.isEmpty())
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
