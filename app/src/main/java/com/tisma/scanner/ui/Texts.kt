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

/** El autor en «Publicar»: la cuenta, por su nombre público en la biblioteca (nunca el correo). */
fun publishAuthorLine(libraryName: String): String = "Autor: $libraryName (tu cuenta)"

/**
 * El código de una publicación (su id en la biblioteca de Tisma, p2p_library.md 3 quater y 3 novies): se muestra en
 * su ficha y en «Mis publicaciones», y con él se la encuentra o se la bloquea sola, sin tocar las demás de la cuenta.
 */
fun publishedCodeLine(modelId: String): String = "Código de la publicación: $modelId"
