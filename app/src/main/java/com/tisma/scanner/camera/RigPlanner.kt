package com.tisma.scanner.camera

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
    /** Objetivo físico que se ve en la vista previa (p.ej. sólo la macro en la pasada cerca). */
    val previewPhysicalId: String? = null,
) {
    val streamIds: List<String> get() = physicalStreams.ifEmpty { listOf(deviceId) }
}
