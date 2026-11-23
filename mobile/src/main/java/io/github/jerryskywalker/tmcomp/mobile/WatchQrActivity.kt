package io.github.jerryskywalker.tmcomp.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ViewfinderView

class WatchQrActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.watch_qr_activity)
        findViewById<android.view.View>(R.id.close_watch_scan).setOnClickListener { finish() }
        return findViewById(R.id.watch_qr_scanner)
    }
}

class SquareWatchBarcodeView(context: Context, attrs: AttributeSet) : BarcodeView(context, attrs) {
    override fun calculateFramingRect(container: Rect, surface: Rect): Rect {
        val frame = super.calculateFramingRect(container, surface)
        val size = minOf(frame.width(), frame.height(), (250 * resources.displayMetrics.density).toInt())
        val left = frame.centerX() - size / 2
        val top = frame.centerY() - size / 2
        return Rect(left, top, left + size, top + size)
    }
}

class WatchQrFrame(context: Context, attrs: AttributeSet) : ViewfinderView(context, attrs) {
    private val corner = Path()
    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(157, 231, 198)
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        val frame = framingRect ?: return
        val l = frame.left.toFloat()
        val t = frame.top.toFloat()
        val r = frame.right.toFloat()
        val b = frame.bottom.toFloat()
        val edge = minOf(22 * density, frame.width() * .18f)
        paint.color = 0xb9000b08.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), t, paint)
        canvas.drawRect(0f, t, l, b, paint)
        canvas.drawRect(r, t, width.toFloat(), b, paint)
        canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), paint)
        corner.reset()
        corner.moveTo(l, t + edge)
        corner.lineTo(l, t)
        corner.lineTo(l + edge, t)
        corner.moveTo(r - edge, t)
        corner.lineTo(r, t)
        corner.lineTo(r, t + edge)
        corner.moveTo(r, b - edge)
        corner.lineTo(r, b)
        corner.lineTo(r - edge, b)
        corner.moveTo(l + edge, b)
        corner.lineTo(l, b)
        corner.lineTo(l, b - edge)
        canvas.drawPath(corner, border)
    }

    override fun addPossibleResultPoint(point: com.google.zxing.ResultPoint) = Unit
}
