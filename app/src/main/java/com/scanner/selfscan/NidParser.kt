package com.scanner.selfscan

import com.google.mlkit.vision.barcode.common.Barcode

/** NID/কার্ডের বারকোডের লেখা থেকে নাম, নম্বর, জন্মতারিখ ইত্যাদি আলাদা করে (XML, JSON বা "key: value" ধরনের লেখা)। */
object NidParser {
    private val GROUPS: List<Pair<Set<String>, String>> = listOf(
        setOf("nid", "nidnumber", "nidno", "pin", "nationalid", "nationalidno", "nationalidnumber", "idnumber", "idno") to "NID নম্বর",
        setOf("namebn", "namebangla", "namebengali", "nameinbangla", "banglaname") to "নাম (বাংলা)",
        setOf("name", "nameen", "nameenglish", "fullname", "nameinenglish") to "নাম",
        setOf("dob", "dateofbirth", "birthdate", "birth", "dateofbirthen") to "জন্মতারিখ",
        setOf("father", "fathername", "fathernameen") to "পিতার নাম",
        setOf("mother", "mothername", "mothernameen") to "মাতার নাম",
        setOf("spouse", "spousename", "husband", "wife") to "স্বামী/স্ত্রী",
        setOf("address", "presentaddress", "permanentaddress", "presentaddressen") to "ঠিকানা",
        setOf("blood", "bloodgroup") to "রক্তের গ্রুপ",
        setOf("gender", "sex") to "লিঙ্গ",
        setOf("issuedate", "dateofissue", "issued") to "ইস্যুর তারিখ"
    )
    private val NID_KEYS = GROUPS[0].first
    private val NAME_KEYS = GROUPS[1].first + GROUPS[2].first
    private val DOB_KEYS = GROUPS[3].first
    private const val MAX_VALUE = 120

    data class Field(val label: String, val value: String)
    data class Result(val fields: List<Field>, val hidden: Int, val nidLike: Boolean)

    private fun norm(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    fun parse(raw: String): Result {
        val found = ArrayList<Pair<String, String>>()
        Regex("<\\s*([A-Za-z][A-Za-z0-9_\\-]*)\\s*>([^<]*)<\\s*/").findAll(raw)
            .forEach { found += it.groupValues[1] to it.groupValues[2].trim() }
        Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(raw)
            .forEach { found += it.groupValues[1] to it.groupValues[2].trim() }
        Regex("([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]*)\"").findAll(raw)
            .forEach { found += it.groupValues[1] to it.groupValues[2].trim() }
        val kv = Regex("^\\s*([A-Za-z][A-Za-z0-9 _.\\-]{0,29}?)\\s*[:=]\\s*(.+)$")
        raw.split(Regex("[\\n\\r|]")).forEach { line ->
            kv.find(line)?.let { found += it.groupValues[1] to it.groupValues[2].trim() }
        }

        val keys = found.map { norm(it.first) }.toSet()
        val nidLike = keys.any { it in NID_KEYS } && keys.any { it in NAME_KEYS || it in DOB_KEYS }

        val fields = LinkedHashSet<Field>()
        var hidden = 0
        for ((k, v) in found) {
            if (v.isBlank()) continue
            if (v.length > MAX_VALUE) { hidden++; continue }
            val label = GROUPS.firstOrNull { norm(k) in it.first }?.second ?: k.trim()
            fields += Field(label, v)
        }
        return Result(fields.toList(), hidden, nidLike)
    }

    fun looksLikeNid(raw: String, format: Int): Boolean =
        format == Barcode.FORMAT_PDF417 || parse(raw).nidLike
}
