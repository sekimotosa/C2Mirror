package io.github.sekimotosa.c2mirror.pairing

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * lockdownd との通信に使う XML plist の最小実装。
 * 対応型: Map<String, Any>(dict) / List(array) / String / Long,Int(integer) / Boolean / ByteArray(data) / Double(real)
 */
object Plist {
    fun encode(root: Any): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n")
        write(sb, root, 0)
        sb.append("</plist>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun indent(sb: StringBuilder, n: Int) { repeat(n) { sb.append('\t') } }

    private fun write(sb: StringBuilder, v: Any, depth: Int) {
        indent(sb, depth)
        when (v) {
            is Map<*, *> -> {
                sb.append("<dict>\n")
                for ((k, value) in v) {
                    if (value == null) continue
                    indent(sb, depth + 1); sb.append("<key>").append(esc(k.toString())).append("</key>\n")
                    write(sb, value, depth + 1)
                }
                indent(sb, depth); sb.append("</dict>\n")
            }
            is List<*> -> {
                sb.append("<array>\n")
                v.filterNotNull().forEach { write(sb, it, depth + 1) }
                indent(sb, depth); sb.append("</array>\n")
            }
            is String -> sb.append("<string>").append(esc(v)).append("</string>\n")
            is Boolean -> sb.append(if (v) "<true/>\n" else "<false/>\n")
            is Int, is Long -> sb.append("<integer>").append(v).append("</integer>\n")
            is Double -> sb.append("<real>").append(v).append("</real>\n")
            is ByteArray -> sb.append("<data>").append(Base64.getEncoder().encodeToString(v)).append("</data>\n")
            else -> throw IllegalArgumentException("unsupported plist type ${v::class.java}")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun decode(bytes: ByteArray): Any? {
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "bplist") {
            throw IllegalArgumentException("binary plist is not supported")
        }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
        val doc = f.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }
            .parse(ByteArrayInputStream(bytes))
        val plist = doc.documentElement
        val first = children(plist).firstOrNull() ?: return null
        return parse(first)
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeDict(bytes: ByteArray): Map<String, Any> =
        decode(bytes) as? Map<String, Any> ?: throw IllegalArgumentException("plist root is not a dict")

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        val nl = e.childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let { out += it }
        return out
    }

    private fun parse(e: Element): Any = when (e.tagName) {
        "dict" -> {
            val m = LinkedHashMap<String, Any>()
            val c = children(e)
            var i = 0
            while (i + 1 < c.size) {
                m[c[i].textContent] = parse(c[i + 1]); i += 2
            }
            m
        }
        "array" -> children(e).map { parse(it) }
        "string" -> e.textContent
        "integer" -> e.textContent.trim().toLong()
        "real" -> e.textContent.trim().toDouble()
        "true" -> true
        "false" -> false
        "data" -> Base64.getMimeDecoder().decode(e.textContent.trim())
        "date" -> e.textContent
        else -> throw IllegalArgumentException("unsupported plist element <${e.tagName}>")
    }
}
