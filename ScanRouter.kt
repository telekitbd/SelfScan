package com.scanner.selfscan

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * QR এর লেখা দেখে কোথায় পাঠাবে ঠিক করে।
 * অ্যাপ ফরম্যাট:  app:<প্যাকেজ>?token=<টোকেন>   (যেমন app:com.immediate.shop?token=abc123)
 */
object ScanRouter {
    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

    fun handle(ctx: Context, raw: String) {
        val t = raw.trim()
        when {
            t.startsWith("app:", true) -> openApp(ctx, t)
            t.startsWith("http://", true) || t.startsWith("https://", true) ->
                if (isApk(t)) downloadApk(ctx, t) else openWeb(ctx, t)
            t.startsWith("intent:", true) -> openIntentUri(ctx, t)
            // otpauth://, fb://, market://, tel: ইত্যাদি: সেই লিংক যে অ্যাপ হ্যান্ডেল করে সেটাই খুলবে
            SCHEME.containsMatchIn(t) && !t.contains(" ") -> view(ctx, t)
            else -> toast(ctx, t)
        }
    }

    private fun isApk(url: String) = Uri.parse(url).path?.endsWith(".apk", true) == true

    /**
     * ওয়েব লিংক ব্রাউজারে/WebView তে না খুলে, ওই লিংক যে অ্যাপ হ্যান্ডেল করে (YouTube, Facebook, Telegram ইত্যাদি)
     * সেই অ্যাপেই খুলবে। কোনো অ্যাপ না পেলে তখনই শুধু ব্রাউজারে যাবে।
     */
    fun openWeb(ctx: Context, url: String) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
                )
                return
            } catch (e: ActivityNotFoundException) { /* অ্যাপ নেই, নিচে ব্রাউজার */ }
        }
        view(ctx, url)
    }

    private fun openApp(ctx: Context, t: String) {
        val body = t.substring(4)
        val pkg = body.substringBefore("?").trim()
        val token = Regex("(?:^|[?&])token=([^&]*)").find(body)?.groupValues?.get(1)?.let { Uri.decode(it) }
        if (!PKG.matches(pkg)) { toast(ctx, "অ্যাপের নাম ঠিক নেই"); return }
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { store(ctx, pkg); return }   // অ্যাপ নেই: Play Store এ নেবে
        if (token != null) i.putExtra("token", token)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun store(ctx: Context, pkg: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            view(ctx, "https://play.google.com/store/apps/details?id=$pkg")
        }
    }

    private fun openIntentUri(ctx: Context, t: String) {
        val i = try { Intent.parseUri(t, Intent.URI_INTENT_SCHEME) } catch (e: Exception) { toast(ctx, "লিংক ঠিক নেই"); return }
        i.addCategory(Intent.CATEGORY_BROWSABLE)
        i.component = null
        i.selector = null
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            val fb = i.getStringExtra("browser_fallback_url")
            val p = i.`package`
            when {
                fb != null && fb.startsWith("http") -> openWeb(ctx, fb)
                p != null -> store(ctx, p)
                else -> toast(ctx, "এই লিংক খোলার মতো অ্যাপ নেই")
            }
        }
    }

    fun view(ctx: Context, url: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            toast(ctx, "এই লিংক খোলার মতো অ্যাপ নেই")
        }
    }

    fun downloadApk(ctx: Context, url: String) {
        if (ctx is Activity) {
            AlertDialog.Builder(ctx)
                .setTitle("APK ডাউনলোড")
                .setMessage("শুধু বিশ্বস্ত উৎসের APK ইনস্টল করুন।\n\n$url")
                .setPositiveButton("ডাউনলোড") { _, _ -> startDownload(ctx, url) }
                .setNegativeButton("বাতিল", null)
                .show()
        } else startDownload(ctx, url)
    }

    private fun startDownload(ctx: Context, url: String) {
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            toast(app, "ইনস্টলের অনুমতি দিন, তারপর আবার স্ক্যান করুন")
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val dm = app.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("APK ডাউনলোড হচ্ছে")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "scan_${System.currentTimeMillis()}.apk")
        val id = dm.enqueue(req)
        toast(app, "ডাউনলোড শুরু হয়েছে")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id) ?: return
                try {
                    c.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (_: Exception) { /* নোটিফিকেশনে চাপলেও ইনস্টল শুরু হবে */ }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
