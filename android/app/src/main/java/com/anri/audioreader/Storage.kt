package com.anri.audioreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim()).apply()

    var serverToken: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()

    var speaker: String
        get() = p.getString("speaker", "xenia") ?: "xenia"
        set(v) = p.edit().putString("speaker", v).apply()

    var deepseekKey: String
        get() = p.getString("deepseek", "") ?: ""
        set(v) = p.edit().putString("deepseek", v.trim()).apply()

    var quizMinutes: Int
        get() = p.getInt("quizMinutes", 10)
        set(v) = p.edit().putInt("quizMinutes", v).apply()

    var quizCount: Int
        get() = p.getInt("quizCount", 3)
        set(v) = p.edit().putInt("quizCount", v).apply()

    var speed: Float
        get() = p.getFloat("speed", 1.0f)
        set(v) = p.edit().putFloat("speed", v).apply()

    /** Средний темп голоса на х1 (слов в минуту), измеряется по сыгранным фрагментам. */
    var baseWpm: Float
        get() = p.getFloat("baseWpm", 0f)
        set(v) = p.edit().putFloat("baseWpm", v).apply()

    // ---- внимание ----
    var microMinutes: Int
        get() = p.getInt("microMinutes", 3)
        set(v) = p.edit().putInt("microMinutes", v).apply()

    var adaptive: Boolean
        get() = p.getBoolean("adaptive", true)
        set(v) = p.edit().putBoolean("adaptive", v).apply()

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    var hooks: Boolean
        get() = p.getBoolean("hooks", true)
        set(v) = p.edit().putBoolean("hooks", v).apply()

    /** gradient | split | plain */
    var bgMode: String
        get() = p.getString("bgMode", "gradient") ?: "gradient"
        set(v) = p.edit().putString("bgMode", v).apply()

    var landscape: Boolean
        get() = p.getBoolean("landscape", true)
        set(v) = p.edit().putBoolean("landscape", v).apply()

    /** auto | brown | rain | fire | off */
    var ambient: String
        get() = p.getString("ambient", "auto") ?: "auto"
        set(v) = p.edit().putString("ambient", v).apply()

    var ambientVolume: Float
        get() = p.getFloat("ambientVolume", 0.3f)
        set(v) = p.edit().putFloat("ambientVolume", v).apply()

    val microEnabled: Boolean get() = microMinutes > 0 && deepseekKey.isNotBlank()

    fun getHook(bookId: String, episode: Int): String? = p.getString("hook_${bookId}_$episode", null)
    fun setHook(bookId: String, episode: Int, json: String) = p.edit().putString("hook_${bookId}_$episode", json).apply()

    val quizEnabled: Boolean get() = quizMinutes > 0 && deepseekKey.isNotBlank()

    fun getProgress(id: String): Int = p.getInt("pos_$id", 0)
    fun setProgress(id: String, i: Int) = p.edit().putInt("pos_$id", i).apply()

    fun history(): List<HistoryItem> {
        val arr = JSONArray(p.getString("history", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(o.getLong("t"), o.getDouble("speed").toFloat(), o.getInt("ok"), o.getInt("n"),
                o.optString("kind", "quiz"), o.optString("mode", ""))
        }
    }

    fun addHistory(item: HistoryItem) {
        val arr = JSONArray(p.getString("history", "[]"))
        arr.put(JSONObject().put("t", item.time).put("speed", item.speed.toDouble()).put("ok", item.ok).put("n", item.total)
            .put("kind", item.kind).put("mode", item.mode))
        val trimmed = JSONArray()
        val from = maxOf(0, arr.length() - 1000)
        for (i in from until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString("history", trimmed.toString()).apply()
    }
}

data class HistoryItem(
    val time: Long,
    val speed: Float,
    val ok: Int,
    val total: Int,
    val kind: String = "quiz",   // quiz | micro
    val mode: String = "",       // фон, с которым слушал: gradient | split | plain
)

data class Quote(val time: Long, val segment: Int, val text: String)

data class BookMeta(val id: String, val title: String, val count: Int)

class Book(val id: String, val title: String, val segments: List<String>)

object Library {
    private fun root(ctx: Context) = File(ctx.filesDir, "books")

    fun list(ctx: Context): List<BookMeta> =
        (root(ctx).listFiles() ?: emptyArray())
            .mapNotNull { dir ->
                runCatching {
                    val o = JSONObject(File(dir, "meta.json").readText())
                    BookMeta(o.getString("id"), o.getString("title"), o.getInt("count"))
                }.getOrNull()
            }
            .sortedByDescending { it.id }

    fun save(ctx: Context, title: String, segments: List<String>): String {
        val id = System.currentTimeMillis().toString()
        val dir = File(root(ctx), id).apply { mkdirs() }
        File(dir, "segments.json").writeText(JSONArray(segments).toString())
        File(dir, "meta.json").writeText(
            JSONObject().put("id", id).put("title", title).put("count", segments.size).toString()
        )
        return id
    }

    fun load(ctx: Context, id: String): Book {
        val dir = File(root(ctx), id)
        val meta = JSONObject(File(dir, "meta.json").readText())
        val arr = JSONArray(File(dir, "segments.json").readText())
        return Book(id, meta.getString("title"), (0 until arr.length()).map { arr.getString(it) })
    }

    private fun quotesFile(ctx: Context, id: String) = File(File(root(ctx), id), "quotes.json")

    fun quotes(ctx: Context, id: String): List<Quote> {
        val f = quotesFile(ctx, id)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Quote(o.getLong("t"), o.getInt("seg"), o.getString("text"))
        }
    }

    private fun writeQuotes(ctx: Context, id: String, list: List<Quote>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.time).put("seg", it.segment).put("text", it.text)) }
        quotesFile(ctx, id).writeText(arr.toString())
    }

    /** Возвращает false, если такая фраза уже сохранена. */
    fun addQuote(ctx: Context, id: String, q: Quote): Boolean {
        val list = quotes(ctx, id)
        if (list.any { it.segment == q.segment && it.text == q.text }) return false
        writeQuotes(ctx, id, list + q)
        return true
    }

    fun deleteQuote(ctx: Context, id: String, time: Long) =
        writeQuotes(ctx, id, quotes(ctx, id).filter { it.time != time })

    fun delete(ctx: Context, id: String) {
        File(root(ctx), id).deleteRecursively()
    }
}
