package com.anri.audioreader

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON = "application/json".toMediaType()

private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()

fun timingFileFor(audio: File) = File(audio.path.removeSuffix(".ogg") + ".json")

object TtsClient {
    fun fetch(base: String, token: String, speaker: String, text: String, out: File) {
        if (base.isBlank()) throw IOException("в настройках не указан адрес сервера")
        val body = JSONObject().put("text", text).put("speaker", speaker).toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url(base.trimEnd('/') + "/tts")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 401 -> throw IOException("сервер не принял токен")
                !r.isSuccessful -> throw IOException("сервер ответил ${r.code}")
            }
            out.parentFile?.mkdirs()
            // Тайминги предложений для караоке-подсветки (новый сервер присылает их в заголовке)
            r.header("X-Timing")?.let { b64 ->
                runCatching {
                    timingFileFor(out).writeText(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8))
                }
            }
            val tmp = File(out.path + ".part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(out)) throw IOException("не удалось сохранить аудио")
        }
    }
}

/** Проверка сервера озвучки: возвращает имя модели или бросает понятную ошибку. */
fun checkServer(base: String): String {
    if (base.isBlank()) throw IOException("адрес не указан")
    val req = Request.Builder().url(base.trimEnd('/') + "/health").get().build()
    http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw IOException("сервер ответил ${r.code}")
        val o = JSONObject(r.body!!.string())
        if (!o.optBoolean("ok")) throw IOException("по этому адресу не сервер озвучки")
        return o.optString("model", "?")
    }
}

data class Question(val q: String, val options: List<String>, val answer: Int)

object QuizClient {
    private const val SYSTEM = """Ты проверяешь, понял ли человек прослушанный отрывок книги.
Составь вопросы только по конкретному содержанию этого отрывка: события, поступки, причины, детали, имена, числа.
Не спрашивай об общих знаниях и о том, чего нет в тексте. Вопросы короткие, на русском языке.
У каждого вопроса ровно 3 варианта ответа, верный ровно один, неверные правдоподобные.
Ответ верни строго в формате JSON:
{"questions":[{"q":"текст вопроса","options":["вариант","вариант","вариант"],"answer":0}]}
где answer — номер верного варианта (0, 1 или 2)."""

    fun generate(key: String, text: String, count: Int): List<Question> {
        val payload = JSONObject()
            .put("model", "deepseek-chat")
            .put("temperature", 0.3)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", "Нужно вопросов: $count.\n\nОтрывок:\n$text"))
            )
        val req = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val raw = http.newCall(req).execute().use { r ->
            when {
                r.code == 401 -> throw IOException("DeepSeek не принял ключ")
                r.code == 402 -> throw IOException("на балансе DeepSeek закончились деньги")
                !r.isSuccessful -> throw IOException("DeepSeek ответил ${r.code}")
            }
            r.body!!.string()
        }
        val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val arr = JSONObject(content).getJSONArray("questions")
        val out = ArrayList<Question>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val opts = o.getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it) } }
            val ans = o.getInt("answer")
            if (opts.size < 2 || ans !in opts.indices) continue
            val order = opts.indices.shuffled()
            out.add(Question(o.getString("q"), order.map { opts[it] }, order.indexOf(ans)))
        }
        if (out.isEmpty()) throw IOException("DeepSeek вернул пустой опрос")
        return out
    }
}

/** Общий вызов DeepSeek с ответом в JSON. */
private fun deepseekJson(key: String, system: String, user: String, temperature: Double): JSONObject {
    val payload = JSONObject()
        .put("model", "deepseek-chat")
        .put("temperature", temperature)
        .put("response_format", JSONObject().put("type", "json_object"))
        .put(
            "messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
        )
    val req = Request.Builder()
        .url("https://api.deepseek.com/chat/completions")
        .header("Authorization", "Bearer $key")
        .post(payload.toString().toRequestBody(JSON))
        .build()
    val raw = http.newCall(req).execute().use { r ->
        when {
            r.code == 401 -> throw IOException("DeepSeek не принял ключ")
            r.code == 402 -> throw IOException("на балансе DeepSeek закончились деньги")
            !r.isSuccessful -> throw IOException("DeepSeek ответил ${r.code}")
        }
        r.body!!.string()
    }
    val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
        .getJSONObject("message").getString("content")
    return JSONObject(content)
}

data class Hook(val text: String, val mood: String)

val MOODS = listOf("calm", "warm", "joy", "business", "tense", "dark", "sad")

object HookClient {
    private const val SYSTEM = """Ты пишешь короткий «крючок» перед следующей серией аудиокниги, чтобы слушателю захотелось узнать продолжение.
Тебе дают текст, который он сейчас услышит. Напиши ОДНУ фразу до 120 символов: интригующий вопрос или намёк на то, что произойдёт или станет понятно.
Не раскрывай развязку и ответ, не пересказывай, не используй кавычки и эмодзи. Пиши по-русски, живо и конкретно, с именами из текста.
Также определи настроение отрывка одним словом из списка: calm, warm, joy, business, tense, dark, sad.
Ответ строго в JSON: {"hook":"...","mood":"..."}"""

    fun hook(key: String, text: String): Hook {
        val o = deepseekJson(key, SYSTEM, text.take(6000), 0.8)
        val mood = o.optString("mood").takeIf { it in MOODS } ?: "calm"
        return Hook(o.getString("hook").trim(), mood)
    }
}

object MicroClient {
    private const val SYSTEM = """Ты задаёшь один быстрый вопрос, проверяющий, слушал ли человек отрывок книги внимательно.
Вопрос по конкретному факту, поступку, причине или детали из отрывка, особенно из его последней части. Не по общим знаниям.
Вопрос до 80 символов. Ровно 2 варианта ответа до 40 символов, верный один, неверный правдоподобный.
Ответ строго в JSON: {"q":"...","options":["...","..."],"answer":0}"""

    fun question(key: String, text: String): Question {
        val o = deepseekJson(key, SYSTEM, text.takeLast(5000), 0.5)
        val arr = o.getJSONArray("options")
        val opts = (0 until arr.length()).map { arr.getString(it) }
        val ans = o.getInt("answer")
        if (opts.size != 2 || ans !in opts.indices) throw IOException("DeepSeek вернул неверный вопрос")
        val order = opts.indices.shuffled()
        return Question(o.getString("q"), order.map { opts[it] }, order.indexOf(ans))
    }
}
