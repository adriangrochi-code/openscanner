package com.tisma.scanner.recon

import com.tisma.scanner.ins.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VisualCenterTest {
    @Test fun knownRotationRecoversCenterFromPoints() {
        val r = Random(1)
        val truth = LivePointCloud.View(Vec3(0.0, -1.0, 0.0), Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0), Vec3(-0.25, 0.0, 0.0), 280.0, 160.0, 120.0)
        val pts = List(60) { Vec3(r.nextDouble(-0.03, 0.03), r.nextDouble(-0.03, 0.03), r.nextDouble(-0.03, 0.03)) }
        val obs = pts.map { LivePointCloud.Ref(it, truth.project(it)!!) }
        val guess = LivePointCloud.View(truth.xAxis, truth.yAxis, truth.zAxis, truth.center + Vec3(0.004, 0.006, -0.003), 280.0, 160.0, 120.0)
        val c = LivePointCloud().visualCenter(guess, obs)!!
        println("centro: error %.3f mm (partía de 7.8 mm)".format((c - truth.center).norm() * 1000))
        assertTrue((c - truth.center).norm() < 0.0005)
    }
}
