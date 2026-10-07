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
)

/** Вся логика чтения: очередь фрагментов, загрузка озвучки, скорость, опросы. */
object Engine {
    private const val AHEAD = 4          // сколько фрагментов озвучивать заранее
    private const val KEEP_BEHIND = 10   // сколько прослушанных фрагментов хранить

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

    fun init(ctx: Context) {
        app = ctx.applicationContext
        settings = Settings(app)
        player = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setPlaybackSpeed(settings.speed)
        state.update { it.copy(speed = settings.speed) }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onIndexChanged()
            override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
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
                    refresh()
                }
            }
        }
    }

    fun segmentText(i: Int): String = book?.segments?.getOrNull(i) ?: ""

    private fun currentIndex(): Int =
        player.currentMediaItem?.mediaId?.toIntOrNull() ?: state.value.index

    private fun audioFile(id: String, i: Int) = File(app.cacheDir, "audio/$id/${settings.speaker}/$i.ogg")

    // ---------- открытие и навигация ----------

    fun open(id: String) {
        if (book?.id == id) return
        pause()
        val b = Library.load(app, id)
        book = b
        val pos = settings.getProgress(id).coerceIn(0, maxOf(0, b.segments.size - 1))
        state.value = UiState(bookId = id, title = b.title, count = b.segments.size, index = pos, speed = settings.speed)
        listenedSec = 0
        startAt(pos, play = false)
    }

    fun forget(id: String) {
        if (book?.id == id) {
            wantPlay = false
            generation++
            player.stop()
            player.clearMediaItems()
            book = null
            state.value = UiState(speed = settings.speed)
        }
        File(app.cacheDir, "audio/$id").deleteRecursively()
    }

    private fun startAt(i: Int, play: Boolean) {
        val b = book ?: return
        generation++
        wantPlay = play
        player.stop()
        player.clearMediaItems()
        queued = i - 1
        quizFrom = i
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
        File(app.cacheDir, "audio").deleteRecursively()
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
        settings.setProgress(b.id, i)
        state.update { it.copy(index = i) }
        audioFile(b.id, i - KEEP_BEHIND - 1).delete()
        fill()
        maybeQuiz(i)
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
        if (total > 0) settings.addHistory(HistoryItem(System.currentTimeMillis(), settings.speed, ok, total))
        closeQuiz()
    }

    fun skipQuiz() = closeQuiz()

    private fun closeQuiz() {
        listenedSec = 0
        quizFrom = currentIndex()
        state.update { it.copy(quiz = null) }
        play()
    }

    private fun refresh() {
        state.update {
            it.copy(
                playing = player.isPlaying,
                buffering = wantPlay && !player.isPlaying && it.quiz == null && it.error == null,
                listenedSec = listenedSec,
            )
        }
    }
}
