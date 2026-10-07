package com.anri.audioreader

import org.json.JSONArray

/** Предложение фрагмента: символы [charStart, charEnd) звучат с startSec по endSec. */
data class Sentence(val charStart: Int, val charEnd: Int, val startSec: Float, val endSec: Float)

data class Word(val start: Int, val end: Int)

/**
 * Привязка слов ко времени. Сервер знает точное начало и конец каждого предложения,
 * а внутри предложения время делится между словами по числу слогов.
 */
object Karaoke {
    private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯaeiouyAEIOUY"
    private const val PAUSE_MARKS = ",;:—–-…"

    fun parse(json: String): List<Sentence> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val a = arr.getJSONArray(it)
            Sentence(a.getInt(0), a.getInt(1), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
        }
    }

    fun words(text: String): List<Word> =
        Regex("\\S+").findAll(text).map { Word(it.range.first, it.range.last + 1) }.toList()

    private fun weight(text: String, w: Word): Float {
        val s = text.substring(w.start, w.end)
        var v = s.count { it in VOWELS }.toFloat()
        v += s.count { it.isDigit() } * 2.5f            // числа читаются длинно
        if (v == 0f) v = if (s.any { it.isLetter() }) 1f else 0.15f
        if (s.last() in PAUSE_MARKS) v += 1f            // пауза после запятой и тире
        return v
    }

    /** Время начала каждого слова в секундах. */
    fun schedule(text: String, words: List<Word>, sentences: List<Sentence>): FloatArray {
        val starts = FloatArray(words.size) { -1f }
        for (s in sentences) {
            val idx = words.indices.filter { words[it].start >= s.charStart && words[it].start < s.charEnd }
            if (idx.isEmpty()) continue
            val weights = idx.map { weight(text, words[it]) }
            val total = weights.sum()
            var acc = 0f
            idx.forEachIndexed { k, wi ->
                starts[wi] = s.startSec + (s.endSec - s.startSec) * acc / total
                acc += weights[k]
            }
        }
        for (i in starts.indices) if (starts[i] < 0) starts[i] = if (i > 0) starts[i - 1] else 0f
        return starts
    }

    /** Если сервер не прислал тайминги: весь фрагмент как одно предложение. */
    fun fallback(text: String, durationSec: Float): List<Sentence> =
        listOf(Sentence(0, text.length, 0f, durationSec * 0.97f))

    fun current(starts: FloatArray, posSec: Float): Int {
        var lo = 0
        var hi = starts.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (starts[mid] <= posSec) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}
