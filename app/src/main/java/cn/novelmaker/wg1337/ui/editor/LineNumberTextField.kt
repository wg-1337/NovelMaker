package cn.novelmaker.wg1337.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LineNumberTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Float = 16f,
    lineSpacing: Float = 5f,
    onFontSizeChange: ((Float) -> Unit)? = null,
    minFontSize: Float = 10f,
    maxFontSize: Float = 32f,
    readOnly: Boolean = false,
    gutterWidth: Dp = 44.dp
) {
    val density = LocalDensity.current
    val gutterWidthPx = with(density) { gutterWidth.toPx() }

    val textStyle = TextStyle(
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = (fontSize + lineSpacing).sp,
        color = MaterialTheme.colorScheme.onSurface
    )

    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val zoomModifier = if (onFontSizeChange != null) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                val newSize = (fontSize * zoom).coerceIn(minFontSize, maxFontSize)
                onFontSizeChange(newSize)
            }
        }
    } else Modifier

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(zoomModifier)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = gutterWidth + 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
            .drawWithContent {
                drawContent()
                val layout = textLayoutResult ?: return@drawWithContent
                val lineCount = layout.lineCount
                if (lineCount == 0) return@drawWithContent

                drawLine(dividerColor, Offset(gutterWidthPx, 0f), Offset(gutterWidthPx, size.height), 1f)

                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = with(density) { fontSize.sp.toPx() }
                    typeface = android.graphics.Typeface.MONOSPACE
                    textAlign = android.graphics.Paint.Align.RIGHT
                    color = android.graphics.Color.argb(
                        (gutterColor.alpha * 255).toInt(), (gutterColor.red * 255).toInt(),
                        (gutterColor.green * 255).toInt(), (gutterColor.blue * 255).toInt()
                    )
                }

                val lineNumX = gutterWidthPx - 8f
                var firstLine = 0; var lastLine = lineCount - 1
                for (i in 0 until lineCount) { if (layout.getLineBottom(i) > 0f) { firstLine = i; break } }
                for (i in lineCount - 1 downTo 0) { if (layout.getLineTop(i) < size.height) { lastLine = i; break } }

                for (line in firstLine..lastLine) {
                    drawContext.canvas.nativeCanvas.drawText("${line + 1}", lineNumX, layout.getLineBaseline(line), paint)
                }
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        readOnly = readOnly,
        onTextLayout = { result -> textLayoutResult = result }
    )
}
