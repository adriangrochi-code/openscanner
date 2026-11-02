package com.tisma.scanner.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassRigPlannerTest {
    private val front = Lens("1", back = false, logical = false, physicalIds = emptyList(), isPublic = true, pixels = 16_000_000, hfovDeg = 78.0)

    /** Caso A: una cámara lógica "0" que esconde principal (12 MP), ultra gran angular (5 MP) y macro (2 MP). */
    private val logicalPhone = listOf(
        Lens("0", true, true, listOf("2", "3", "4"), true, 12_000_000, 72.0),
        Lens("2", true, false, emptyList(), false, 12_000_000, 72.0),
        Lens("3", true, false, emptyList(), false, 5_000_000, 108.0),
        Lens("4", true, false, emptyList(), false, 2_000_000, 80.0),
        front,
    )

    /** Caso B: cada objetivo es una cámara pública independiente. */
    private val separatePhone = listOf(
        Lens("0", true, false, emptyList(), true, 12_000_000, 72.0),
        Lens("2", true, false, emptyList(), true, 5_000_000, 108.0),
        Lens("3", true, false, emptyList(), true, 2_000_000, 80.0),
        front,
    )

    @Test fun detectsRolesHonorLike() {
        val r = CameraRoles.detect(logicalPhone)
        assertEquals("2", r.main); assertEquals("3", r.ultrawide); assertEquals("4", r.macro); assertEquals(null, r.tele)
    }

    @Test fun farIsMainPlusUltrawideSyncedClosePreviewsOnlyMacro() {
        val roles = CameraRoles.detect(logicalPhone)
        val far = PassRigPlanner.plan(ScanPass.FAR, roles, logicalPhone, emptyList())
        assertEquals(RigPlan.Mode.PHYSICAL, far.mode)
        assertEquals(listOf("2", "3"), far.groups.single().physicalStreams)
        assertEquals("2", far.groups.single().previewPhysicalId)

        val close = PassRigPlanner.plan(ScanPass.CLOSE, roles, logicalPhone, emptyList())
        assertEquals(RigPlan.Mode.PHYSICAL, close.mode)
        assertEquals("4", close.groups.single().previewPhysicalId)   // en pantalla, sólo la macro
        assertEquals(listOf("4", "2"), close.groups.single().physicalStreams)
    }

    @Test fun separateCamerasWithoutConcurrencyGoByTurns() {
        val roles = CameraRoles.detect(separatePhone)
        val close = PassRigPlanner.plan(ScanPass.CLOSE, roles, separatePhone, emptyList())
        assertEquals(RigPlan.Mode.TURNS, close.mode)
        assertEquals("3", close.groups.single().deviceId)               // vista: macro
        assertEquals("0", close.separate.single().deviceId)             // compañera: principal
    }

    @Test fun separateCamerasWithConcurrencyOpenTogether() {
        val roles = CameraRoles.detect(separatePhone)
        val far = PassRigPlanner.plan(ScanPass.FAR, roles, separatePhone, listOf(setOf("0", "2")))
        assertEquals(RigPlan.Mode.CONCURRENT, far.mode)
        assertEquals(listOf("0", "2"), far.groups.map { it.deviceId })
        assertTrue(far.groups.first().primary)
    }

    @Test fun phoneWithoutMacroUsesMainForClose() {
        val phone = separatePhone.filter { it.id != "3" }
        val close = PassRigPlanner.plan(ScanPass.CLOSE, CameraRoles.detect(phone), phone, emptyList())
        assertEquals(RigPlan.Mode.SINGLE, close.mode)
        assertEquals("0", close.previewId)
    }

    @Test fun teleOnlyUsedWhenNoUltrawide() {
        val phone = listOf(
            Lens("0", true, false, emptyList(), true, 12_000_000, 72.0),
            Lens("5", true, false, emptyList(), true, 8_000_000, 35.0),
        )
        val roles = CameraRoles.detect(phone)
        assertEquals("5", roles.tele)
        assertEquals("5", PassRigPlanner.plan(ScanPass.FAR, roles, phone, emptyList()).companionId)
    }
}
