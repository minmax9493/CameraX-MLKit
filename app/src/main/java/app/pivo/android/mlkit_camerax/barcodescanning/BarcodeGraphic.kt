package app.pivo.android.mlkit_camerax.barcodescanning

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import app.pivo.android.mlkit_camerax.common.GraphicOverlay
import com.google.android.gms.vision.CameraSource
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode

class BarcodeGraphic(overlay: GraphicOverlay, private val barcode: FirebaseVisionBarcode, private val facing:Int, private val rotation:Int) : GraphicOverlay.Graphic(overlay) {

    private var rectPaint = Paint().apply {
        color = TEXT_COLOR
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
    }

    private var barcodePaint = Paint().apply {
        color = TEXT_COLOR
        textSize = TEXT_SIZE
    }

    /**
     * Draws the barcode block annotations for position, size, and raw value on the supplied canvas.
     */
    override fun draw(canvas: Canvas) {
        // Draws the bounding box around the BarcodeBlock.
//        val rect = RectF(barcode.boundingBox)
//
//        rect.left = translateX2(rect.left)
//        rect.top = translateY2(rect.top)
//        rect.right = translateX2(rect.right)
//        rect.bottom = translateY2(rect.bottom)

//        canvas.drawRect(rect, rectPaint)
//
//        // Renders the barcode at the bottom of the box.
//        barcode.rawValue?.let { value ->
//            canvas.drawText(value, rect.left, rect.bottom, barcodePaint)
//        }

        var x = 0f
        var y = 0f

        if (rotation == 0) { // 9 o'clock, back and front camera
            x = translateX2(barcode.boundingBox?.centerY()!!.toFloat())
            y = translateY2(barcode.boundingBox?.centerX()!!.toFloat())
        } else if (rotation == 1 && facing == CameraSource.CAMERA_FACING_BACK) { // 12 o'clock, back camera
            x = translateX(barcode.boundingBox?.centerX()!!.toFloat())
            y = translateY(barcode.boundingBox?.centerY()!!.toFloat())
        } else if (rotation == 2) { //3 o'clock, back and front camera
            x = translateX3(barcode.boundingBox?.centerY()!!.toFloat())
            y = translateY3(barcode.boundingBox?.centerX()!!.toFloat())
        } else if (rotation == 3 && facing == CameraSource.CAMERA_FACING_FRONT) { // 12 o'clock, front camera
            x = translateX(barcode.boundingBox?.centerX()!!.toFloat())
            y = translateY(barcode.boundingBox?.centerY()!!.toFloat())
        }
        val xOffset = scaleX(barcode.boundingBox?.width()!!.toFloat() / 2.0f)
        val yOffset = scaleY(barcode.boundingBox?.height()!!.toFloat() / 2.0f)

        val left = x - xOffset
        val top = y - yOffset
        val right = x + xOffset
        val bottom = y + yOffset

        val rect = RectF(left, top, right, bottom)

        canvas.drawRect(rect, rectPaint)

        // Renders the barcode at the bottom of the box.
        barcode.rawValue?.let { value ->
            canvas.drawText(value, rect.left, rect.bottom, barcodePaint)
        }
    }

    companion object {
        private const val TEXT_COLOR = Color.WHITE
        private const val TEXT_SIZE = 54.0f
        private const val STROKE_WIDTH = 4.0f
    }
}