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

    val quizEnabled: Boolean get() = quizMinutes > 0 && deepseekKey.isNotBlank()

    fun getProgress(id: String): Int = p.getInt("pos_$id", 0)
    fun setProgress(id: String, i: Int) = p.edit().putInt("pos_$id", i).apply()

    fun history(): List<HistoryItem> {
        val arr = JSONArray(p.getString("history", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(o.getLong("t"), o.getDouble("speed").toFloat(), o.getInt("ok"), o.getInt("n"))
        }
    }

    fun addHistory(item: HistoryItem) {
        val arr = JSONArray(p.getString("history", "[]"))
        arr.put(JSONObject().put("t", item.time).put("speed", item.speed.toDouble()).put("ok", item.ok).put("n", item.total))
        val trimmed = JSONArray()
        val from = maxOf(0, arr.length() - 300)
        for (i in from until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString("history", trimmed.toString()).apply()
    }
}

data class HistoryItem(val time: Long, val speed: Float, val ok: Int, val total: Int)

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

    fun delete(ctx: Context, id: String) {
        File(root(ctx), id).deleteRecursively()
    }
}
