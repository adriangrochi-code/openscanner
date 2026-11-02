package com.tisma.scanner.camera

import android.hardware.camera2.CameraMetadata

/**
 * Un "grupo" es un CameraDevice abierto. Si es una cámara lógica multi-cámara, se piden
 * flujos de cada cámara física (gran angular, ultra gran angular, tele...) en la misma
 * captura, con lo que se obtienen imágenes sincronizadas por hardware de varios objetivos.
 */
data class CameraGroup(
    val deviceId: String,
    val physicalStreams: List<String>,   // vacío => un único flujo de la cámara lógica/simple
    val primary: Boolean,                // el que lleva la previsualización
    val concurrent: Boolean,             // abierto a la vez que otro CameraDevice
) {
    val streamIds: List<String> get() = physicalStreams.ifEmpty { listOf(deviceId) }
}

object RigPlanner {

    /**
     * Elige la configuración que exprime más cámaras a la vez:
     *  1. La cámara trasera lógica con más cámaras físicas -> todos sus objetivos en paralelo.
     *  2. Si el dispositivo lo permite (getConcurrentCameraIds), abre además las demás cámaras
     *     del conjunto concurrente (normalmente la frontal).
     */
    fun plan(
        cameras: Map<String, CameraInfo>,
        concurrentSets: List<Set<String>>,
        includeConcurrent: Boolean,
        maxPhysicalStreams: Int = 3,
    ): List<CameraGroup> {
        val publicIds = cameras.keys.filter { id -> cameras.values.none { id in it.physicalIds } || cameras[id]!!.isLogicalMultiCamera }
        val back = publicIds.mapNotNull { cameras[it] }.filter { it.facing == CameraMetadata.LENS_FACING_BACK }
        val main = back.maxWithOrNull(compareBy<CameraInfo>({ it.physicalIds.size }, { -(it.id.toIntOrNull() ?: 99) }))
            ?: publicIds.firstOrNull()?.let { cameras[it] }
            ?: return emptyList()

        val physical = if (main.isLogicalMultiCamera) main.physicalIds.take(maxPhysicalStreams) else emptyList()
        val groups = mutableListOf(CameraGroup(main.id, physical, primary = true, concurrent = false))

        if (includeConcurrent) {
            val set = concurrentSets.firstOrNull { main.id in it && it.size > 1 }
            if (set != null) {
                for (other in set) if (other != main.id) {
                    // En modo concurrente sólo hay garantía para un flujo por cámara.
                    groups += CameraGroup(other, emptyList(), primary = false, concurrent = true)
                }
                groups[0] = groups[0].copy(concurrent = true)
            }
        }
        return groups
    }
}
