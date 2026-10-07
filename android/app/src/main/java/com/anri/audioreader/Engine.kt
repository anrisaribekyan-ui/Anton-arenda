package com.anri.audioreader

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class QuizState(
    val loading: Boolean = false,
    val questions: List<Question> = emptyList(),
    val error: String? = null,
)

data class UiState(
    val bookId: String? = null,
    val title: String = "",
    val count: Int = 0,
    val index: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
    val quiz: QuizState? = null,
    val listenedSec: Int = 0,
    val baseWpm: Float = 0f,
    val mood: String = "calm",
    val hook: HookCard? = null,
    val micro: MicroState? = null,
    val microSec: Int = 0,
    val combo: Int = 0,
    val episodeDone: EpisodeSummary? = null,
)

/** Карточка-крючок перед серией. */
data class HookCard(val episode: Int, val text: String)

/** Микро-вопрос поверх звука. picked — выбранный вариант, speedDelta — как изменилась скорость. */
data class MicroState(val q: Question, val picked: Int? = null, val speedDelta: Float = 0f)

data class EpisodeSummary(val episode: Int, val ok: Int, val total: Int, val bestCombo: Int)

/** Вся логика чтения: очередь фрагментов, загрузка озвучки, скорость, опросы. */
object Engine {
    private const val AHEAD = 4          // сколько фрагментов озвучивать заранее
    private const val KEEP_BEHIND = 10   // сколько прослушанных фрагментов хранить
    private const val AUDIO_DIR = "audio2" // v2: новое качество и тайминги
    const val EPISODE = 10                 // фрагментов в одной серии

    lateinit var app: Context
    lateinit var settings: Settings
    lateinit var player: ExoPlayer

    val state = MutableStateFlow(UiState())

    private val scope = MainScope()
    private val fillLock = Mutex()
    private var book: Book? = null
    private var queued = -1        // последний индекс книги, добавленный в плеер
    private var generation = 0     // меняется при каждом перезапуске очереди
    private var wantPlay = false
    private var listenedSec = 0    // реальное время прослушивания с прошлого опроса
    private var quizFrom = 0       // с какого фрагмента копится текст для опроса
    private var microSec = 0       // время с прошлого микро-вопроса
    private var microFrom = 0
    private var microLoading = false
    private var lastIndex = -1
    private var hookedEpisode = -1
    private val hookLoading = HashSet<Int>()
    private var epOk = 0
    private var epTotal = 0
    private var epBestCombo = 0

    fun init(ctx: Context) {
        app = ctx.applicationContext
        settings = Settings(app)
        Haptics.init(app)
        Thread { File(app.cacheDir, "audio").deleteRecursively() }.start() // аудио старой версии
        player = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setPlaybackSpeed(settings.speed)
        state.update { it.copy(speed = settings.speed, baseWpm = settings.baseWpm) }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onIndexChanged()
            override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
                if (playbackState == Player.STATE_READY) measureWpm()
                refresh()
            }
            override fun onPlayerError(error: PlaybackException) {
                state.update { it.copy(error = "Ошибка воспроизведения: ${error.errorCodeName}") }
                refresh()
            }
        })

        scope.launch {
            while (true) {
                delay(1000)
                if (player.isPlaying) {
                    listenedSec++
                    microSec++
                    maybeMicro()
                    refresh()
                }
            }
        }
    }

    fun segmentText(i: Int): String = book?.segments?.getOrNull(i) ?: ""

    /** Тайминги предложений фрагмента, если сервер их прислал. */
    fun timing(i: Int): List<Sentence>? {
        val b = book ?: return null
        val f = timingFileFor(audioFile(b.id, i))
        if (!f.exists()) return null
        return runCatching { Karaoke.parse(f.readText()) }.getOrNull()
    }

    /** Позиция и длительность в секундах, если сейчас звучит именно этот фрагмент. */
    fun position(i: Int): Pair<Float, Float>? {
        if (player.currentMediaItem?.mediaId != i.toString()) return null
        val d = player.duration
        return player.currentPosition / 1000f to (if (d > 0) d / 1000f else -1f)
    }

    private fun currentIndex(): Int =
        player.currentMediaItem?.mediaId?.toIntOrNull() ?: state.value.index

    private fun audioFile(id: String, i: Int) = File(app.cacheDir, "$AUDIO_DIR/$id/${settings.speaker}/$i.ogg")

    // ---------- открытие и навигация ----------

    fun open(id: String) {
        if (book?.id == id) return
        pause()
        val b = Library.load(app, id)
        book = b
        val pos = settings.getProgress(id).coerceIn(0, maxOf(0, b.segments.size - 1))
        state.value = UiState(bookId = id, title = b.title, count = b.segments.size, index = pos,
            speed = settings.speed, baseWpm = settings.baseWpm)
        listenedSec = 0
        microSec = 0
        hookedEpisode = -1
        lastIndex = pos
        resetEpisodeStats()
        startAt(pos, play = false)
        maybeHook(pos, showCard = true)
    }

    fun forget(id: String) {
        if (book?.id == id) {
            wantPlay = false
            generation++
            player.stop()
            player.clearMediaItems()
            book = null
            state.value = UiState(speed = settings.speed, baseWpm = settings.baseWpm)
        }
        File(app.cacheDir, "$AUDIO_DIR/$id").deleteRecursively()
    }

    private fun startAt(i: Int, play: Boolean) {
        val b = book ?: return
        generation++
        wantPlay = play
        player.stop()
        player.clearMediaItems()
        queued = i - 1
        quizFrom = i
        microFrom = i
        settings.setProgress(b.id, i)
        state.update { it.copy(index = i, error = null) }
        fill()
        refresh()
    }

    fun play() {
        val b = book ?: return
        if (state.value.quiz != null) return
        wantPlay = true
        when {
            state.value.error != null -> startAt(currentIndex(), true)
            player.mediaItemCount == 0 -> fill()
            player.playbackState == Player.STATE_ENDED ->
                if (currentIndex() >= b.segments.size - 1) startAt(0, true) else fill()
            else -> player.play()
        }
        refresh()
    }

    fun pause() {
        wantPlay = false
        player.pause()
        refresh()
    }

    fun next() = jump(currentIndex() + 1)
    fun prev() = jump(currentIndex() - 1)

    private fun jump(i: Int) {
        val b = book ?: return
        if (i !in b.segments.indices) return
        startAt(i, wantPlay || player.isPlaying)
    }

    fun retry() = startAt(currentIndex(), true)

    fun setSpeed(value: Float) {
        val v = ((value * 10).roundToInt() / 10f).coerceIn(0.5f, 8.0f)
        player.setPlaybackSpeed(v)
        settings.speed = v
        state.update { it.copy(speed = v) }
    }

    fun clearAudioCache() {
        val i = currentIndex()
        val playing = wantPlay
        File(app.cacheDir, AUDIO_DIR).deleteRecursively()
        if (book != null) startAt(i, playing)
    }

    // ---------- очередь и загрузка ----------

    private fun fill() {
        val gen = generation
        scope.launch { fillLock.withLock { fillLocked(gen) } }
    }

    private suspend fun fillLocked(gen: Int) {
        val b = book ?: return
        val target = minOf(currentIndex() + AHEAD, b.segments.size - 1)
        while (queued < target) {
            if (gen != generation) return
            val i = queued + 1
            val f = audioFile(b.id, i)
            if (!f.exists()) {
                refresh()
                try {
                    val s = settings
                    withContext(Dispatchers.IO) {
                        TtsClient.fetch(s.serverUrl, s.serverToken, s.speaker, b.segments[i], f)
                    }
                } catch (e: Exception) {
                    if (gen == generation) {
                        state.update { it.copy(error = "Не удалось озвучить: ${e.message}") }
                        refresh()
                    }
                    return
                }
            }
            if (gen != generation) return
            player.addMediaItem(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(f))
                    .setMediaId(i.toString())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(b.title)
                            .setArtist("Фрагмент ${i + 1} из ${b.segments.size}")
                            .build()
                    )
                    .build()
            )
            queued = i
            when (player.playbackState) {
                Player.STATE_IDLE -> {
                    player.prepare()
                    if (wantPlay) player.play()
                }
                Player.STATE_ENDED -> {
                    // догнали очередь, пока шла озвучка: продолжаем с нового фрагмента
                    player.seekTo(player.mediaItemCount - 1, 0)
                    if (wantPlay) player.play()
                }
                else -> {}
            }
        }
        refresh()
    }

    private fun onIndexChanged() {
        val b = book ?: return
        val i = currentIndex()
        if (i == lastIndex + 1 && i % EPISODE == 0) finishEpisode(i / EPISODE - 1)
        lastIndex = i
        settings.setProgress(b.id, i)
        state.update { it.copy(index = i) }
        maybeHook(i, showCard = i % EPISODE == 0)
        if (i % EPISODE == EPISODE - 2) prefetchHook((i + 2) / EPISODE)
        audioFile(b.id, i - KEEP_BEHIND - 1).let { it.delete(); timingFileFor(it).delete() }
        fill()
        maybeQuiz(i)
    }

    private var measured = -1

    /** Темп голоса: слова фрагмента / его длительность на х1, скользящее среднее. */
    private fun measureWpm() {
        val b = book ?: return
        val i = currentIndex()
        if (i == measured) return
        val ms = player.duration
        if (ms < 5000) return
        val words = Karaoke.words(b.segments.getOrNull(i) ?: return).size
        if (words < 15) return
        measured = i
        val wpm = words / (ms / 60000f)
        val old = settings.baseWpm
        val avg = if (old <= 0f) wpm else old * 0.85f + wpm * 0.15f
        settings.baseWpm = avg
        state.update { it.copy(baseWpm = avg) }
    }

    private fun onEnded() {
        val b = book ?: return
        if (currentIndex() >= b.segments.size - 1 && queued >= b.segments.size - 1) {
            wantPlay = false   // книга дослушана
        } else {
            fill()             // ждём, пока озвучится следующий фрагмент
        }
    }

    // ---------- опросы ----------

    private fun maybeQuiz(i: Int) {
        if (!settings.quizEnabled) return
        if (listenedSec < settings.quizMinutes * 60) return
        if (state.value.quiz != null || i <= quizFrom) return
        player.pause()
        player.seekTo(player.currentMediaItemIndex, 0)
        requestQuiz()
    }

    private fun quizText(): String {
        val b = book ?: return ""
        val to = currentIndex().coerceAtMost(b.segments.size)
        val from = quizFrom.coerceIn(0, to)
        return b.segments.subList(from, to).joinToString("\n").takeLast(12000)
    }

    fun requestQuiz() {
        val text = quizText()
        if (text.isBlank()) return
        state.update { it.copy(quiz = QuizState(loading = true)) }
        refresh()
        val key = settings.deepseekKey
        val count = settings.quizCount
        scope.launch {
            try {
                val qs = withContext(Dispatchers.IO) { QuizClient.generate(key, text, count) }
                state.update { it.copy(quiz = QuizState(questions = qs)) }
            } catch (e: Exception) {
                state.update { it.copy(quiz = QuizState(error = e.message ?: "неизвестная ошибка")) }
            }
        }
    }

    fun finishQuiz(ok: Int, total: Int) {
        if (total > 0) settings.addHistory(
            HistoryItem(System.currentTimeMillis(), settings.speed, ok, total, "quiz", settings.bgMode)
        )
        closeQuiz()
    }

    fun skipQuiz() = closeQuiz()

    private fun closeQuiz() {
        listenedSec = 0
        quizFrom = currentIndex()
        state.update { it.copy(quiz = null) }
        play()
    }

    // ---------- серии и крючки ----------

    private fun episodeText(ep: Int): String {
        val b = book ?: return ""
        val from = ep * EPISODE
        if (from >= b.segments.size) return ""
        return b.segments.subList(from, minOf(from + EPISODE, b.segments.size)).joinToString("\n")
    }

    private fun parseHook(json: String): Hook? = runCatching {
        val o = org.json.JSONObject(json)
        Hook(o.getString("hook"), o.optString("mood", "calm"))
    }.getOrNull()

    private fun applyHook(ep: Int, h: Hook, showCard: Boolean) {
        state.update { it.copy(mood = h.mood, hook = if (showCard && settings.hooks) HookCard(ep, h.text) else it.hook) }
        refresh()
    }

    /** Загружает крючок и настроение серии (из кэша или у DeepSeek). */
    private fun loadHook(ep: Int, onReady: (Hook) -> Unit) {
        val b = book ?: return
        settings.getHook(b.id, ep)?.let { parseHook(it) }?.let { onReady(it); return }
        if (settings.deepseekKey.isBlank() || ep in hookLoading) return
        val text = episodeText(ep)
        if (text.isBlank()) return
        hookLoading.add(ep)
        val key = settings.deepseekKey
        scope.launch {
            try {
                val h = withContext(Dispatchers.IO) { HookClient.hook(key, text) }
                settings.setHook(b.id, ep, org.json.JSONObject().put("hook", h.text).put("mood", h.mood).toString())
                if (book?.id == b.id) onReady(h)
            } catch (_: Exception) {
                // крючок — приятное дополнение, без него чтение продолжается
            } finally {
                hookLoading.remove(ep)
            }
        }
    }

    private fun maybeHook(i: Int, showCard: Boolean) {
        val ep = i / EPISODE
        if (ep == hookedEpisode) return
        hookedEpisode = ep
        loadHook(ep) { h -> if (i / EPISODE == currentIndex() / EPISODE) applyHook(ep, h, showCard) }
    }

    private fun prefetchHook(ep: Int) = loadHook(ep) {}

    fun dismissHook() = state.update { it.copy(hook = null) }

    private fun resetEpisodeStats() {
        epOk = 0; epTotal = 0; epBestCombo = state.value.combo
    }

    private fun finishEpisode(ep: Int) {
        Haptics.episode()
        state.update { it.copy(episodeDone = EpisodeSummary(ep, epOk, epTotal, epBestCombo)) }
        resetEpisodeStats()
        scope.launch {
            delay(5000)
            state.update { if (it.episodeDone?.episode == ep) it.copy(episodeDone = null) else it }
        }
    }

    // ---------- микро-вопросы, комбо и адаптивная скорость ----------

    private fun maybeMicro() {
        if (!settings.microEnabled || microLoading) return
        val st = state.value
        if (st.micro != null || st.quiz != null) return
        if (microSec < settings.microMinutes * 60) return
        val b = book ?: return
        val cur = currentIndex()
        val sb = StringBuilder()
        for (k in microFrom.coerceAtLeast(0) until cur.coerceAtMost(b.segments.size)) sb.append(b.segments[k]).append('\n')
        // из текущего фрагмента берём только уже прозвучавшую часть
        position(cur)?.let { (pos, dur) ->
            if (dur > 0f) {
                val t = b.segments.getOrNull(cur) ?: ""
                sb.append(t.take((t.length * (pos / dur)).toInt().coerceIn(0, t.length)))
            }
        }
        val text = sb.toString()
        if (text.length < 300) return
        microLoading = true
        val key = settings.deepseekKey
        scope.launch {
            try {
                val q = withContext(Dispatchers.IO) { MicroClient.question(key, text) }
                if (state.value.quiz == null) {
                    state.update { it.copy(micro = MicroState(q)) }
                    Haptics.tick()
                }
            } catch (_: Exception) {
                microSec = settings.microMinutes * 60 - 60   // попробуем снова через минуту
            } finally {
                microLoading = false
            }
        }
    }

    fun answerMicro(picked: Int) {
        val m = state.value.micro ?: return
        if (m.picked != null) return
        val right = picked == m.q.answer
        var combo = state.value.combo
        var delta = 0f
        epTotal++
        if (right) {
            combo++
            epOk++
            epBestCombo = maxOf(epBestCombo, combo)
            Haptics.success()
            if (settings.adaptive && combo % 2 == 0) delta = 0.1f
        } else {
            combo = 0
            Haptics.fail()
            if (settings.adaptive && settings.speed > 1.0f) delta = -0.1f
        }
        if (delta != 0f) setSpeed(settings.speed + delta)
        settings.addHistory(
            HistoryItem(System.currentTimeMillis(), settings.speed - delta, if (right) 1 else 0, 1, "micro", settings.bgMode)
        )
        state.update { it.copy(micro = m.copy(picked = picked, speedDelta = delta), combo = combo) }
        scope.launch {
            delay(1800)
            closeMicro()
        }
    }

    /** Вопрос висел без ответа — убираем без штрафа. */
    fun dismissMicro() {
        if (state.value.micro?.picked == null) closeMicro()
    }

    private fun closeMicro() {
        microSec = 0
        microFrom = currentIndex()
        state.update { it.copy(micro = null) }
        refresh()
    }

    // ---------- цитаты ----------

    fun likeSentence(segment: Int, text: String): Boolean {
        val b = book ?: return false
        val added = Library.addQuote(app, b.id, Quote(System.currentTimeMillis(), segment, text.trim()))
        Haptics.like()
        return added
    }

    fun currentBookId(): String? = book?.id

    /** Вызывается после сохранения настроек. */
    fun applySettings() = refresh()

    private fun refresh() {
        state.update {
            it.copy(
                playing = player.isPlaying,
                buffering = wantPlay && !player.isPlaying && it.quiz == null && it.error == null,
                listenedSec = listenedSec,
                microSec = microSec,
            )
        }
        Ambient.update(Ambient.resolve(settings.ambient, state.value.mood), player.isPlaying, settings.ambientVolume)
    }
}
