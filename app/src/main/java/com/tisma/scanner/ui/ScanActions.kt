package com.tisma.scanner.ui

import android.graphics.SurfaceTexture

/** Lo que la interfaz puede pedir. La implementa ScanViewModel (y un doble en los tests de UI). */
interface ScanActions {
    fun onPreviewAvailable(texture: SurfaceTexture)
    fun onPreviewDestroyed()
    fun startScan()
    fun stopScan()
    fun captureNow()
    fun focusSweep()
    fun setPass(p: com.tisma.scanner.camera.ScanPass)
    fun setPartDepth(cm: Int)
    fun setAutoTrigger(v: Boolean)
    fun setHdr(v: Boolean)
    fun setTorch(v: Boolean)
    fun setLock3A(v: Boolean)
    fun setDiscardBlurry(v: Boolean)
    fun setJpegQuality(q: Int)
    fun setUseConcurrent(v: Boolean)
    fun setRotateCameras(v: Boolean)
    /** Cámara que se ve en una pasada (null = elección automática). */
    fun setPreviewCamera(pass: com.tisma.scanner.camera.ScanPass, id: String?)
    /** Informe de cámaras para compartir y depurar a distancia. */
    fun diagnostics(): String
    /** Prueba de quietud de 60 s para medir la precisión de la INS. */
    fun runImuStillTest()
    fun setStabilizePreview(v: Boolean)
    /** Usar la cámara macro en la pasada Cerca. */
    fun setUseMacro(v: Boolean)
    /** Medir distancia con el enfoque (barridos automáticos). */
    fun setFocusDepth(v: Boolean)
    fun setAntiShakeBurst(v: Boolean)
    /** Transformación de estabilización para un fotograma de la vista previa (o null). */
    fun stabilizePreview(frameTimestampNs: Long, viewW: Int, viewH: Int): com.tisma.scanner.camera.PreviewStabilizer.Transform?
}
