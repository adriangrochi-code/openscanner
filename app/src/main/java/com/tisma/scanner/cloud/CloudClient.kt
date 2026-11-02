package com.tisma.scanner.cloud

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente de Tisma Server (ver server/README.md). Sin dependencias: HttpURLConnection.
 * Sólo HTTPS: la clave de API viaja en cada petición.
 */
class CloudClient(baseUrl: String, private val apiKey: String) {

    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("https://")) { "El servidor debe usar https://" }
        require(apiKey.isNotBlank()) { "Falta la clave de API (⚙ → Nube)" }
    }

    class HttpError(val code: Int, val body: JSONObject?) :
        IOException("HTTP $code: ${body?.optString("error") ?: ""}")

    private fun request(method: String, path: String, body: ByteArray? = null, len: Int = body?.size ?: 0,
                        contentType: String = "application/json", timeoutMs: Int = 60_000): Pair<Int, JSONObject?> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
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

    fun health(): JSONObject? = request("GET", "/api/health").second

    /** Crea la subida. Devuelve (id, tamaño de trozo). */
    fun create(size: Long, name: String): Pair<String, Int> {
        val (code, j) = request("POST", "/api/scans", JSONObject().put("size", size).put("name", name).toString().toByteArray())
        if (code != 201 || j == null) throw HttpError(code, j)
        return j.getString("id") to j.getInt("chunk_size")
    }

    fun status(id: String): JSONObject {
        val (code, j) = request("GET", "/api/scans/$id")
        if (code != 200 || j == null) throw HttpError(code, j)
        return j
    }

    /**
     * Sube el archivo desde lo que el servidor ya tiene, en trozos, reintentando cortes de red
     * con esperas crecientes. Si el servidor responde 409, se continúa desde lo que dice tener.
     */
    fun upload(id: String, file: File, chunkSize: Int, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean = { false }) {
        val size = file.length()
        var offset = status(id).optLong("received", 0L)
        val buf = ByteArray(chunkSize)
        var failures = 0
        RandomAccessFile(file, "r").use { raf ->
            while (offset < size) {
                if (isCancelled()) throw IOException("cancelado")
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(chunkSize.toLong(), size - offset).toInt())
                try {
                    val (code, j) = request("PUT", "/api/scans/$id/data?offset=$offset", buf, n,
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
                    offset = runCatching { status(id).optLong("received", offset) }.getOrDefault(offset)
                }
            }
        }
        val (code, j) = request("POST", "/api/scans/$id/complete", "{}".toByteArray())
        if (code != 202) throw HttpError(code, j)
    }

    private fun backoff(failures: Int): Int {
        if (failures >= 12) throw IOException("sin conexión con el servidor tras varios intentos")
        Thread.sleep(minOf(60_000L, 2_000L shl failures))
        return failures + 1
    }

    fun download(id: String, name: String, dest: File) {
        val c = URL("$base/api/scans/$id/files/$name").openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.connectTimeout = 20_000; c.readTimeout = 120_000
            if (c.responseCode != 200) throw HttpError(c.responseCode, null)
            val tmp = File(dest.parentFile, dest.name + ".part")
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        } finally {
            c.disconnect()
        }
    }
}
