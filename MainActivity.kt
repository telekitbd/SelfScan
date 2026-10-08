package com.scanner.selfscan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var info: TextView
    private val scanner by lazy { BarcodeScanning.getClient() }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else info.text = "ক্যামেরার অনুমতি লাগবে (গ্যালারি ও স্ক্রিন স্ক্যান এমনিতেই চলবে)"
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::scanUri) }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenScanService::class.java).putExtra("code", r.resultCode).putExtra("data", data)
            )
            moveTaskToBack(true) // এখন যেকোনো অ্যাপ/ব্রাউজারে QR খুলে নোটিফিকেশনের "স্ক্যান" চাপুন
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this)
        info = TextView(this).apply { setTextColor(Color.WHITE); text = "QR/বারকোড ক্যামেরার সামনে ধরুন"; setPadding(0, 0, 0, 16) }
        val b1 = Button(this).apply {
            text = "গ্যালারি/স্ক্রিনশট থেকে স্ক্যান"
            setOnClickListener { pickImage.launch("image/*") }
        }
        val b2 = Button(this).apply {
            text = "স্ক্রিন স্ক্যান চালু করুন"
            setOnClickListener {
                projection.launch(getSystemService(Context.MEDIA_PROJECTION_SERVICE).let { (it as MediaProjectionManager).createScreenCaptureIntent() })
            }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(32, 32, 32, 32)
            addView(info); addView(b1); addView(b2)
        }
        setContentView(FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else camPerm.launch(Manifest.permission.CAMERA)

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    // অন্য অ্যাপ থেকে Share → Self Scanner
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("image/") == true) {
            IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let(::scanUri)
        }
    }

    private fun scanUri(uri: Uri) {
        try {
            scanner.process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { list ->
                    val v = list.firstOrNull()?.rawValue
                    if (v != null) onResult(v) else info.text = "ছবিতে QR পাওয়া যায়নি"
                }
        } catch (e: Exception) {
            info.text = "ছবি পড়া যায়নি"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || busy) { proxy.close(); return }
        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { list -> list.firstOrNull()?.rawValue?.let(::onResult) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onResult(text: String) {
        if (busy) return
        busy = true
        ScanRouter.handle(this, text)
        handler.postDelayed({ busy = false }, 2500)
    }
}
