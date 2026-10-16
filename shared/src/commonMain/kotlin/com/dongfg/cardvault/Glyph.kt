package com.dongfg.cardvault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun Glyph(kind: String, label: String, color: Color = VaultMuted) {
    Canvas(Modifier.size(24.dp).semantics { contentDescription = label }) {
        val u = size.width / 24
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1*u,y1*u), Offset(x2*u,y2*u), 1.7f*u)
        when(kind) {
            "search" -> { drawCircle(color, 6.5f*u, Offset(10.5f*u,10.5f*u), style = Stroke(1.7f*u)); line(15f,15f,21f,21f) }
            "plus" -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            "close" -> { line(6f,6f,18f,18f); line(6f,18f,18f,6f) }
            "back" -> { line(15f,5f,8f,12f); line(8f,12f,15f,19f) }
            "min" -> line(5f,16f,19f,16f)
            "max" -> drawRect(color, Offset(6*u,6*u), Size(12*u,12*u), style = Stroke(1.4f*u))
        }
    }
}

@Composable
fun VaultMark(large: Boolean = false) {
    Canvas(Modifier.size(if (large) 48.dp else 30.dp)) {
        val u = size.width / 32
        drawRoundRect(VaultGreen.copy(alpha = .18f), Offset(3*u,3*u), Size(23*u,17*u), CornerRadius(3*u))
        drawRoundRect(VaultGreen, Offset(7*u,10*u), Size(23*u,17*u), CornerRadius(3*u), style = Stroke(1.6f*u))
        drawLine(VaultGreen, Offset(8*u,16*u), Offset(29*u,16*u), 1.6f*u)
        drawLine(VaultGreen, Offset(12*u,22*u), Offset(18*u,22*u), 1.6f*u)
    }
}
