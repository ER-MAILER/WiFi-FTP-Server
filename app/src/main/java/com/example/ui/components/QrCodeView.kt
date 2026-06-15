package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    qrColor: Color = Color.Black,
    backgroundColor: Color = Color.White
) {
    val matrix = remember(content) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 200, 200)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val array = Array(width) { BooleanArray(height) }
            for (x in 0 until width) {
                for (y in 0 until height) {
                    array[x][y] = bitMatrix.get(x, y)
                }
            }
            array
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(16.dp)
            .testTag("qr_code_container")
    ) {
        if (matrix != null) {
            Canvas(modifier = Modifier.fillMaxSize().testTag("qr_code_canvas")) {
                val matrixSize = matrix.size
                val cellWidth = size.width / matrixSize
                val cellHeight = size.height / matrixSize

                // Draw background manually to prevent margins bleeding
                drawRect(
                    color = backgroundColor,
                    topLeft = Offset.Zero,
                    size = size
                )

                for (x in 0 until matrixSize) {
                    for (y in 0 until matrixSize) {
                        if (matrix[x][y]) {
                            // Slightly overlap coordinates by 1 pixel to remove grid spacing lines
                            drawRect(
                                color = qrColor,
                                topLeft = Offset(x * cellWidth, y * cellHeight),
                                size = Size(cellWidth + 0.5f, cellHeight + 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}
