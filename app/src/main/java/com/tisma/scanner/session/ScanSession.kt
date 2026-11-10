package com.tisma.scanner.session

import android.os.Build
import com.tisma.scanner.camera.CameraInfo
import com.tisma.scanner.ins.InsState
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Estructura del dataset (pensada para COLMAP / OpenMVS / Meshroom y tools/reconstruct.py):
 *
 * scan_YYYYMMDD_HHMMSS/
 *   session.json         dispositivo, cámaras (intrínsecos, distorsión, pose relativa), ajustes
 *   imu.csv              muestras crudas de todos los sensores inerciales
 *   ins_trajectory.csv   trayectoria estimada por la INS (≈ cada muestra del giróscopo, diezmada)
 *   frames.csv           una fila por imagen: cámara, timestamp, pose INS interpolada
 *   capture_meta.jsonl   metadatos por captura y cámara (exposición, foco, intrínsecos dinámicos)
 *   images/<cameraId>/<timestamp>.jpg
 */
class ScanSession(baseDir: File) {

    val dir: File = File(baseDir, "scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
    private val imagesDir = File(dir, "images")
    private val frames: BufferedWriter
    private val trajectory: BufferedWriter
    private val meta: BufferedWriter
    private val refined: BufferedWriter
    private val quality: BufferedWriter
    val imageCount = AtomicInteger(0)

    init {
        imagesDir.mkdirs()
        frames = File(dir, "frames.csv").bufferedWriter().apply {
            write("frame,trigger,pass,camera_id,t_ns,file,width,height,px,py,pz,qw,qx,qy,qz,stationary\n")
        }
        trajectory = File(dir, "ins_trajectory.csv").bufferedWriter().apply {
            write("t_ns,px,py,pz,vx,vy,vz,qw,qx,qy,qz,stationary\n")
        }
        meta = File(dir, "capture_meta.jsonl").bufferedWriter()
        refined = File(dir, "refined_poses.csv").bufferedWriter().apply {
            write("t_ns,px,py,pz,qw,qx,qy,qz\n")
        }
        quality = File(dir, "quality.csv").bufferedWriter().apply {
            write("t_ns,pass,camera_id,sharpness,texture,clipped_high,clipped_low,mean_luma,blurry,motion_blur_px,deleted,view_az_deg,view_el_deg,coverage_ring,coverage_bin\n")
        }
    }

    fun imuWriter(): BufferedWriter = File(dir, "imu.csv").bufferedWriter(bufferSize = 1 shl 16)

    fun writeSessionInfo(cameras: Collection<CameraInfo>, opened: List<Pair<String, String>>, extra: JSONObject) {
        val j = JSONObject().apply {
            put("format", "tisma-scanner/1")
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                put("sdk", Build.VERSION.SDK_INT)
            })
            put("time_base", "elapsedRealtimeNanos")
            put("world_frame", "Z arriba (gravedad -Z), X/Y horizontales; ejes del dispositivo = Android sensor frame")
            put("cameras", JSONArray(cameras.map { it.toJson() }))
            put("opened_streams", JSONArray(opened.map { JSONObject().put("camera_id", it.first).put("size", it.second) }))
            put("settings", extra)
        }
        File(dir, "session.json").writeText(j.toString(2))
    }

    @Synchronized
    fun addImage(trigger: Int, pass: String, cameraId: String, tNs: Long, w: Int, h: Int, jpeg: ByteArray, pose: InsState?) {
        val camDir = File(imagesDir, cameraId).apply { mkdirs() }
        val f = File(camDir, "$tNs.jpg")
        FileOutputStream(f).use { it.write(jpeg) }
        val n = imageCount.incrementAndGet()
        val rel = "images/$cameraId/${f.name}"
        val p = pose
        frames.write(buildString {
            append(n).append(',').append(trigger).append(',').append(pass).append(',').append(cameraId).append(',').append(tNs).append(',')
            append(rel).append(',').append(w).append(',').append(h).append(',')
            if (p != null) {
                append(p.position.x).append(',').append(p.position.y).append(',').append(p.position.z).append(',')
                append(p.orientation.w).append(',').append(p.orientation.x).append(',')
                append(p.orientation.y).append(',').append(p.orientation.z).append(',')
                append(if (p.stationary) 1 else 0)
            } else append(",,,,,,,") // 8 campos vacíos (px..stationary)
            append('\n')
        })
    }

    /** Borra una foto descartada (movida). Devuelve los bytes liberados. */
    fun deleteImage(cameraId: String, tNs: Long): Long {
        val f = File(File(imagesDir, cameraId), "$tNs.jpg")
        val len = f.length()
        return if (f.delete()) len else 0L
    }

    /**
     * Pose de un fotograma clave con la posición INS corregida por la imagen (dirección del
     * movimiento por visión, longitud por INS). El servidor la usa como prior de cada foto.
     */
    @Synchronized
    fun addRefinedPose(tNs: Long, c: com.tisma.scanner.ins.Vec3, q: com.tisma.scanner.ins.Quat) {
        runCatching { refined.write("$tNs,${c.x},${c.y},${c.z},${q.w},${q.x},${q.y},${q.z}\n") }
    }

    @Synchronized
    fun addTrajectory(s: InsState) {
        trajectory.write("${s.timestampNs},${s.position.x},${s.position.y},${s.position.z}," +
            "${s.velocity.x},${s.velocity.y},${s.velocity.z}," +
            "${s.orientation.w},${s.orientation.x},${s.orientation.y},${s.orientation.z},${if (s.stationary) 1 else 0}\n")
    }

    @Synchronized
    fun addCaptureMeta(trigger: Int, cameraId: String, tNs: Long, m: JSONObject) {
        meta.write(m.put("trigger", trigger).put("camera_id", cameraId).put("t_ns", tNs).toString())
        meta.write("\n")
    }

    @Synchronized
    fun addQuality(pass: String, cameraId: String, tNs: Long, q: com.tisma.scanner.quality.QualityReport, blurry: Boolean,
                   motionBlurPx: Double, deleted: Boolean, azEl: Pair<Double, Double>?, bin: Pair<Int, Int>?) {
        quality.write("$tNs,$pass,$cameraId,${q.sharpness},${q.texture},${q.clippedHigh},${q.clippedLow},${q.meanLuma}," +
            "${if (blurry) 1 else 0},${"%.2f".format(java.util.Locale.US, motionBlurPx)},${if (deleted) 1 else 0},${azEl?.first ?: ""},${azEl?.second ?: ""},${bin?.first ?: ""},${bin?.second ?: ""}\n")
    }

    /** Resumen para decidir en el servidor (o antes de subir) si merece la pena procesar. */
    fun writeQualitySummary(summary: JSONObject) {
        File(dir, "quality_summary.json").writeText(summary.toString(2))
    }

    @Synchronized
    fun close() {
        runCatching { frames.close() }; runCatching { trajectory.close() }; runCatching { meta.close() }
        runCatching { refined.close() }
        runCatching { quality.close() }
    }

    /** Comprime el escaneo en un .zip junto a la carpeta para compartirlo. */
    fun zip(): File = zipDir(dir)

    companion object {
        /**
         * Un escaneo cortado mientras se grababa (la app se cerró de golpe): sus .csv y .jsonl pueden terminar en una
         * línea a medias. Se dejan hasta la última línea completa (con su salto de línea y, en un .csv, con tantos
         * campos como la cabecera; en un .jsonl, que sea un JSON). Devuelve cuántas líneas se quitaron.
         */
        fun repairTruncated(dir: File): Int {
            var removed = 0
            dir.listFiles { f -> f.isFile && (f.extension == "csv" || f.extension == "jsonl") }?.forEach { f ->
                val text = f.readText()
                if (text.isEmpty()) return@forEach
                val lines = text.split('\n').toMutableList()
                val tail = lines.removeAt(lines.size - 1)            // lo que sigue al último salto: incompleto
                if (tail.isNotEmpty()) removed++
                if (f.extension == "csv" && lines.size > 1) {
                    val fields = lines[0].count { it == ',' }
                    while (lines.size > 1 && lines.last().count { it == ',' } != fields) { lines.removeAt(lines.size - 1); removed++ }
                } else if (f.extension == "jsonl") {
                    while (lines.isNotEmpty() && runCatching { org.json.JSONObject(lines.last()) }.isFailure) { lines.removeAt(lines.size - 1); removed++ }
                }
                f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
            }
            return removed
        }

        /** Archivos del escaneo que nunca salen del teléfono: la posición absoluta (decisión del dueño, 04-11-2026). */
        val STAYS_ON_PHONE = setOf("gps.csv")

        /** Comprime una carpeta de escaneo en un .zip a su lado (también para retomar un escaneo). */
        fun zipDir(dir: File): File {
        val out = File(dir.parentFile, dir.name + ".zip")
        ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
            // Los JPEG ya están comprimidos: compresión mínima = zip mucho más rápido.
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            // Fuera lo interno del procesado en el móvil (estado para retomar, temporales) y la posición absoluta
            // (gps.csv: latitud, longitud y altura): nunca sale del teléfono, sólo las poses relativas entre fotos.
            val internal = setOf("recon_state.bin", "pendiente.json", "deblur_pendiente.csv") + STAYS_ON_PHONE
            dir.walkTopDown().filter { it.isFile && it.name !in internal && !it.name.endsWith(".tmp") }.forEach { f ->
                val entry = ZipEntry(dir.name + "/" + f.relativeTo(dir).invariantSeparatorsPath)
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out
    }
    }
}
