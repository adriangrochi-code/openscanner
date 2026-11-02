package com.tisma.scanner.quality

import kotlin.math.abs

/**
 * Métricas rápidas de calidad de una foto, calculadas sobre una miniatura en escala de grises.
 *
 * @property sharpness var(Laplaciano) / var(imagen): nitidez normalizada, casi independiente de la
 *   exposición (ambas escalan con el contraste²), así las fotos −2/+2 EV del HDR no parecen movidas.
 * @property texture fracción de baldosas con gradiente relativo suficiente: lo que la fotogrametría
 *   puede emparejar. Una pieza lisa y mate da valores bajos -> aviso para usar tapete o spray.
 * @property clippedHigh / clippedLow fracción de píxeles quemados / negros.
 */
data class QualityReport(
    val sharpness: Double,
    val texture: Double,
    val clippedHigh: Double,
    val clippedLow: Double,
    val meanLuma: Double,
)

object ImageQuality {

    /** @param gray luminancia 0..255 (fila a fila), w×h. */
    fun analyzeGray(gray: IntArray, w: Int, h: Int, tile: Int = 32): QualityReport {
        require(gray.size >= w * h && w >= 3 && h >= 3)
        var sum = 0.0; var sum2 = 0.0; var hi = 0; var lo = 0
        for (i in 0 until w * h) {
            val v = gray[i]
            sum += v; sum2 += v.toDouble() * v
            if (v >= 250) hi++ else if (v <= 5) lo++
        }
        val n = (w * h).toDouble()
        val mean = sum / n
        val varGray = (sum2 / n - mean * mean).coerceAtLeast(0.0)

        var lSum = 0.0; var lSum2 = 0.0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val c = row + x
                val l = (4 * gray[c] - gray[c - 1] - gray[c + 1] - gray[c - w] - gray[c + w]).toDouble()
                lSum += l; lSum2 += l * l
            }
        }
        val ln = ((w - 2) * (h - 2)).toDouble()
        val lMean = lSum / ln
        val varLap = lSum2 / ln - lMean * lMean
        val sharpness = varLap / (varGray + 1.0)

        // Textura por baldosas: gradiente medio relativo a la luminancia local.
        var tiles = 0; var textured = 0
        var ty = 0
        while (ty + tile <= h) {
            var tx = 0
            while (tx + tile <= w) {
                var g = 0.0; var m = 0.0; var clipped = 0
                for (y in ty until ty + tile - 1) {
                    val row = y * w
                    for (x in tx until tx + tile - 1) {
                        val c = row + x
                        g += abs(gray[c + 1] - gray[c]) + abs(gray[c + w] - gray[c])
                        m += gray[c]
                        if (gray[c] >= 250 || gray[c] <= 5) clipped++
                    }
                }
                val cnt = ((tile - 1) * (tile - 1)).toDouble()
                if (clipped < cnt * 0.5) {
                    tiles++
                    if ((g / cnt) / (m / cnt + 10.0) > TEXTURE_THRESHOLD) textured++
                }
                tx += tile
            }
            ty += tile
        }
        return QualityReport(
            sharpness = sharpness,
            texture = if (tiles == 0) 0.0 else textured.toDouble() / tiles,
            clippedHigh = hi / n,
            clippedLow = lo / n,
            meanLuma = mean,
        )
    }

    /** Gradiente medio / luminancia por encima del cual una baldosa se considera "con textura". */
    const val TEXTURE_THRESHOLD = 0.03
}

/**
 * Lleva la cuenta de nitidez por cámara para juzgar cada foto respecto a las anteriores
 * (la nitidez absoluta depende mucho de la escena; la relativa no).
 */
class BlurJudge(private val window: Int = 15, private val ratio: Double = 0.5) {
    private val history = HashMap<String, ArrayDeque<Double>>()

    @Synchronized fun isBlurry(cameraId: String, sharpness: Double): Boolean {
        val h = history.getOrPut(cameraId) { ArrayDeque() }
        val blurry = if (h.size >= 5) sharpness < h.sorted()[h.size / 2] * ratio else false
        if (!blurry) { h.addLast(sharpness); while (h.size > window) h.removeFirst() }
        return blurry
    }

    @Synchronized fun reset() = history.clear()
}
