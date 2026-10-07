package com.anri.audioreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Достаёт из файла название и список абзацев. */
object Parsers {

    fun parse(ctx: Context, uri: Uri): Pair<String, List<String>> {
        val name = displayName(ctx, uri) ?: "Книга"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("файл не читается")
        val base = name.substringBefore('.')
        val lower = name.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> fb2(firstInZip(bytes, ".fb2"), base)
            lower.endsWith(".fb2") -> fb2(bytes, base)
            lower.endsWith(".epub") -> epub(bytes, base)
            else -> txt(bytes, base)
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
        return text.removePrefix("﻿")
    }

    private fun txt(bytes: ByteArray, base: String): Pair<String, List<String>> {
        val paras = decode(bytes).split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        return base to paras
    }

    private fun fb2(bytes: ByteArray, base: String): Pair<String, List<String>> {
        val head = String(bytes, 0, minOf(400, bytes.size), Charsets.ISO_8859_1)
        val enc = Regex("encoding=[\"']([^\"']+)[\"']").find(head)?.groupValues?.get(1) ?: "utf-8"
        val xml = runCatching { String(bytes, Charset.forName(enc)) }.getOrElse { decode(bytes) }
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val title = doc.getElementsByTag("book-title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: base
        val paras = doc.getElementsByTag("body")
            .filter { it.attr("name") != "notes" && it.attr("name") != "comments" }
            .flatMap { body -> body.select("p, v, subtitle").map { it.text().trim() } }
            .filter { it.isNotEmpty() }
        return title to paras
    }

    private fun epub(bytes: ByteArray, base: String): Pair<String, List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) files[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        val container = Jsoup.parse(
            String(files["META-INF/container.xml"] ?: error("это не EPUB"), Charsets.UTF_8), "", Parser.xmlParser()
        )
        val opfPath = container.getElementsByTag("rootfile").first()?.attr("full-path") ?: error("в EPUB нет оглавления")
        val opf = Jsoup.parse(String(files[opfPath] ?: error("в EPUB нет оглавления"), Charsets.UTF_8), "", Parser.xmlParser())
        val dir = opfPath.substringBeforeLast('/', "")
        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: base
        val manifest = opf.getElementsByTag("item").associate { it.attr("id") to it.attr("href") }
        val spine = opf.getElementsByTag("itemref").map { it.attr("idref") }

        val paras = ArrayList<String>()
        for (id in spine) {
            val href = manifest[id] ?: continue
            val path = resolve(dir, URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            val data = files[path] ?: continue
            val body = Jsoup.parse(String(data, Charsets.UTF_8)).body()
            val blocks = body.select("p, h1, h2, h3, h4, h5, h6")
            if (blocks.isEmpty()) {
                body.text().trim().takeIf { it.isNotEmpty() }?.let { paras.add(it) }
            } else {
                blocks.forEach { b -> b.text().trim().takeIf { it.isNotEmpty() }?.let { paras.add(it) } }
            }
        }
        return title to paras
    }

    private fun resolve(dir: String, href: String): String {
        val stack = ArrayList<String>()
        val full = if (dir.isEmpty()) href else "$dir/$href"
        for (part in full.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun firstInZip(bytes: ByteArray, ext: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.lowercase().endsWith(ext)) return z.readBytes()
                e = z.nextEntry
            }
        }
        error("в архиве нет $ext")
    }
}

/** Режет абзацы на фрагменты ~700 символов по границам предложений. */
object Segmenter {
    private const val MAX = 700

    fun split(paras: List<String>): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) out.add(sb.toString().trim())
            sb.setLength(0)
        }
        for (p in paras) {
            val pieces = if (p.length <= MAX) listOf(p) else sentences(p)
            for (s in pieces) {
                if (sb.isNotEmpty() && sb.length + s.length + 1 > MAX) flush()
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(s)
            }
        }
        flush()
        return out
    }

    private fun sentences(p: String): List<String> =
        p.split(Regex("(?<=[.!?…])\\s+")).flatMap { byWords(it) }

    private fun byWords(s: String): List<String> {
        if (s.length <= MAX) return listOf(s)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (w in s.split(' ')) {
            if (sb.isNotEmpty() && sb.length + w.length + 1 > MAX) {
                out.add(sb.toString()); sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
