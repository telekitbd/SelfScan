package com.scanner.selfscan

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** QR/বারকোডে লিংক না থেকে সাধারণ লেখা থাকলে সেটা এখানে দেখায়, কপি করার বাটনসহ। */
class TextActivity : AppCompatActivity() {
    private val PURPLE = 0xFF4C1D95.toInt()
    private val SOFT = 0xFFF5F3FF.toInt()
    private val BORDER = 0xFFDDD6FE.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(c: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else PURPLE)
        typeface = Typeface.DEFAULT_BOLD
        background = shape(if (filled) PURPLE else Color.WHITE, 14, if (filled) null else BORDER)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = SOFT
        val content = intent.getStringExtra("text") ?: ""

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            addView(TextView(this@TextActivity).apply {
                text = "স্ক্যান করা লেখা"; textSize = 22f; setTextColor(PURPLE); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@TextActivity).apply {
                text = content
                textSize = 16f
                setTextColor(0xFF111827.toInt())
                setTextIsSelectable(true)
                background = shape(Color.WHITE, 14, BORDER)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
            })
            addView(button("কপি করুন", true) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("scan", content))
                Toast.makeText(this@TextActivity, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
            })
            addView(button("বন্ধ করুন", false) { finish() })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(SOFT); addView(col) })
    }
}
