package com.tisma.scanner

import android.content.Context

/**
 * Los avisos legales que la AGPL-3.0 pide en una interfaz interactiva (sección 5 d): quién, la licencia, que no hay
 * garantía, cómo ver la licencia y dónde está el código fuente. Los textos completos van en el APK (assets/legal/).
 */
object About {
    const val SOURCE_URL = "https://github.com/adriangrochi-code/openscanner"
    const val LICENSE_FILE = "legal/LICENSE"
    const val THIRD_PARTY_FILE = "legal/THIRD_PARTY.md"

    fun notice(version: String) =
        "Tisma Scanner $version · © 2026 los autores de Tisma Scanner.\n" +
            "Software libre: puedes usarlo, estudiarlo, modificarlo y compartirlo bajo la licencia GNU Affero " +
            "General Public License 3.0 (AGPL-3.0). Se entrega SIN NINGUNA GARANTÍA; los detalles, en la licencia.\n" +
            "Código fuente: $SOURCE_URL"

    fun read(ctx: Context, file: String): String =
        runCatching { ctx.assets.open(file).bufferedReader().use { it.readText() } }.getOrDefault("(no está en esta versión)")
}
