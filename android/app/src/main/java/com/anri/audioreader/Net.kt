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
