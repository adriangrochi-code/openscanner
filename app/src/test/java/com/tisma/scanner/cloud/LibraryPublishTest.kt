package com.tisma.scanner.cloud

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)  // org.json es el de Android
class LibraryPublishTest {
    private val item = JSONObject("""{"id":"af_1","files":[
        {"path":"pieza_informe.json","size":75,"sha256":"bb"},
        {"path":"pieza.stl","size":1234,"sha256":"aa"},
        {"path":"log.txt","size":9,"sha256":"cc"}]}""")

    @Test fun theManifestIsTheCanonicalJsonTheServerSigns() {
        // Lo mismo que json.dumps({"files": ...}, sort_keys=True, separators=(",", ":")) con los archivos por path.
        val files = LibraryPublish.files(item)
        assertEquals("""{"files":[{"path":"pieza.stl","role":"project","sha256":"aa","size":1234},""" +
            """{"path":"pieza_informe.json","role":"analysis","sha256":"bb","size":75}]}""", LibraryPublish.canonical(files))
    }

    @Test fun stringsAreEscapedAsPythonsJsonDumpsWithoutAsciiEscapes() {
        // json.dumps(v, ensure_ascii=False): «/» y ñ tal cual; comillas, barra invertida y control escapados.
        assertEquals("\"sub/año \\\"x\\\" \\\\ \\n\\t\\u0001ü\"", LibraryPublish.jsonString("sub/año \"x\" \\ \n\t\u0001ü"))
    }

    @Test fun theBodyIsSignedWithThePeerKeyAndKeepsTheLimits() {
        val key = LibraryPublish.PeerKey.create()
        val body = LibraryPublish.body(item, key, "Engranaje", "Ana", "CC-BY-4.0", "", ByteArray(10))
        assertEquals("af_1", body.getString("from_file"))
        assertEquals(2, body.getJSONArray("files").length())  // log.txt no se publica
        val v = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(Base64.getDecoder().decode(key.publicB64), 0)) }
        val msg = LibraryPublish.canonical(body.getJSONArray("files")).toByteArray()
        v.update(msg, 0, msg.size)
        assertTrue(v.verifySignature(Base64.getDecoder().decode(body.getString("manifest_signature"))))
        // La misma clave guardada y leída firma igual.
        assertEquals(key.publicB64, LibraryPublish.PeerKey.fromB64(key.seedB64).publicB64)
        for (bad in listOf({ LibraryPublish.body(item, key, "", "Ana", "MIT", "", null) },
                           { LibraryPublish.body(item, key, "x", "Ana", "WTFPL", "", null) },
                           { LibraryPublish.body(item, key, "x", "Ana", "MIT", "", ByteArray(70_000)) },
                           { LibraryPublish.body(JSONObject("""{"id":"af","files":[]}"""), key, "x", "Ana", "MIT", "", null) })) {
            assertTrue(runCatching(bad).isFailure)
        }
    }

    @Test fun withTheAccountsNameTheBodyHasNoAuthor() {
        // El autor es la cuenta: si ya tiene nombre público no se manda author_name, y lo firmado no cambia.
        val key = LibraryPublish.PeerKey.create()
        val body = LibraryPublish.body(item, key, "Engranaje", null, "CC-BY-4.0", "", null)
        assertTrue(!body.has("author_name"))
        assertTrue(LibraryPublish.fieldsOk("Engranaje", null, ""))
        assertTrue(!LibraryPublish.fieldsOk("Engranaje", "  ", ""))   // la primera vez, un nombre de verdad
    }

    @Test fun limitsCountWhatIsSentAndCharactersAsTheServerDoes() {
        val key = LibraryPublish.PeerKey.create()
        // 120 caracteres y un espacio al final: se envía sin el espacio, así que entra.
        val body = LibraryPublish.body(item, key, "a".repeat(120) + " ", " Ana ", "MIT", " hola ", null)
        assertEquals("a".repeat(120), body.getString("name"))
        assertEquals("Ana", body.getString("author_name"))
        assertEquals("hola", body.getString("description"))
        // 60 emojis: 60 caracteres para el servidor (len de Python), 120 unidades UTF-16 en Kotlin.
        val emoji = "\uD83D\uDD27".repeat(60)
        assertEquals(emoji, LibraryPublish.body(item, key, "x", emoji, "MIT", "", null).getString("author_name"))
        assertTrue(runCatching { LibraryPublish.body(item, key, "x", emoji + "a", "MIT", "", null) }.isFailure)
        assertTrue(runCatching { LibraryPublish.body(item, key, "a".repeat(121), "Ana", "MIT", "", null) }.isFailure)
        assertTrue(runCatching { LibraryPublish.body(item, key, "   ", "Ana", "MIT", "", null) }.isFailure)
        // El formulario recorta sin partir un emoji, y acepta lo mismo que body.
        assertEquals(emoji, LibraryPublish.clip(emoji + "\uD83D\uDD27", 60))
        assertEquals("ab", LibraryPublish.clip("ab", 60))
        assertTrue(LibraryPublish.fieldsOk("a".repeat(120) + " ", emoji, ""))
        assertTrue(!LibraryPublish.fieldsOk(" ", "Ana", ""))
    }
}
