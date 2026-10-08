package com.scanner.selfscan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
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
    private var cameraStarted = false
    private var cameraWanted = false

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else info.text = "ক্যামেরার অনুমতি দেওয়া নেই (শুধু ক্যামেরা স্ক্যানের জন্য লাগে, সেলফ স্ক্যান এমনিতেই চলবে)"
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
            moveTaskToBack(true) // এখন QR খোলা অ্যাপ/ব্রাউজারে গিয়ে নোটিফিকেশনের "স্ক্যান" চাপুন
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        info = TextView(this).apply {
            textSize = 16f
            setPadding(pad, pad, pad, pad)
            text = "সেলফ স্ক্যান: ফোনের স্ক্রিনে বা গ্যালারিতে থাকা QR এখান থেকেই স্ক্যান হবে।"
        }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.GONE
        }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(btn("১. স্ক্রিন স্ক্যান চালু করুন (সরাসরি স্ক্রিন থেকে)") {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    info.text = "প্রথমে 'অন্য অ্যাপের উপরে দেখানো' অনুমতি চালু করুন, তারপর ফিরে এসে আবার এই বাটন চাপুন"
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection.launch(mpm.createScreenCaptureIntent())
                }
            })
            addView(btn("২. গ্যালারি/স্ক্রিনশট থেকে স্ক্যান") { pickImage.launch("image/*") })
            addView(btn("৩. ক্যামেরা দিয়ে স্ক্যান (ঐচ্ছিক)") {
                cameraWanted = true
                preview.visibility = View.VISIBLE
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    startCamera()
                else camPerm.launch(Manifest.permission.CAMERA)
            })
            addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        if (cameraWanted && !cameraStarted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) startCamera()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    // অন্য অ্যাপ থেকে ছবি Share → Self Scanner
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
                    if (v != null) onResult(v) else info.text = "এই ছবিতে কোনো QR পাওয়া যায়নি"
                }
                .addOnFailureListener { info.text = "ছবি স্ক্যান করা যায়নি" }
        } catch (e: Exception) {
            info.text = "ছবি পড়া যায়নি"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> { info.text = "এই ফোনে ক্যামেরা পাওয়া যায়নি"; return@addListener }
                }
                val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { analyze(it) }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, prev, analysis)
                cameraStarted = true
                info.text = "ক্যামেরা চালু। QR সামনে ধরুন"
            } catch (e: Exception) {
                info.text = "ক্যামেরা চালু হয়নি: ${e.javaClass.simpleName} ${e.message ?: ""}"
            }
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

    // QR এর ধরন বলে (গোপন অংশ দেখায় না)
    private fun describe(t: String): String {
        val s = t.trim()
        return when {
            s.startsWith("app:", true) -> "অ্যাপ লিংক"
            s.startsWith("http", true) -> "ওয়েব লিংক"
            else -> Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):").find(s)?.groupValues?.get(1)?.let { "$it:// লিংক" } ?: "সাধারণ লেখা"
        }
    }

    private fun onResult(text: String) {
        if (busy) return
        busy = true
        info.text = "QR পাওয়া গেছে (${describe(text)}), খোলার চেষ্টা চলছে…"
        ScanRouter.handle(this, text)
        handler.postDelayed({ busy = false }, 2500)
    }
}
