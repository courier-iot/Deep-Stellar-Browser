package dev.deepstellar.browser.ui

import dev.deepstellar.browser.AppLock
import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.deepstellar.browser.MainActivity
import dev.deepstellar.browser.R
import dev.deepstellar.browser.qr.QrCodec
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR share & scan.
 *  - Share: shows the current page as a QR code for another device. No sync account needed.
 *  - Scan: camera with continuous autofocus, tap-to-focus, pinch or slider zoom, and torch.
 *    Decoding happens on the phone (ZXing) — nothing is uploaded.
 */
class QrActivity : AppCompatActivity() {

    private lateinit var shareView: View
    private lateinit var scanView: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var focusRing: View
    private lateinit var zoomBar: SeekBar
    private lateinit var torchBtn: MaterialButton
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var analyzer: ExecutorService? = null
    private val handled = AtomicBoolean(false)
    private var torchOn = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            toast("Camera permission is needed to scan")
            if (intent.getStringExtra(EXTRA_TEXT) == null) finish() else showShare()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        Thread {
            val text = runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (opts.outWidth / sample > 2000 || opts.outHeight / sample > 2000) sample *= 2
                val bmp = contentResolver.openInputStream(uri)!!.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                bmp?.let { QrCodec.decodeBitmap(it) }
            }.getOrNull()
            runOnUiThread { if (text != null) onDecoded(text) else toast("No QR code found in that image") }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        shareView = buildShareView(intent.getStringExtra(EXTRA_TEXT))
        scanView = buildScanView()
        root.addView(shareView)
        root.addView(scanView)
        val bar = scaffold("QR code", root)
        bar.menu.add("Scan from image").setOnMenuItemClickListener { AppLock.expectExternal(); pickImage.launch("image/*"); true }

        if (intent.getBooleanExtra(EXTRA_SCAN, false) || intent.getStringExtra(EXTRA_TEXT) == null) showScan() else showShare()
    }

    override fun onDestroy() {
        analyzer?.shutdownNow()
        super.onDestroy()
    }

    // ================================================================ share

    private fun buildShareView(text: String?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24))
        }
        if (text != null) {
            val size = (resources.displayMetrics.widthPixels * 0.72f).toInt().coerceAtMost(Ui.dp(this, 340))
            val card = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    cornerRadius = Ui.dp(context, 22).toFloat()
                    setStroke(Ui.dp(context, 2), Ui.color(context, R.color.nebula_violet))
                }
                val pad = Ui.dp(context, 14)
                setPadding(pad, pad, pad, pad)
            }
            val img = ImageView(this)
            runCatching { QrCodec.encode(text, size) }
                .onSuccess { img.setImageBitmap(it) }
                .onFailure { toast("This address is too long for a QR code") }
            card.addView(img, FrameLayout.LayoutParams(size, size))
            box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@QrActivity, 12)
            })
            box.addView(TextView(this).apply {
                this.text = text
                setTextColor(Ui.color(context, R.color.starlight_dim))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
                maxLines = 4
                setPadding(0, Ui.dp(context, 18), 0, Ui.dp(context, 18))
            })
            box.addView(TextView(this).apply {
                this.text = "Point another device's camera here. No account, no sync server."
                setTextColor(Ui.color(context, R.color.starlight_dim))
                textSize = 12f
                gravity = Gravity.CENTER
            })
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(context, 20), 0, 0)
            }
            buttons.addView(button("Copy link") { copy(text) })
            buttons.addView(button("Scan a code") { showScan() })
            box.addView(buttons)
        }
        return android.widget.ScrollView(this).apply { addView(box) }
    }

    private fun button(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ui.dp(context, 6); marginEnd = Ui.dp(context, 6)
        }
    }

    private fun showShare() {
        stopCamera()
        scanView.visibility = View.GONE
        shareView.visibility = View.VISIBLE
    }

    // ================================================================ scan

    @SuppressLint("ClickableViewAccessibility")
    private fun buildScanView(): FrameLayout {
        val frame = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        frame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Viewfinder square
        val vf = Ui.dp(this, 240)
        frame.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = Ui.dp(context, 20).toFloat()
                setStroke(Ui.dp(context, 2), Ui.color(context, R.color.cosmic_blue))
            }
        }, FrameLayout.LayoutParams(vf, vf, Gravity.CENTER))

        // Focus ring shown where you tap
        focusRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(Ui.dp(context, 2), Ui.color(context, R.color.nebula_violet))
            }
            alpha = 0f
        }
        frame.addView(focusRing, FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)))

        // Bottom controls: hint, zoom slider, torch, back to code
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 14))
        }
        controls.addView(TextView(this).apply {
            text = "Tap to focus · pinch or slide to zoom"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        zoomBar = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) camera?.cameraControl?.setLinearZoom(p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        controls.addView(zoomBar)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        torchBtn = button("Torch off") { toggleTorch() }
        row.addView(torchBtn)
        if (intent.getStringExtra(EXTRA_TEXT) != null) row.addView(button("Show my code") { showShare() })
        controls.addView(row)
        frame.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Gestures: pinch zoom + tap to focus
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                val ratio = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                cam.cameraInfo.zoomState.value?.let { zoomBar.progress = (it.linearZoom * 100).toInt() }
                return true
            }
        })
        previewView.setOnTouchListener { v, ev ->
            scale.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP && !scale.isInProgress && ev.pointerCount == 1) {
                focusAt(ev.x, ev.y)
                v.performClick()
            }
            true
        }
        return frame
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS) // then back to continuous autofocus
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        val half = focusRing.layoutParams.width / 2f
        focusRing.translationX = x - half
        focusRing.translationY = y - half
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f; focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(220).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(700).setDuration(400).start()
        }.start()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) { toast("No flashlight on this camera"); return }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchBtn.text = if (torchOn) "Torch on" else "Torch off"
    }

    private fun showScan() {
        shareView.visibility = View.GONE
        scanView.visibility = View.VISIBLE
        handled.set(false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            AppLock.expectExternal(); cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: run { toast("Camera unavailable"); return@addListener }
            provider = p
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val exec = analyzer ?: Executors.newSingleThreadExecutor().also { analyzer = it }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(exec) { image ->
                try {
                    if (!handled.get()) {
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val rowStride = plane.rowStride
                        val data = ByteArray(rowStride * image.height)
                        buf.rewind()
                        buf.get(data, 0, minOf(buf.remaining(), data.size))
                        val text = QrCodec.decodeLuma(data, rowStride, image.width, image.height)
                        if (text != null && handled.compareAndSet(false, true)) {
                            runOnUiThread { onDecoded(text) }
                        }
                    }
                } catch (e: Exception) {
                    // a bad frame — skip it
                } finally {
                    image.close()
                }
            }
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                zoomBar.progress = 0
                torchOn = false
                torchBtn.text = "Torch off"
            } catch (e: Exception) {
                toast("Couldn't start the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        runCatching { provider?.unbindAll() }
        camera = null
    }

    // ================================================================ result

    private fun onDecoded(text: String) {
        stopCamera()
        val trimmed = text.trim()
        val isMagnet = trimmed.startsWith("magnet:", ignoreCase = true)
        val isUrl = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) ||
            (!trimmed.contains(' ') && Patterns.WEB_URL.matcher(trimmed).matches())
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(when { isMagnet -> "Magnet link"; isUrl -> "Link"; else -> "Text" })
            .setMessage(trimmed.take(1200))
            .setNeutralButton("Copy") { _, _ -> copy(trimmed); handled.set(false); showScan() }
            .setNegativeButton("Scan again") { _, _ -> showScan() }
            .setOnCancelListener { showScan() }
        when {
            isMagnet -> b.setPositiveButton("Start torrent") { _, _ -> openInBrowser(Uri.parse(trimmed)) }
            isUrl -> b.setPositiveButton("Open") { _, _ ->
                openInBrowser(Uri.parse(if (trimmed.contains("://")) trimmed else "https://$trimmed"))
            }
            else -> b.setPositiveButton("Search") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, trimmed))
                finish()
            }
        }
        b.show()
    }

    private fun openInBrowser(uri: Uri) {
        // Opened inside Deep Stellar (never handed to another app).
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri))
        finish()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("qr", text))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_SCAN = "scan"
    }
}
