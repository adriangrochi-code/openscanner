package com.tisma.scanner.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lo que las guías prometen en pantalla existe en la app, y la app habla de tú. Las guías citaban
 * «¡DEMASIADO RÁPIDO!», que la app nunca muestra; quedaban «mantené» y «sacás».
 */
class GuideLabelsTest {
    private val app = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

    /** Citas de las guías que no son de la app: el diálogo de la impresora, Android y mensajes del servidor. */
    private val notTheApp = setOf("100 %", "tamaño real", "Tamaño real", "ajustar a la página", "Ajustar a la página",
        "instalar apps desconocidas", "No se pudo armar la pieza con estas fotos")

    @Test fun everyLabelTheGuidesQuoteIsInTheApp() {
        val missing = listOf("../docs/GUIA.md", "../docs/PROBAR.md").flatMap { doc ->
            Regex("«([^»]{2,80})»").findAll(File(doc).readText()).map { it.groupValues[1].replace(Regex("\\s+"), " ") }
                .filter { it !in notTheApp && !appHas(it) }
                .map { "$doc: «$it»" }.toList()
        }
        assertTrue("las guías citan textos que la app no tiene:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** El texto (o, si tiene números, cada parte fija de él, sin los símbolos de un icono aparte) está en la app. */
    private fun appHas(label: String): Boolean {
        // Partido también en «: », donde la app arma el mensaje en dos trozos («Falta (pasada lejos): » + lo que falta).
        val parts = label.split(Regex("\\d+|: ")).map { it.trim().trimStart('✓', '⚠', '■', '☁', '⬢', ' ') }.filter { it.length > 1 }
        return parts.all { it in app }
    }

    @Test fun theAppSpeaksInTu() {
        val voseo = Regex("\"[^\"\\n]*(?<!\\p{L})(mantené|probá|tocá|elegí|esperá|usá|volvé|hacé|poné|mirá|acercá|alejá|girá|caminá|" +
            "apuntá|sacá|sacás|movés|tenés|podés|querés|entrá|escaneá|borrá|pulsá|abrí|cerrá|seguí|repetí|revisá|activá|" +
            "instalá|imprimí|dejá|fijate|asegurate)(?!\\p{L})[^\"\\n]*\"")
        val found = voseo.findAll(app).map { it.value }.toList()
        assertTrue("textos en voseo: $found", found.isEmpty())
    }
}
