package com.gnterminal.app.core

/**
 * Tiny dependency-free JSON reader. Produces Map<String, Any?>, List<Any?>,
 * String, Double, Boolean or null. Kept pure-Kotlin so parsers are unit-testable
 * off-device.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Reader(text)
        val v = p.value()
        return v
    }

    fun parseOrNull(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        return try {
            parse(text)
        } catch (e: Exception) {
            null
        }
    }

    /** Quote a string as a JSON / JavaScript string literal. Null becomes `null`. */
    fun quote(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') i++ else break
            }
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            val c = s[i]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> lit("true", true)
                c == 'f' -> lit("false", false)
                c == 'n' -> lit("null", null)
                c == '-' || c.isDigit() -> num()
                else -> throw IllegalArgumentException("Unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Bad literal at $i")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("Expected key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("Expected ':' at $i")
                i++
                m[k] = value()
                ws()
                if (i >= s.length) throw IllegalArgumentException("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l.add(value())
                ws()
                if (i >= s.length) throw IllegalArgumentException("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
                }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) throw IllegalArgumentException("Bad escape")
                val e = s[i++]
                when (e) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) throw IllegalArgumentException("Bad unicode escape")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
            }
        }

        fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++ else break
            }
            return s.substring(start, i).toDouble()
        }
    }
}

// ---- Lightweight accessors over parsed JSON -------------------------------------------

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?>? = this as? List<Any?>

fun Any?.field(key: String): Any? = asMap()?.get(key)

/** Case-insensitive key lookup (MCX changes the casing of its field names). */
fun Any?.fieldCI(key: String): Any? {
    val m = asMap() ?: return null
    if (m.containsKey(key)) return m[key]
    val k = m.keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: return null
    return m[k]
}

fun Any?.obj(key: String): Map<String, Any?>? = field(key).asMap()
fun Any?.list(key: String): List<Any?>? = field(key).asList()
fun Any?.num(key: String): Double? = toNum(field(key))
fun Any?.text(key: String): String? = toText(field(key))
fun Any?.numCI(key: String): Double? = toNum(fieldCI(key))
fun Any?.textCI(key: String): String? = toText(fieldCI(key))

fun toNum(v: Any?): Double? = when (v) {
    is Double -> if (v.isNaN()) null else v
    is Number -> v.toDouble()
    is String -> v.replace(",", "").trim().toDoubleOrNull()
    else -> null
}

fun toText(v: Any?): String? = when (v) {
    null -> null
    is String -> v
    is Double -> if (!v.isInfinite() && v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    else -> v.toString()
}
