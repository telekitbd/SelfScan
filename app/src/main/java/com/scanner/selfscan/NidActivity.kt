package com.scanner.selfscan

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** NID/কার্ড বারকোডের তথ্য দেখায়, প্রতিটার পাশে কপি বাটন। সব কাজ ফোনের ভেতরেই হয়, কোথাও পাঠানো হয় না। */
class NidActivity : AppCompatActivity() {
    private val PURPLE = 0xFF4C1D95.toInt()
    private val SOFT = 0xFFF5F3FF.toInt()
    private val BORDER = 0xFFDDD6FE.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(c: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
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

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("scan", text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        Toast.makeText(this, "কপি হয়েছে", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = SOFT
        val raw = intent.getStringExtra("raw") ?: ""
        val res = NidParser.parse(raw)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            addView(tv("কার্ডের বারকোড তথ্য", 22f, PURPLE, true))
            addView(tv("তথ্য শুধু আপনার ফোনেই থাকে, কোথাও পাঠানো হয় না", 12f, 0xFF6B7280.toInt()))
        }

        if (res.fields.isEmpty()) {
            col.addView(tv("তথ্য আলাদা করা যায়নি। নিচের 'Raw ডেটা কপি' চেপে লেখাটা কপি করুন।", 14f, 0xFF374151.toInt()).apply {
                setPadding(0, dp(16), 0, 0)
            })
        }
        for (f in res.fields) {
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(f.label, 12f, 0xFF6B7280.toInt()))
                addView(tv(f.value, 16f, 0xFF111827.toInt(), true))
            }
            val copyBtn = tv("কপি", 14f, PURPLE, true).apply {
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener { copy(f.value) }
            }
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(Color.WHITE, 14, BORDER)
                setPadding(dp(16), dp(12), dp(12), dp(12))
                addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(copyBtn)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
            })
        }
        if (res.hidden > 0) {
            col.addView(tv("(খুব বড় ${res.hidden}টি ডেটা দেখানো হয়নি)", 12f, 0xFF6B7280.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        }

        if (res.fields.isNotEmpty()) {
            col.addView(button("সব তথ্য কপি", true) {
                copy(res.fields.joinToString("\n") { "${it.label}: ${it.value}" })
            })
        }
        col.addView(button("Raw ডেটা কপি", false) { copy(raw) })
        col.addView(button("বন্ধ করুন", false) { finish() })

        setContentView(ScrollView(this).apply { setBackgroundColor(SOFT); addView(col) })
    }
}
