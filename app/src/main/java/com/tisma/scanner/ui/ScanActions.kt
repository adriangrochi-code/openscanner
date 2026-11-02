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
}
