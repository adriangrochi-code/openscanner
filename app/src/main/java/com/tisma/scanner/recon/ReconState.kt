package com.tisma.scanner.recon

import com.tisma.scanner.ins.Vec3
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Vista guardada para la IA de profundidad: pose corregida, foto pequeña (JPEG) y puntos medidos (u, v, m). */
class DepthShot(val view: LivePointCloud.View, val jpeg: ByteArray, val samples: List<Triple<Double, Double, Double>>)

/**
 * Lo reconstruido en vivo, en disco (recon_state.bin): si Android cierra la app mientras se
 * procesa el escaneo, al volver se rehacen las mallas desde aquí (la memoria se perdió).
 */
object ReconState {
    private const val VERSION = 1

    fun save(f: File, fragments: List<LivePointCloud.Fragment>, shots: List<DepthShot>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(VERSION)
            o.writeInt(fragments.size)
            for (fr in fragments) {
                o.writeInt(fr.id); floats(o, fr.xyz); floats(o, fr.uv); ints(o, fr.tris)
                o.writeBoolean(fr.thumb != null); fr.thumb?.let { ints(o, it) }
                o.writeInt(fr.tw); o.writeInt(fr.th); vec(o, fr.viewDir)
                o.writeBoolean(fr.view != null); fr.view?.let { view(o, it) }
                o.writeInt(fr.gw); o.writeInt(fr.gh)
            }
            o.writeInt(shots.size)
            for (s in shots) {
                view(o, s.view); o.writeInt(s.jpeg.size); o.write(s.jpeg)
                o.writeInt(s.samples.size)
                for ((u, v, d) in s.samples) { o.writeDouble(u); o.writeDouble(v); o.writeDouble(d) }
            }
        }
        tmp.renameTo(f)
    }

    fun load(f: File): Pair<List<LivePointCloud.Fragment>, List<DepthShot>>? = runCatching {
        DataInputStream(f.inputStream().buffered(1 shl 16)).use { i ->
            if (i.readInt() != VERSION) return null
            val frs = List(i.readInt()) {
                val id = i.readInt(); val xyz = floats(i); val uv = floats(i); val tris = ints(i)
                val thumb = if (i.readBoolean()) ints(i) else null
                val tw = i.readInt(); val th = i.readInt(); val vd = vec(i)
                val v = if (i.readBoolean()) view(i) else null
                LivePointCloud.Fragment(id, xyz, uv, tris, thumb, tw, th, vd, v, i.readInt(), i.readInt())
            }
            val shots = List(i.readInt()) {
                val v = view(i)
                val jpeg = ByteArray(i.readInt()).also { i.readFully(it) }
                DepthShot(v, jpeg, List(i.readInt()) { Triple(i.readDouble(), i.readDouble(), i.readDouble()) })
            }
            frs to shots
        }
    }.getOrNull()

    private fun floats(o: DataOutputStream, a: FloatArray) { o.writeInt(a.size); for (x in a) o.writeFloat(x) }
    private fun ints(o: DataOutputStream, a: IntArray) { o.writeInt(a.size); for (x in a) o.writeInt(x) }
    private fun vec(o: DataOutputStream, v: Vec3) { o.writeDouble(v.x); o.writeDouble(v.y); o.writeDouble(v.z) }
    private fun view(o: DataOutputStream, v: LivePointCloud.View) {
        vec(o, v.xAxis); vec(o, v.yAxis); vec(o, v.zAxis); vec(o, v.center); o.writeDouble(v.f); o.writeDouble(v.cx); o.writeDouble(v.cy)
    }
    private fun floats(i: DataInputStream) = FloatArray(i.readInt()) { i.readFloat() }
    private fun ints(i: DataInputStream) = IntArray(i.readInt()) { i.readInt() }
    private fun vec(i: DataInputStream) = Vec3(i.readDouble(), i.readDouble(), i.readDouble())
    private fun view(i: DataInputStream) = LivePointCloud.View(vec(i), vec(i), vec(i), vec(i), i.readDouble(), i.readDouble(), i.readDouble())
}
