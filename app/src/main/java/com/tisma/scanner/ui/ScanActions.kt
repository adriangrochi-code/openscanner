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
    /** Velocímetro de cámara (flujo óptico + enfoque) para frenar la deriva de la INS. */
    fun setCameraVelocity(v: Boolean)
    /** Calibración base a tiempo (deriva en N minutos + registro). */
    fun runDriftTest()
    fun cancelDriftTest()
    fun setDriftMinutes(m: Int)
    /** Borra la calibración automática del acelerómetro y sus posturas guardadas. */
    fun resetAutoCalibration()
    fun setCloudUrl(v: String)
    /** Entrar con la cuenta de Tisma Cloud (guarda el token de la sesión, no la contraseña). */
    fun cloudLogin(email: String, password: String)
    fun cloudLogout()
    /** Enviar el último escaneo a Tisma Cloud y traer la pieza. */
    fun sendToCloud()
    fun setAntiShakeBurst(v: Boolean)
    /** Vídeo 360 (una cámara, flujo continuo, fotogramas automáticos) o fotos sueltas. */
    fun setVideoMode(v: Boolean)
    /** Quitar el desenfoque de movimiento de las fotos en el móvil. */
    fun setDeblurMotion(v: Boolean)
    /** Crear el modelo 3D en el móvil (sin servidor) con lo reconstruido en vivo. */
    fun buildLocalModel()
    /** IA de profundidad (Depth Anything 3 Small) para el modelo del móvil. */
    fun setUseDepthAi(v: Boolean)
    fun setDepthModelUrl(v: String)
    fun downloadDepthModel()
    /** Pose de la cámara en tiempo real para la guía en realidad aumentada (modo vídeo). */
    val arPose: kotlinx.coroutines.flow.StateFlow<ArPose?>
    /** Transformación de estabilización para un fotograma de la vista previa (o null). */
    fun stabilizePreview(frameTimestampNs: Long, viewW: Int, viewH: Int): com.tisma.scanner.camera.PreviewStabilizer.Transform?
}
