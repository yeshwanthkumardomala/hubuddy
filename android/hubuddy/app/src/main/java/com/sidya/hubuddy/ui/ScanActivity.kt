package com.sidya.hubuddy.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.sidya.hubuddy.net.Invitation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the QR code shown on the PC. Uses the framework camera and a small QR decoder, so there is no Google Play
 * Services dependency and it works on any Android 5.0+ phone with a camera. If there is no camera, or permission is
 * refused, it says so and the caller offers pasting the invitation instead. The invitation is validated by the caller.
 */
@Suppress("DEPRECATION")
class ScanActivity : Activity(), SurfaceHolder.Callback, Camera.PreviewCallback {
    private var camera: Camera? = null
    private lateinit var surface: SurfaceView
    private lateinit var hint: TextView
    private val busy = AtomicBoolean(false)
    private val done = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private var previewW = 0
    private var previewH = 0
    private var lastTry = 0L
    private var surfaceReady = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        hint = TextView(this).apply {
            text = "Point the camera at the QR code shown by Hubuddy on your PC."
            setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); textSize = 15f; setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER; tag = "scanHint"
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val cancel = Button(this).apply { text = "Cancel"; tag = "scanCancel"; setOnClickListener { finish() } }
        root.addView(cancel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 48 })
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == REQ_CAMERA) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (surfaceReady) start() }
            else problem("Camera permission was refused. Go back and choose Paste invitation, or Find PCs.")
        }
    }

    private fun problem(msg: String) { hint.text = msg; hint.setBackgroundColor(0xCC8A3B00.toInt()) }

    private fun hasPermission() = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true; if (hasPermission()) start() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stop() }

    private fun start() {
        if (camera != null) return
        try {
            val cam = Camera.open() ?: run { problem("This phone has no usable camera. Go back and choose Paste invitation."); return }
            camera = cam
            val params = cam.parameters
            val best = params.supportedPreviewSizes.orEmpty().filter { it.width <= 1280 && it.height <= 960 }.maxByOrNull { it.width * it.height }
            if (best != null) params.setPreviewSize(best.width, best.height)
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            cam.parameters = params
            previewW = cam.parameters.previewSize.width; previewH = cam.parameters.previewSize.height
            cam.setDisplayOrientation(displayDegrees())
            cam.setPreviewDisplay(surface.holder)
            cam.setPreviewCallback(this)
            cam.startPreview()
        } catch (e: Exception) {
            stop()
            problem("The camera could not be opened. Go back and choose Paste invitation.")
        }
    }

    private fun displayDegrees(): Int {
        val info = Camera.CameraInfo().also { Camera.getCameraInfo(0, it) }
        val rot = when (windowManager.defaultDisplay.rotation) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
        return (info.orientation - rot + 360) % 360
    }

    private fun stop() {
        try { camera?.setPreviewCallback(null); camera?.stopPreview() } catch (e: Exception) { }
        try { camera?.release() } catch (e: Exception) { }
        camera = null
    }

    override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
        val now = System.currentTimeMillis()
        if (data == null || done.get() || now - lastTry < 250 || !busy.compareAndSet(false, true)) return
        lastTry = now
        val frame = data.copyOf()
        worker.execute {
            try {
                val text = decode(frame, previewW, previewH)
                if (text != null && text.startsWith(Invitation.PREFIX) && done.compareAndSet(false, true)) {
                    runOnUiThread {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                        finish()
                    }
                } else if (text != null) runOnUiThread { hint.text = "That QR code is not a Hubuddy invitation. Use the one on the PC's Hubuddy page." }
            } finally { busy.set(false) }
        }
    }

    override fun onDestroy() { stop(); worker.shutdownNow(); super.onDestroy() }

    companion object {
        const val EXTRA_TEXT = "text"
        private const val REQ_CAMERA = 71

        /** Decodes a QR code from an NV21 camera frame. Also used by the unit tests on synthetic images. */
        fun decode(nv21: ByteArray, w: Int, h: Int): String? = try {
            val src = PlanarYUVLuminanceSource(nv21, w, h, 0, 0, w, h, false)
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (e: Exception) { null }
    }
}
