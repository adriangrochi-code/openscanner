package com.tisma.scanner.ui

import android.content.SharedPreferences

/**
 * Los ajustes de captura que la persona eligió, guardados entre usos de la app. Antes sólo vivían en memoria: al
 * volver a abrir la app, el modo (Vídeo 360/Fotos), HDR, disparo automático, nitidez, IA, calidad JPEG… volvían a
 * los de fábrica. La linterna no se guarda (encenderla sola al abrir sería una sorpresa).
 */
object SavedSettings {
    private val BOOLS: List<Triple<String, (UiState) -> Boolean, (UiState, Boolean) -> UiState>> = listOf(
        Triple("set_video_mode", { it.videoMode }, { s, v -> s.copy(videoMode = v) }),
        Triple("set_hdr", { it.hdr }, { s, v -> s.copy(hdr = v) }),
        Triple("set_auto_trigger", { it.autoTrigger }, { s, v -> s.copy(autoTrigger = v) }),
        Triple("set_lock_3a", { it.lock3A }, { s, v -> s.copy(lock3A = v) }),
        Triple("set_discard_blurry", { it.discardBlurry }, { s, v -> s.copy(discardBlurry = v) }),
        Triple("set_deblur_motion", { it.deblurMotion }, { s, v -> s.copy(deblurMotion = v) }),
        Triple("set_use_depth_ai", { it.useDepthAi }, { s, v -> s.copy(useDepthAi = v) }),
        Triple("set_anti_shake_burst", { it.antiShakeBurst }, { s, v -> s.copy(antiShakeBurst = v) }),
        Triple("set_stabilize_preview", { it.stabilizePreview }, { s, v -> s.copy(stabilizePreview = v) }),
        Triple("set_rotate_cameras", { it.rotateCameras }, { s, v -> s.copy(rotateCameras = v) }),
        Triple("set_use_macro", { it.useMacro }, { s, v -> s.copy(useMacro = v) }),
        Triple("set_use_concurrent", { it.useConcurrent }, { s, v -> s.copy(useConcurrent = v) }),
        Triple("set_camera_velocity", { it.cameraVelocity }, { s, v -> s.copy(cameraVelocity = v) }),
        Triple("set_focus_depth", { it.focusDepth }, { s, v -> s.copy(focusDepth = v) }),
    )
    private val INTS: List<Triple<String, (UiState) -> Int, (UiState, Int) -> UiState>> = listOf(
        Triple("set_jpeg_quality", { it.jpegQuality }, { s, v -> s.copy(jpegQuality = v.coerceIn(50, 100)) }),
        Triple("set_part_depth_cm", { it.partDepthCm }, { s, v -> s.copy(partDepthCm = v.coerceIn(1, 30)) }),
        Triple("set_drift_minutes", { it.driftMinutes }, { s, v -> s.copy(driftMinutes = v.coerceIn(1, 30)) }),
    )

    /** El estado con los ajustes guardados (los que no estén guardados quedan como en `s`). */
    fun load(p: SharedPreferences, s: UiState): UiState {
        var out = s
        for ((key, get, set) in BOOLS) out = set(out, p.getBoolean(key, get(s)))
        for ((key, get, set) in INTS) out = set(out, p.getInt(key, get(s)))
        return out
    }

    /** Lo que cuenta como «los ajustes»: si cambia, se guardan. */
    fun of(s: UiState): List<Any> = BOOLS.map { it.second(s) } + INTS.map { it.second(s) }

    fun save(p: SharedPreferences, s: UiState) {
        val e = p.edit()
        for ((key, get, _) in BOOLS) e.putBoolean(key, get(s))
        for ((key, get, _) in INTS) e.putInt(key, get(s))
        e.apply()
    }
}
