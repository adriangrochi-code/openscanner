package com.tisma.scanner.recon

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * Depth Anything 3 Small (Apache-2.0) en el móvil con ONNX Runtime: profundidad RELATIVA densa de
 * una foto. La escala la pone [DepthPrior] con los puntos medidos.
 *
 * Las exportaciones ONNX no documentan sus tensores, así que se leen del propio modelo:
 * entrada [1,3,H,W] o [1,N,3,H,W] (Depth Anything 3 acepta varias vistas), H×W fijos o libres
 * (entonces 378×504, múltiplos de 14), normalización ImageNet; de salida se toma el tensor con
 * forma de imagen más grande.
 */
class DepthModel private constructor(private val env: OrtEnvironment, private val session: OrtSession) : AutoCloseable {
    class Prediction(val w: Int, val h: Int, val depth: FloatArray)

    private val inputName = session.inputNames.first()
    private val inShape = (session.inputInfo[inputName]!!.info as TensorInfo).shape
    private val rank = inShape.size
    val inH = inShape[rank - 2].toInt().takeIf { it > 0 } ?: DEFAULT_H
    val inW = inShape[rank - 1].toInt().takeIf { it > 0 } ?: DEFAULT_W

    fun predict(bmp: Bitmap): Prediction? {
        val img = if (bmp.width == inW && bmp.height == inH) bmp else Bitmap.createScaledBitmap(bmp, inW, inH, true)
        val px = IntArray(inW * inH); img.getPixels(px, 0, inW, 0, 0, inW, inH)
        val plane = inW * inH
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            data[i] = (((c shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            data[plane + i] = (((c shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            data[2 * plane + i] = ((c and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        val shape = if (rank == 5) longArrayOf(1, 1, 3, inH.toLong(), inW.toLong()) else longArrayOf(1, 3, inH.toLong(), inW.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            session.run(mapOf(inputName to t)).use { out ->
                // El tensor de salida "imagen" más grande es la profundidad (los demás: confianza, poses…).
                var best: Prediction? = null
                for (e in out) {
                    val v = e.value as? OnnxTensor ?: continue
                    val sh = v.info.shape
                    if (sh.size < 2) continue
                    val oh = sh[sh.size - 2].toInt(); val ow = sh[sh.size - 1].toInt()
                    if (oh < 16 || ow < 16) continue
                    val fb = v.floatBuffer
                    val arr = FloatArray(ow * oh); fb.get(arr, 0, arr.size)
                    if (best == null || ow * oh > best.w * best.h || e.key.contains("depth", ignoreCase = true)) best = Prediction(ow, oh, arr)
                    if (e.key.contains("depth", ignoreCase = true)) break
                }
                return best
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        const val DEFAULT_W = 504
        const val DEFAULT_H = 378
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        fun load(file: File): DepthModel? = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            DepthModel(env, env.createSession(file.absolutePath, opts))
        }.getOrNull()
    }
}
