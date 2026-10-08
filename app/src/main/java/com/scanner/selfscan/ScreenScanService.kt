package com.scanner.selfscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/** স্ক্রিনের ছবি ধরে রাখে; নোটিফিকেশনের "স্ক্যান" চাপলে সেই ছবি থেকে QR পড়ে। */
class ScreenScanService : Service() {

    companion object {
        @Volatile var instance: ScreenScanService? = null
        private const val CH = "selfscan"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var last: Image? = null
    private var overlay: Button? = null
    private var wm: WindowManager? = null
    private val h = Handler(Looper.getMainLooper())
    private val scanner by lazy { BarcodeScanning.getClient() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "STOP") { cleanup(); stopSelf(); return START_NOT_STICKY }
        val code = i?.getIntExtra("code", 0) ?: 0
        val data = i?.let { IntentCompat.getParcelableExtra(it, "data", Intent::class.java) }
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        goForeground()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { cleanup(); stopSelf() }
        }, h)

        val m = resources.displayMetrics
        val r = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val im = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            last?.close()
            last = im
        }, h)
        display = p.createVirtualDisplay(
            "selfscan", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )
        reader = r
        projection = p
        instance = this
        addOverlay()
        Toast.makeText(this, "স্ক্রিন স্ক্যান চালু। QR খুলে ভাসমান 'স্ক্যান' বাটন চাপুন (বন্ধ করতে বাটনে লং প্রেস)", Toast.LENGTH_LONG).show()
        return START_NOT_STICKY
    }

    // অন্য অ্যাপের উপরে ভাসমান বাটন: চাপলে স্ক্রিনের QR স্ক্যান হয়
    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "স্ক্যান"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFF4C1D95.toInt()); cornerRadius = 80f }
            setOnClickListener { scanFromOverlay() }
            setOnLongClickListener { cleanup(); stopSelf(); true }
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        w.addView(b, lp)
        wm = w
        overlay = b
    }

    private fun scanFromOverlay() {
        overlay?.visibility = View.INVISIBLE   // বাটনটা ছবিতে না আসার জন্য লুকানো
        h.postDelayed({
            capture { text ->
                overlay?.visibility = View.VISIBLE
                if (text != null) ScanRouter.handle(this, text)
                else Toast.makeText(this, "স্ক্রিনে QR পাওয়া যায়নি", Toast.LENGTH_LONG).show()
            }
        }, 500)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Screen scan", NotificationManager.IMPORTANCE_LOW))
        val scanPi = PendingIntent.getActivity(this, 1, Intent(this, ScanTriggerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 2, Intent(this, ScreenScanService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("স্ক্রিন স্ক্যানার চালু")
            .setContentText("QR খোলা রেখে এখানে চাপুন")
            .setContentIntent(scanPi)
            .addAction(android.R.drawable.ic_menu_search, "স্ক্যান", scanPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বন্ধ", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    fun capture(cb: (String?) -> Unit) {
        val img = last
        if (img == null) { cb(null); return }
        val bmp = try {
            val pl = img.planes[0]
            val w = pl.rowStride / pl.pixelStride
            val full = Bitmap.createBitmap(w, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(pl.buffer)
            Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        } catch (e: Exception) { cb(null); return }
        scanner.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cb(it.firstOrNull()?.rawValue) }
            .addOnFailureListener { cb(null) }
    }

    private fun cleanup() {
        instance = null
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlay = null
        last?.close(); last = null
        display?.release(); display = null
        reader?.close(); reader = null
        val p = projection; projection = null
        p?.stop()
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }
}
