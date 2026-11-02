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
    val imageCount = AtomicInteger(0)

    init {
        imagesDir.mkdirs()
        frames = File(dir, "frames.csv").bufferedWriter().apply {
            write("frame,trigger,camera_id,t_ns,file,width,height,px,py,pz,qw,qx,qy,qz,stationary\n")
        }
        trajectory = File(dir, "ins_trajectory.csv").bufferedWriter().apply {
            write("t_ns,px,py,pz,vx,vy,vz,qw,qx,qy,qz,stationary\n")
        }
        meta = File(dir, "capture_meta.jsonl").bufferedWriter()
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
    fun addImage(trigger: Int, cameraId: String, tNs: Long, w: Int, h: Int, jpeg: ByteArray, pose: InsState?) {
        val camDir = File(imagesDir, cameraId).apply { mkdirs() }
        val f = File(camDir, "$tNs.jpg")
        FileOutputStream(f).use { it.write(jpeg) }
        val n = imageCount.incrementAndGet()
        val rel = "images/$cameraId/${f.name}"
        val p = pose
        frames.write(buildString {
            append(n).append(',').append(trigger).append(',').append(cameraId).append(',').append(tNs).append(',')
            append(rel).append(',').append(w).append(',').append(h).append(',')
            if (p != null) {
                append(p.position.x).append(',').append(p.position.y).append(',').append(p.position.z).append(',')
                append(p.orientation.w).append(',').append(p.orientation.x).append(',')
                append(p.orientation.y).append(',').append(p.orientation.z).append(',')
                append(if (p.stationary) 1 else 0)
            } else append(",,,,,,,,")
            append('\n')
        })
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
    fun close() {
        runCatching { frames.close() }; runCatching { trajectory.close() }; runCatching { meta.close() }
    }

    /** Comprime el escaneo en un .zip junto a la carpeta para compartirlo. */
    fun zip(): File {
        val out = File(dir.parentFile, dir.name + ".zip")
        ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val entry = ZipEntry(dir.name + "/" + f.relativeTo(dir).invariantSeparatorsPath)
                // Los JPEG ya están comprimidos: STORED sería ideal pero exige CRC previo; DEFLATE nivel bajo basta.
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out
    }
}
