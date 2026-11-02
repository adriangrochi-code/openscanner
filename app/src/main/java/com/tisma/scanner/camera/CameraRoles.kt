package com.tisma.scanner.camera

/**
 * Datos mínimos de un objetivo para decidir su papel. Kotlin puro (testeable sin Android).
 *
 * @property isPublic aparece en CameraManager.cameraIdList (se puede abrir directamente).
 *   Los objetivos ocultos sólo se usan como flujo físico de su cámara lógica.
 */
data class Lens(
    val id: String,
    val back: Boolean,
    val logical: Boolean,
    val physicalIds: List<String>,
    val isPublic: Boolean,
    val pixels: Long,
    val hfovDeg: Double?,
)

/** Papel de cada objetivo trasero. */
data class CameraRoles(val main: String?, val ultrawide: String?, val tele: String?, val macro: String?) {

    fun describe() = listOfNotNull(
        main?.let { "principal #$it" }, tele?.let { "tele #$it" },
        ultrawide?.let { "ultra gran angular #$it" }, macro?.let { "macro #$it" },
    ).joinToString(" · ").ifEmpty { "sin cámaras traseras" }

    companion object {
        /** Menos de esto es una cámara "de relleno" (macro/profundidad de 2 MP). */
        const val MACRO_MAX_PIXELS = 3_500_000L

        /**
         * Heurística por objetivo físico ("hoja", no lógico):
         *  - macro: el de menor resolución, por debajo de 3,5 MP (las macro de móvil son de 2 MP);
         *  - principal: el de mayor resolución entre el resto;
         *  - ultra gran angular: campo de visión > 1,25× el principal;
         *  - tele: campo de visión < principal / 1,3.
         */
        fun detect(lenses: List<Lens>): CameraRoles {
            val leaves = lenses.filter { it.back && !it.logical }.distinctBy { it.id }
            if (leaves.isEmpty()) {
                return CameraRoles(lenses.firstOrNull { it.back && it.isPublic }?.id, null, null, null)
            }
            val macro = leaves.filter { it.pixels in 1 until MACRO_MAX_PIXELS }.minByOrNull { it.pixels }
            val rest = leaves.filter { it != macro }.ifEmpty { leaves }
            val main = rest.maxWithOrNull(compareBy<Lens>({ it.pixels }, { -(it.id.toIntOrNull() ?: 99) }))!!
            val fov = main.hfovDeg
            val others = rest.filter { it != main && it.hfovDeg != null }
            val ultra = if (fov == null) null else others.filter { it.hfovDeg!! > fov * 1.25 }.maxByOrNull { it.hfovDeg!! }
            val tele = if (fov == null) null else others.filter { it.hfovDeg!! < fov / 1.3 }.minByOrNull { it.hfovDeg!! }
            return CameraRoles(main.id, ultra?.id, tele?.id, macro?.id)
        }
    }
}

/**
 * Qué se abre en una pasada.
 *
 * @property groups dispositivos abiertos a la vez (el primero lleva la vista previa).
 * @property separate cámaras compañeras que no se pueden abrir a la vez: se disparan por turnos.
 */
data class RigPlan(
    val groups: List<CameraGroup>,
    val separate: List<CameraGroup>,
    val previewId: String?,
    val companionId: String?,
    val mode: Mode,
) {
    enum class Mode(val label: String) {
        SINGLE("una cámara"),
        PHYSICAL("simultáneas (misma cámara lógica, sincronizadas por hardware)"),
        CONCURRENT("simultáneas (cámaras concurrentes)"),
        TURNS("por turnos (el móvil no las abre a la vez)"),
    }
}

object PassRigPlanner {

    /**
     * Lejos: vista = principal; compañera = ultra gran angular (o tele si no hay ultra gran angular).
     * Cerca: vista = macro (sólo la macro en pantalla); compañera = principal.
     */
    fun previewAndCompanion(pass: ScanPass, roles: CameraRoles): Pair<String?, String?> = when (pass) {
        ScanPass.FAR -> roles.main to (roles.ultrawide ?: roles.tele)
        ScanPass.CLOSE -> if (roles.macro != null) roles.macro to roles.main else roles.main to null
    }

    fun plan(
        pass: ScanPass,
        roles: CameraRoles,
        lenses: List<Lens>,
        concurrentSets: List<Set<String>>,
        allowConcurrent: Boolean = true,
    ): RigPlan {
        val (preview, companion) = previewAndCompanion(pass, roles)
        if (preview == null) return RigPlan(emptyList(), emptyList(), null, null, RigPlan.Mode.SINGLE)
        val byId = lenses.associateBy { it.id }

        /** Cámara pública lógica que contiene a `id` como objetivo físico. */
        fun parentOf(id: String) = lenses.firstOrNull { it.isPublic && it.logical && id in it.physicalIds }

        /** Cómo abrir un objetivo solo, con él en la vista previa. */
        fun solo(id: String): CameraGroup? {
            val l = byId[id]
            if (l != null && l.isPublic) return CameraGroup(id, emptyList(), primary = true, concurrent = false)
            val p = parentOf(id) ?: return null
            return CameraGroup(p.id, listOf(id), primary = true, concurrent = false, previewPhysicalId = id)
        }

        val soloPreview = solo(preview)
            ?: return RigPlan(emptyList(), emptyList(), preview, companion, RigPlan.Mode.SINGLE)
        if (companion == null || companion == preview) {
            return RigPlan(listOf(soloPreview), emptyList(), preview, null, RigPlan.Mode.SINGLE)
        }

        // 1) Ambos son objetivos físicos de la misma cámara lógica: un solo dispositivo,
        //    dos flujos sincronizados, y la vista previa apuntando al objetivo de la pasada.
        val pp = parentOf(preview)
        val pc = parentOf(companion)
        if (pp != null && pp == pc) {
            return RigPlan(
                listOf(CameraGroup(pp.id, listOf(preview, companion), primary = true, concurrent = false, previewPhysicalId = preview)),
                emptyList(), preview, companion, RigPlan.Mode.PHYSICAL,
            )
        }

        // 2) Dos dispositivos públicos que el móvil garantiza abrir a la vez.
        val soloCompanion = solo(companion)
        if (allowConcurrent && soloCompanion != null) {
            val a = soloPreview.deviceId
            val b = soloCompanion.deviceId
            if (a != b && concurrentSets.any { a in it && b in it }) {
                return RigPlan(
                    listOf(soloPreview.copy(concurrent = true), soloCompanion.copy(primary = false, concurrent = true)),
                    emptyList(), preview, companion, RigPlan.Mode.CONCURRENT,
                )
            }
        }

        // 3) Por turnos: la vista previa se queda en el objetivo de la pasada.
        return RigPlan(
            listOf(soloPreview), listOfNotNull(soloCompanion), preview, companion,
            if (soloCompanion != null) RigPlan.Mode.TURNS else RigPlan.Mode.SINGLE,
        )
    }
}
