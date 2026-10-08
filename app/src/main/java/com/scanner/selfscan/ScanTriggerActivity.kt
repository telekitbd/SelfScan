package com.scanner.selfscan

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** নোটিফিকেশন থেকে খোলে (স্বচ্ছ), স্ক্রিন স্ক্যান করে ফলাফল অনুযায়ী পাঠায়, তারপর বন্ধ হয়। */
class ScanTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({
            val s = ScreenScanService.instance
            if (s == null) {
                Toast.makeText(this, "স্ক্রিন স্ক্যান চালু নেই, অ্যাপ থেকে চালু করুন", Toast.LENGTH_LONG).show()
                finish()
            } else {
                s.capture { text ->
                    if (text != null) ScanRouter.handle(this, text)
                    else Toast.makeText(this, "স্ক্রিনে QR পাওয়া যায়নি", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }, 600)
    }
}
