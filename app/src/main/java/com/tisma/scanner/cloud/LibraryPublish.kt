package com.tisma.scanner.cloud

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * Publicar una pieza escaneada en la biblioteca compartida de Tisma (p2p_library.md, regla 7 bis), desde el
 * archivo que «Imprimir» guardó en la cuenta (`POST /v1/files`). El servidor la reparte; el teléfono sólo firma.
 *
 * - La app tiene una clave de par Ed25519 propia (PeerKey), registrada una vez por instalación en la cuenta.
 * - Firma el manifiesto canónico {"files":[…]}: claves ordenadas, sin espacios, UTF-8, archivos ordenados por
 *   `path`, cada uno {path, role, sha256, size}. Los archivos son los del ítem guardado (mismo size y sha256):
 *   pieza.stl como "project" y pieza_informe.json como "analysis".
 */
object LibraryPublish {

    /** Licencias que acepta la biblioteca. La primera es la que se ofrece por defecto. */
    val LICENSES = listOf(
        "CC-BY-4.0" to "CC BY 4.0 (citar al autor)",
        "CC-BY-SA-4.0" to "CC BY-SA 4.0 (citar y compartir igual)",
        "CC-BY-NC-4.0" to "CC BY-NC 4.0 (sin uso comercial)",
        "CC-BY-NC-SA-4.0" to "CC BY-NC-SA 4.0",
        "CC-BY-ND-4.0" to "CC BY-ND 4.0 (sin modificar)",
        "CC-BY-NC-ND-4.0" to "CC BY-NC-ND 4.0",
        "CC0-1.0" to "CC0 (dominio público)",
        "MIT" to "MIT",
        "GPL-3.0-or-later" to "GPL 3.0 o posterior",
        "LicenseRef-All-Rights-Reserved" to "Todos los derechos reservados",
    )
    private val ROLES = mapOf("pieza.stl" to "project", "pieza_informe.json" to "analysis")
    const val MAX_THUMBNAIL = 64 * 1024

    /** Clave de par Ed25519 de esta instalación: 32 bytes de semilla, guardados por la app (base64). */
    class PeerKey(seed: ByteArray) {
        private val key = Ed25519PrivateKeyParameters(seed, 0)
        val seedB64: String = Base64.getEncoder().encodeToString(seed)
        val publicB64: String = Base64.getEncoder().encodeToString(key.generatePublicKey().encoded)

        fun sign(message: ByteArray): String {
            val s = Ed25519Signer()
            s.init(true, key)
            s.update(message, 0, message.size)
            return Base64.getEncoder().encodeToString(s.generateSignature())
        }

        companion object {
            fun create() = PeerKey(ByteArray(32).also { SecureRandom().nextBytes(it) })
            fun fromB64(b64: String) = PeerKey(Base64.getDecoder().decode(b64))
        }
    }

    /** Los archivos del ítem guardado que se publican, con su rol: pieza.stl siempre, el informe si está. */
    fun files(item: JSONObject): JSONArray {
        val out = JSONArray()
        val all = item.getJSONArray("files")
        val list = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("path") in ROLES }
            .sortedBy { it.getString("path") }
        require(list.any { it.getString("path") == "pieza.stl" }) { "el archivo guardado no trae pieza.stl" }
        for (f in list) out.put(JSONObject().put("path", f.getString("path")).put("role", ROLES.getValue(f.getString("path")))
            .put("size", f.getLong("size")).put("sha256", f.getString("sha256")))
        return out
    }

    /**
     * Una cadena JSON como la escribe Python con json.dumps(ensure_ascii=False): sólo se escapan comillas, barra
     * invertida y caracteres de control; «/» y lo que no es ASCII (ñ…) van tal cual. org.json escapa «/», y la firma
     * no coincidiría con la del servidor.
     */
    fun jsonString(v: String): String = buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    /** El manifiesto canónico que se firma: {"files":[{path,role,sha256,size}…]}, claves ordenadas, sin espacios. */
    fun canonical(files: JSONArray): String = buildString {
        append("{\"files\":[")
        val list = (0 until files.length()).map { files.getJSONObject(it) }.sortedBy { it.getString("path") }
        list.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("{\"path\":").append(jsonString(f.getString("path")))
            append(",\"role\":").append(jsonString(f.getString("role")))
            append(",\"sha256\":").append(jsonString(f.getString("sha256")))
            append(",\"size\":").append(f.getLong("size"))
            append('}')
        }
        append("]}")
    }

    /** El cuerpo de `POST /v1/library/models` para publicar `item` (de «Imprimir»), firmado con `key`. */
    fun body(item: JSONObject, key: PeerKey, name: String, author: String, license: String, description: String,
             thumbnailWebp: ByteArray?): JSONObject {
        require(name.isNotBlank() && name.length <= 120) { "el nombre tiene que tener entre 1 y 120 caracteres" }
        require(author.isNotBlank() && author.length <= 60) { "el autor tiene que tener entre 1 y 60 caracteres" }
        require(description.length <= 500) { "la descripción tiene como mucho 500 caracteres" }
        require(LICENSES.any { it.first == license }) { "licencia desconocida: $license" }
        require(thumbnailWebp == null || thumbnailWebp.size <= MAX_THUMBNAIL) { "la miniatura pasa de 64 KB" }
        val files = files(item)
        return JSONObject()
            .put("from_file", item.getString("id"))
            .put("name", name.trim())
            .put("author_name", author.trim())
            .put("license", license)
            .apply { if (description.isNotBlank()) put("description", description.trim()) }
            .put("files", files)
            .put("manifest_signature", key.sign(canonical(files).toByteArray(Charsets.UTF_8)))
            .apply { thumbnailWebp?.let { put("thumbnail_webp", Base64.getEncoder().encodeToString(it)) } }
    }
}
