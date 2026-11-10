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
        require(token.isNotBlank()) { "Entra con tu cuenta de Tisma Cloud (⚙ → Nube)" }
    }

    /** Un error del servidor, con su texto (`detail`) para mostrar. */
    class HttpError(val code: Int, val body: JSONObject?, text: String = describe(code, body)) : IOException(text) {
        companion object {
            /**
             * Los textos de Tisma Cloud (`detail`) están en inglés para todos sus clientes: aquí, en español, por código y
             * comienzo del texto (algunos llevan números: «larger than 12 GB» → «pasa de 12 GB»). Lista de la sesión del
             * servidor, 2026-11-10. N en el texto en español es el primer número del original.
             */
            private val SPANISH = listOf(
                // Cuenta
                Triple(setOf(401), "Wrong e-mail or password", "Correo o contraseña incorrectos"),
                Triple(setOf(401), "The account is disabled", "La cuenta está deshabilitada"),
                Triple(setOf(403), "E-mail not verified", "Confirma tu correo con el código que te mandamos"),
                Triple(setOf(429), "Too many login attempts", "Demasiados intentos: espera un minuto"),
                Triple(setOf(429), "Too many attempts", "Demasiados intentos: espera un minuto"),
                Triple(setOf(429), "Too many wrong passwords",
                    "Demasiadas contraseñas equivocadas: espera una hora o elige una nueva con un código por correo"),
                Triple(setOf(409), "An account with this e-mail exists", "Ya hay una cuenta con ese correo: entra con su contraseña"),
                Triple(setOf(400), "Wrong or expired code", "Código incorrecto o vencido"),
                Triple(setOf(429), "Wait a minute before asking another code", "Espera un minuto antes de pedir otro código"),
                Triple(setOf(503), "The e-mail could not be sent", "No se pudo mandar el correo: prueba en unos minutos"),
                // Envío de un escaneo
                Triple(setOf(413), "The scan is larger than", "El escaneo pasa de N GB"),
                Triple(setOf(429), "Three scans are being sent already", "Ya se están mandando tres escaneos: termina o borra uno"),
                Triple(setOf(422), "The scan is not a zip file", "El archivo no es un escaneo de Tisma Scanner"),
                Triple(setOf(422), "session.json is missing", "El archivo no es un escaneo de Tisma Scanner"),
                Triple(setOf(422), "The scan has no photos", "El archivo no es un escaneo de Tisma Scanner"),
                Triple(setOf(422), "The scan carries the absolute position of the phone",
                    "Actualiza Tisma Scanner: este escaneo trae la ubicación del teléfono"),
                // «Imprimir»
                Triple(setOf(409), "The scan is not done", "El escaneo todavía no terminó"),
                Triple(setOf(410), "The result of the scan was removed", "El resultado ya no está (se guarda 72 h)"),
                Triple(setOf(413), "The files of the account are over", "Tus archivos en Tisma Cloud pasan de N GB: borra alguno"),
                // «Publicar»
                Triple(setOf(429), "Too many publications today", "Ya publicaste 20 modelos hoy"),
                Triple(setOf(403), "This account cannot publish in the library", "Esta cuenta no puede publicar en la biblioteca"),
                Triple(setOf(403), "Blocked in the library", "Esta cuenta no puede publicar en la biblioteca"),
                Triple(setOf(422), "Removed from the library", "Este archivo fue retirado de la biblioteca de Tisma"),
                Triple(setOf(422), "'author_name' is needed the first time", "Elige el nombre con el que vas a publicar"),
                Triple(setOf(403), "The model was removed from the library", "La publicación fue retirada de la biblioteca"),
                Triple(setOf(429), "Too many name changes today", "Ya cambiaste el nombre muchas veces hoy: prueba mañana"),
                Triple(setOf(409), "The model is being changed at the same time", "Se está cambiando al mismo tiempo: prueba de nuevo"),
            )

            /** El texto en español de un `detail` conocido, o null. */
            fun spanish(code: Int, detail: String?): String? {
                if (detail == null) return null
                SPANISH.firstOrNull { (codes, start, _) -> code in codes && detail.startsWith(start) }?.let { (_, _, es) ->
                    val n = Regex("\\d+(?:[.,]\\d+)?").find(detail)?.value
                    return if ("N" in es.split(' ') && n != null) es.split(' ').joinToString(" ") { if (it == "N") n else it } else es
                }
                // Los de «Publicar» que nombran un campo: «name has control or direction characters», «… is at most N».
                if (code == 422 && "has control or direction characters" in detail) return "El nombre tiene caracteres no permitidos"
                if (code == 422 && "is at most" in detail && "characters" in detail) return "El texto es demasiado largo"
                return null
            }

            fun describe(code: Int, body: JSONObject?): String {
                val detail = body?.optString("detail")?.takeIf { it.isNotBlank() }
                spanish(code, detail)?.let { return it }
                return when {
                    code == 401 -> "La sesión venció o se cerró: entra de nuevo"
                    code == SERVER_FULL -> "Tisma Cloud no tiene lugar ahora: el envío se reintenta solo más tarde"
                    code == 410 -> "La pieza ya no está en la nube (se guarda 72 h): escanea de nuevo"
                    code == 413 && detail == null -> "Tu cuenta llegó al límite de archivos: borra alguno de Mi biblioteca"
                    code == 429 && detail == null -> "Demasiados pedidos seguidos: prueba más tarde"
                    detail != null -> detail
                    else -> "HTTP $code"
                }
            }
        }
    }

    companion object {
        /**
         * Lo que se le muestra a la persona de un fallo al hablar con Tisma Cloud: los errores de red de Java llegan en
         * inglés («Unable to resolve host…», «failed to connect…», «timeout»), y sin red era eso lo que se veía al
         * entrar, en «Imprimir» o «Publicar». Los mensajes propios (ya en español) pasan tal cual.
         */
        fun userMessage(e: Throwable): String = when (e) {
            is HttpError -> e.message ?: "HTTP ${e.code}"
            is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException,
            is java.net.PortUnreachableException -> "Sin conexión con Tisma Cloud: revisa la red e inténtalo de nuevo"
            is java.net.SocketTimeoutException -> "Tisma Cloud tarda en responder: inténtalo de nuevo en un rato"
            is javax.net.ssl.SSLException -> "No se pudo hacer una conexión segura con Tisma Cloud (¿están bien la fecha y la hora del teléfono?)"
            is java.io.EOFException, is java.net.ProtocolException, is java.net.SocketException ->
                "Se cortó la conexión con Tisma Cloud: inténtalo de nuevo"
            else -> e.message?.takeIf { it.isNotBlank() } ?: "No se pudo hablar con Tisma Cloud"
        }

        /** Tisma Cloud sin lugar en disco (507, con Retry-After): la subida se guarda y se sigue más tarde. */
        const val SERVER_FULL = 507

        fun normalize(url: String): String {
            val base = url.trim().trimEnd('/')
            require(base.startsWith("https://")) { "El servidor debe usar https://" }
            return base
        }

        /** Entra con la cuenta. Devuelve (token, correo). */
        fun login(baseUrl: String, email: String, password: String, device: String): Pair<String, String> {
            val body = JSONObject().put("email", email.trim()).put("password", password).put("device", device)
            val (code, j) = call(normalize(baseUrl), "POST", "/v1/auth/login", null, body.toString().toByteArray())
            // Al entrar, 401 es correo o contraseña mal, no «la sesión venció» (que es lo que significa en lo demás).
            if (code == 401) throw HttpError(code, j, HttpError.spanish(code, j?.optString("detail")) ?: "Correo o contraseña incorrectos")
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
                    // Sin lugar en el servidor: insistir cada pocos segundos no lo arregla; lo subido queda y
                    // se sigue más tarde (CloudSendWorker lo reintenta con esperas largas).
                    if (e.code == SERVER_FULL) throw e
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

    // --- Mi biblioteca y biblioteca compartida (p2p_library.md, regla 7 bis) ---

    /**
     * «Imprimir»: guarda la pieza de un trabajo de escaneo terminado en la cuenta (privada; aparece en «Mi
     * biblioteca» del teléfono y de la PC). Sin subir nada: el servidor la toma del resultado del trabajo. Repetirlo
     * devuelve el mismo ítem (200). 410: el resultado ya no está (se guarda 72 h); 413: sin cuota de archivos.
     */
    fun keepFile(scanJob: String, name: String? = null): JSONObject {
        val body = JSONObject().put("scan_job", scanJob).apply { if (!name.isNullOrBlank()) put("name", name.trim()) }
        val (code, j) = request("POST", "/v1/files", body.toString().toByteArray())
        if ((code != 200 && code != 201) || j == null) throw HttpError(code, j)
        return j
    }

    /** Registra en la cuenta la clave de par de esta instalación (una vez), para firmar lo que publica. */
    fun registerPeerKey(publicKeyB64: String) {
        val (code, j) = request("POST", "/v1/library/peer-key", JSONObject().put("public_key", publicKeyB64).toString().toByteArray())
        if (code != 200 && code != 201) throw HttpError(code, j)
    }

    /** «Publicar»: `POST /v1/library/models` con el cuerpo de LibraryPublish.body. Devuelve la entrada (`id` lm_…). */
    /**
     * El nombre público de la cuenta en la biblioteca (`library_name`), o null si todavía no tiene: la primera
     * publicación lo toma de `author_name`. Nunca es el correo. (Regla del dueño: la pieza es de quien la escaneó.)
     */
    fun libraryName(): String? {
        val (code, j) = request("GET", "/v1/me")
        if (code != 200 || j == null) throw HttpError(code, j)
        return if (j.isNull("library_name")) null else j.optString("library_name").takeIf { it.isNotBlank() }
    }

    /** Pone (o cambia) el nombre público de la cuenta; también renombra sus publicaciones. */
    fun setLibraryName(name: String): String {
        val (code, j) = request("PUT", "/v1/me/library-name", JSONObject().put("name", name.trim()).toString().toByteArray())
        if (code != 200 || j == null) throw HttpError(code, j)
        return j.getString("library_name")
    }

    fun publishModel(body: JSONObject): JSONObject {
        val (code, j) = request("POST", "/v1/library/models", body.toString().toByteArray(), timeoutMs = 120_000)
        // Otro 422 al publicar es un fallo de la app (firma, tamaños, roles): «No se pudo publicar»; el detalle queda
        // en HttpError.body para el registro.
        if (code == 422 && HttpError.spanish(code, j?.optString("detail")) == null) throw HttpError(code, j, "No se pudo publicar")
        if (code != 201 || j == null) throw HttpError(code, j)
        return j
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
