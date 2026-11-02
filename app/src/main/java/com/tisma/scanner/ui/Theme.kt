package com.tisma.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta de TISMA: grises oscuros con acento violeta. */
object Tisma {
    val Bar = Color(0xFF1E1E22)          // barra superior / barra de estado
    val Bg = Color(0xFF26262B)           // fondo de la vista (como el visor 3D)
    val Panel = Color(0xFF2C2C31)        // panel de ajustes
    val Field = Color(0xFF3A3A40)        // campos de valor / chips
    val Divider = Color(0xFF3F3F46)
    val Purple = Color(0xFF8B2FE0)       // botón principal (Slice), pestaña activa
    val PurpleDim = Color(0xFF4E2577)    // fondo de pestaña seleccionada
    val PurpleText = Color(0xFFB36BFF)   // títulos de sección
    val InfoToast = Color(0xFF5B1FA8)
    val ErrorToast = Color(0xFF7D1F2E)
    val Ok = Color(0xFF5BD47A)
    val Warn = Color(0xFFF2B84B)
    val Text = Color(0xFFE8E8EC)
    val TextDim = Color(0xFF9C9CA6)
    val TextOff = Color(0xFF66666F)
}

@Composable
fun TismaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tisma.Purple,
            onPrimary = Color.White,
            secondary = Tisma.PurpleText,
            background = Tisma.Bg,
            onBackground = Tisma.Text,
            surface = Tisma.Panel,
            onSurface = Tisma.Text,
            surfaceVariant = Tisma.Field,
            onSurfaceVariant = Tisma.TextDim,
            outline = Tisma.Divider,
            error = Tisma.ErrorToast,
            secondaryContainer = Tisma.PurpleDim,
            onSecondaryContainer = Color.White,
        ),
        content = content,
    )
}
