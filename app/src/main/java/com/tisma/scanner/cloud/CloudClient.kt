package com.tisma.scanner.cloud

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Cliente de Tisma Cloud para los escaneos (API v1; contrato en el README de Tisma Cloud, «Escaneos 3D»).
 * Sin dependencias: HttpURLConnection. Sólo HTTPS: el token de la sesión viaja en cada petición.
 *
 * - Se entra con la cuenta de Tisma (`POST /v1/auth/login`): el token es el de la sesión de este teléfono.
 * - El escaneo se sube en trozos a `/v1/scans` (reanudable: 409 dice desde dónde seguir) y al completarlo
 *   se vuelve un trabajo de la cola (`kind` = "scan"), que se sigue con `GET /v1/jobs/{id}`.
 * - El resultado es un zip con `pieza.stl` (mm), `pieza_informe.json` y, si se hicieron, `pieza.ply`,
 *   `textura.zip` y `log.txt`.
 * - El plan de la cuenta tiene que incluir los escaneos: si no, el servidor contesta 402 con el texto.
 */
class CloudClient(baseUrl: String, private val token: String, private val retryBaseMs: Long = 2_000L) {

    private val base = normalize(baseUrl)

    init {
        require(token.isNotBlank()) { "Entrá con tu cuenta de Tisma Cloud (⚙ → Nube)" }
    }

    /** Un error del servidor, con su texto (`detail`) para mostrar. */
    class HttpError(val code: Int, val body: JSONObject?) : IOException(describe(code, body)) {
        companion object {
            fun describe(code: Int, body: JSONObject?): String {
                val detail = body?.optString("detail")?.takeIf { it.isNotBlank() }
                return when {
                    code == 401 -> "La sesión venció o se cerró: entrá de nuevo"
                    detail != null -> detail
                    else -> "HTTP $code"
                }
            }
        }
    }

    companion object {
        fun normalize(url: String): String {
            val base = url.trim().trimEnd('/')
            require(base.startsWith("https://")) { "El servidor debe usar https://" }
            return base
        }

        /** Entra con la cuenta. Devuelve (token, correo). */
        fun login(baseUrl: String, email: String, password: String, device: String): Pair<String, String> {
            val body = JSONObject().put("email", email.trim()).put("password", password).put("device", device)
            val (code, j) = call(normalize(baseUrl), "POST", "/v1/auth/login", null, body.toString().toByteArray())
            if (code != 200 || j == null) throw HttpError(code, j)
            return j.getString("token") to j.optString("email", email.trim())
        }

        internal fun call(base: String, method: String, path: String, token: String?, body: ByteArray? = null,
                          len: Int = body?.size ?: 0, contentType: String = "application/json",
                          timeoutMs: Int = 60_000): Pair<Int, JSONObject?> {
            val c = URL(base + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 20_000
                c.readTimeout = timeoutMs
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", contentType)
                    // Con largo fijo: Tisma Cloud rechaza las subidas sin Content-Length (411).
                    c.setFixedLengthStreamingMode(len)
                    c.outputStream.use { it.write(body, 0, len) }
                }
                val code = c.responseCode
                val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }
                val json = text?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                return code to json
            } finally {
                c.disconnect()
            }
        }
    }

    private fun request(method: String, path: String, body: ByteArray? = null, len: Int = body?.size ?: 0,
                        contentType: String = "application/json", timeoutMs: Int = 60_000) =
        call(base, method, path, token, body, len, contentType, timeoutMs)

    fun logout() {
        runCatching { request("POST", "/v1/auth/logout", "{}".toByteArray()) }
    }

    /** Empieza a mandar un escaneo. Devuelve (id de la subida, tamaño de trozo). */
    fun create(size: Long, name: String): Pair<String, Int> {
        val (code, j) = request("POST", "/v1/scans", JSONObject().put("size", size).put("name", name).toString().toByteArray())
        if (code != 201 || j == null) throw HttpError(code, j)
        return j.getString("id") to j.getInt("chunk_size")
    }

    /** Lo que llegó de una subida (`received`) y su trabajo cuando se completó (`job_id`). */
    fun scanStatus(id: String): JSONObject {
        val (code, j) = request("GET", "/v1/scans/$id")
        if (code != 200 || j == null) throw HttpError(code, j)
        return j
    }

    /**
     * Sube el archivo desde lo que el servidor ya tiene, en trozos, reintentando cortes de red
     * con esperas crecientes. Si el servidor responde 409, se continúa desde lo que dice tener.
     * Al terminar completa la subida y devuelve el id del trabajo.
     */
    fun upload(id: String, file: File, chunkSize: Int, onProgress: (Long, Long) -> Unit,
               isCancelled: () -> Boolean = { false }): String {
        val size = file.length()
        var offset = scanStatus(id).optLong("received", 0L)
        val buf = ByteArray(chunkSize)
        var failures = 0
        RandomAccessFile(file, "r").use { raf ->
            while (offset < size) {
                if (isCancelled()) throw IOException("cancelado")
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(chunkSize.toLong(), size - offset).toInt())
                try {
                    val (code, j) = request("PUT", "/v1/scans/$id/data?offset=$offset", buf, n,
                        "application/octet-stream", timeoutMs = 120_000)
                    when (code) {
                        200, 409 -> { offset = j?.optLong("received", offset) ?: offset; failures = 0 }
                        else -> throw HttpError(code, j)
                    }
                    onProgress(offset, size)
                } catch (e: HttpError) {
                    if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                    failures = backoff(failures)
                } catch (e: IOException) {
                    // Corte de red: esperar y reanudar desde lo que tenga el servidor.
                    failures = backoff(failures)
                    offset = runCatching { scanStatus(id).optLong("received", offset) }.getOrDefault(offset)
                }
            }
        }
        return complete(id)
    }

    /** Revisa el escaneo en el servidor y lo pone en la cola. Devuelve el id del trabajo. */
    fun complete(id: String): String {
        val (code, j) = request("POST", "/v1/scans/$id/complete", "{}".toByteArray(), timeoutMs = 300_000)
        if (code != 201 || j == null) throw HttpError(code, j)
        return j.getString("id")
    }

    /** El trabajo: `status` (queued, running, done, failed, cancelled), `progress`, `summary`, `error`. */
    fun job(jobId: String): JSONObject {
        val (code, j) = request("GET", "/v1/jobs/$jobId")
        if (code != 200 || j == null) throw HttpError(code, j)
        return j
    }

    /**
     * Sigue un trabajo hasta que termina (`done`, `failed` o `cancelled`) y lo devuelve. Un corte de red o un
     * error pasajero del servidor (5xx, 408, 429) no lo corta: se espera cada vez más y se vuelve a preguntar.
     * Sólo un error de la petición (otro 4xx, como 401) o muchos cortes seguidos terminan con una excepción.
     */
    fun waitForJob(jobId: String, pollMs: Long, onStatus: (JSONObject) -> Unit,
                   isCancelled: () -> Boolean = { false }): JSONObject {
        var failures = 0
        while (true) {
            if (isCancelled()) throw IOException("cancelado")
            try {
                val j = job(jobId)
                failures = 0
                onStatus(j)
                if (j.optString("status") in setOf("done", "failed", "cancelled")) return j
                Thread.sleep(pollMs)
            } catch (e: HttpError) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                failures = backoff(failures)
            } catch (e: IOException) {
                failures = backoff(failures)
            }
        }
    }

    private fun backoff(failures: Int): Int {
        if (failures >= 12) throw IOException("sin conexión con el servidor tras varios intentos")
        Thread.sleep(minOf(60_000L, retryBaseMs shl failures))
        return failures + 1
    }

    /**
     * Baja el resultado (un zip) y saca de él los archivos pedidos: nombre en el zip -> archivo de destino.
     * Devuelve los que estaban.
     */
    fun downloadResult(jobId: String, wanted: Map<String, File>): List<File> {
        val c = URL("$base/v1/jobs/$jobId/result").openConnection() as HttpURLConnection
        val got = mutableListOf<File>()
        try {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.connectTimeout = 20_000; c.readTimeout = 120_000
            if (c.responseCode != 200) {
                val text = c.errorStream?.bufferedReader()?.use { it.readText() }
                throw HttpError(c.responseCode, text?.let { runCatching { JSONObject(it) }.getOrNull() })
            }
            ZipInputStream(c.inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Sólo los nombres pedidos, sin carpetas: nada del zip elige dónde se escribe.
                    val dest = wanted[entry.name] ?: continue
                    val tmp = File(dest.parentFile, dest.name + ".part")
                    tmp.outputStream().use { zip.copyTo(it) }
                    if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                    got += dest
                }
            }
        } finally {
            c.disconnect()
        }
        return got
    }
}
