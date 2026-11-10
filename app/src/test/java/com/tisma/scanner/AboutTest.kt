package com.tisma.scanner

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * La AGPL-3.0 (sección 5 d) pide avisos legales en la interfaz: licencia, sin garantía, cómo verla. La app no tenía
 * ninguno. Ahora ⚙ → Registro → Acerca de; los textos completos van en el APK, iguales a los del repo.
 */
@RunWith(RobolectricTestRunner::class)
class AboutTest {
    @Test fun theNoticeSaysLicenceNoWarrantyAndSource() {
        val n = About.notice("0.1.0")
        for (part in listOf("Tisma Scanner 0.1.0", "AGPL-3.0", "SIN NINGUNA GARANTÍA", "Código fuente: https://"))
            assertTrue("falta «$part» en: $n", part in n)
    }

    @Test fun theFullLicenceAndThirdPartyLicencesAreInTheApp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val licence = About.read(ctx, About.LICENSE_FILE)
        assertTrue(licence.startsWith(File("../LICENSE").readText().take(200)))
        assertTrue("GNU AFFERO GENERAL PUBLIC LICENSE" in licence)
        val third = About.read(ctx, About.THIRD_PARTY_FILE)
        assertTrue(third == File("../THIRD_PARTY.md").readText())
        assertTrue("Bouncy Castle" in third && "ONNX Runtime" in third)
    }
}
