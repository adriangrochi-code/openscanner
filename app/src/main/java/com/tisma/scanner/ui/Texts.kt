package com.tisma.scanner.ui

/** «1 foto», «2 fotos», «0 fotos»: un número con su palabra en singular o plural. */
fun count(n: Int, one: String, many: String): String = "$n " + if (n == 1) one else many

/**
 * Cómo se manda un escaneo a Tisma Cloud, según el modo: en Vídeo 360 (el de siempre) no hay botón ☁ Enviar en la
 * cámara, se manda desde «Tu pieza»; en Fotos, con ☁ Enviar.
 */
fun sendHint(videoMode: Boolean): String =
    if (videoMode) "Haz un escaneo y, en «Tu pieza», toca «Crear modelo final en Tisma Cloud»."
    else "Haz un escaneo y pulsa ☁ Enviar en la pantalla de la cámara."
