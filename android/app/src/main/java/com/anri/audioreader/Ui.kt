package com.anri.audioreader

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val SPEAKERS = listOf(
    "xenia" to "Ксения",
    "kseniya" to "Ксения 2",
    "baya" to "Бая",
    "aidar" to "Айдар",
    "eugene" to "Евгений",
)

private val GOOD = Color(0xFF3E9B57)

private fun fmtSpeed(v: Float) = "х" + String.format(Locale.US, "%.1f", v).replace('.', ',')

@Composable
fun AppRoot() {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFF6CC0B1), secondary = Color(0xFF6CC0B1))
    else
        lightColorScheme(primary = Color(0xFF23685D), secondary = Color(0xFF23685D))

    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by rememberSaveable {
                mutableStateOf(if (Engine.state.value.bookId != null) "player" else "library")
            }
            BackHandler(enabled = screen != "library") { screen = "library" }
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                when (screen) {
                    "player" -> PlayerScreen(onBack = { screen = "library" })
                    "settings" -> SettingsScreen(onBack = { screen = "library" })
                    else -> LibraryScreen(
                        onOpen = { id -> Engine.open(id); screen = "player" },
                        onSettings = { screen = "settings" },
                    )
                }
            }
        }
    }
}

// ---------------- Книги ----------------

@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf(Library.list(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val current by Engine.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            try {
                withContext(Dispatchers.IO) {
                    val (title, paras) = Parsers.parse(ctx, uri)
                    val segments = Segmenter.split(paras)
                    if (segments.isEmpty()) throw IllegalStateException("в файле не найден текст")
                    Library.save(ctx, title, segments)
                }
                books = Library.list(ctx)
            } catch (e: Exception) {
                message = "Не удалось открыть файл: ${e.message}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Книги", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("Настройки") }
        }
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Загружаю книгу…" else "Добавить книгу: FB2, EPUB, TXT")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (Engine.settings.serverUrl.isBlank()) {
            Text(
                "Сначала укажи адрес сервера озвучки в настройках.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (books.isEmpty() && !busy) {
            Text("Пока пусто. Добавь книгу из памяти телефона.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(books, key = { it.id }) { b ->
                val pos = if (current.bookId == b.id) current.index else Engine.settings.getProgress(b.id)
                Card(Modifier.fillMaxWidth().clickable { onOpen(b.id) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { if (b.count > 0) pos.toFloat() / b.count else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${pos * 100 / maxOf(1, b.count)}% · ${b.count} фрагментов",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (confirmDelete == b.id) {
                                TextButton(onClick = {
                                    Engine.forget(b.id)
                                    Library.delete(ctx, b.id)
                                    books = Library.list(ctx)
                                    confirmDelete = null
                                }) { Text("Точно удалить", color = MaterialTheme.colorScheme.error) }
                            } else {
                                TextButton(onClick = { confirmDelete = b.id }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Плеер ----------------

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val s by Engine.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("← Книги") }
        Text(s.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
        LinearProgressIndicator(
            progress = { if (s.count > 0) (s.index + 1).toFloat() / s.count else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Фрагмент ${s.index + 1} из ${s.count}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        key(s.index) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(Engine.segmentText(s.index), style = MaterialTheme.typography.bodyLarge)
            }
        }

        when {
            s.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { Engine.retry() }) { Text("Повторить") }
            }
            s.buffering -> Text("Озвучиваю текст…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { Engine.setSpeed(s.speed - 0.1f) }, enabled = s.speed > 0.5f) { Text("−0,1") }
            Text(
                fmtSpeed(s.speed),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { Engine.setSpeed(s.speed + 0.1f) }, enabled = s.speed < 8f) { Text("+0,1") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { Engine.prev() }, enabled = s.index > 0) { Text("Назад") }
            val active = s.playing || s.buffering
            Button(
                onClick = { if (active) Engine.pause() else Engine.play() },
                modifier = Modifier.height(56.dp),
            ) { Text(if (active) "Пауза" else "Слушать", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(onClick = { Engine.next() }, enabled = s.index < s.count - 1) { Text("Вперёд") }
        }

        val quizLine = if (Engine.settings.quizEnabled) {
            val left = maxOf(0, Engine.settings.quizMinutes * 60 - s.listenedSec)
            "Опрос через ${left / 60}:${(left % 60).toString().padStart(2, '0')}"
        } else {
            "Опросы выключены. Включи их в настройках."
        }
        Text(quizLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    s.quiz?.let { QuizDialog(it) }
}

@Composable
fun QuizDialog(q: QuizState) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(
                Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Проверка понимания", style = MaterialTheme.typography.titleLarge)
                when {
                    q.loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text("Составляю вопросы по прослушанному…")
                        }
                        TextButton(onClick = { Engine.skipQuiz() }) { Text("Пропустить опрос") }
                    }
                    q.error != null -> {
                        Text("Не получилось: ${q.error}", color = MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(onClick = { Engine.requestQuiz() }) { Text("Повторить") }
                            TextButton(onClick = { Engine.skipQuiz() }) { Text("Пропустить") }
                        }
                    }
                    else -> QuizBody(q.questions)
                }
            }
        }
    }
}

@Composable
private fun QuizBody(questions: List<Question>) {
    val picks = remember(questions) { mutableStateListOf<Int?>().apply { repeat(questions.size) { add(null) } } }
    var checked by remember(questions) { mutableStateOf(false) }

    questions.forEachIndexed { qi, item ->
        Text("${qi + 1}. ${item.q}", fontWeight = FontWeight.SemiBold)
        item.options.forEachIndexed { oi, opt ->
            val color = when {
                checked && oi == item.answer -> GOOD
                checked && picks[qi] == oi -> MaterialTheme.colorScheme.error
                else -> Color.Unspecified
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = picks[qi] == oi, enabled = !checked, onClick = { picks[qi] = oi })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = picks[qi] == oi, onClick = null, enabled = !checked)
                Text(opt, color = color, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    val ok = questions.indices.count { picks[it] == questions[it].answer }
    if (!checked) {
        Button(
            onClick = { checked = true },
            enabled = picks.none { it == null },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Проверить") }
    } else {
        Text("Верно $ok из ${questions.size}", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { Engine.finishQuiz(ok, questions.size) }, modifier = Modifier.fillMaxWidth()) {
            Text("Слушать дальше")
        }
    }
}

// ---------------- Настройки ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val st = Engine.settings
    var server by remember { mutableStateOf(st.serverUrl) }
    var token by remember { mutableStateOf(st.serverToken) }
    var speaker by remember { mutableStateOf(st.speaker) }
    var key by remember { mutableStateOf(st.deepseekKey) }
    var minutes by remember { mutableStateOf(st.quizMinutes.toString()) }
    var count by remember { mutableStateOf(st.quizCount.toString()) }
    var saved by remember { mutableStateOf(false) }
    var cacheNote by remember { mutableStateOf<String?>(null) }
    val history = remember { st.history() }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Книги") }
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)

        Text("Сервер озвучки", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = server, onValueChange = { server = it; saved = false },
            label = { Text("Адрес, например http://1.2.3.4:8010") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it; saved = false },
            label = { Text("Токен сервера") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Text("Голос", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEAKERS.forEach { (id, name) ->
                FilterChip(selected = speaker == id, onClick = { speaker = id; saved = false }, label = { Text(name) })
            }
        }

        Text("Опросы", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = key, onValueChange = { key = it; saved = false },
            label = { Text("Ключ DeepSeek API") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = minutes, onValueChange = { v -> minutes = v.filter { it.isDigit() }.take(3); saved = false },
            label = { Text("Опрос каждые N минут (0 — выключить)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = count, onValueChange = { v -> count = v.filter { it.isDigit() }.take(1); saved = false },
            label = { Text("Вопросов за раз, от 1 до 5") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = {
            st.serverUrl = server
            st.serverToken = token
            st.speaker = speaker
            st.deepseekKey = key
            st.quizMinutes = minutes.toIntOrNull() ?: 0
            st.quizCount = (count.toIntOrNull() ?: 3).coerceIn(1, 5)
            saved = true
        }, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Сохранено" else "Сохранить") }

        OutlinedButton(onClick = { Engine.clearAudioCache(); cacheNote = "Скачанное аудио удалено" }) {
            Text("Очистить скачанное аудио")
        }
        cacheNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        Text("Понимание по скоростям", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) {
            Text("Здесь появится доля верных ответов на каждой скорости, когда пройдёшь первые опросы.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.groupBy { (it.speed * 10).roundToInt() }.toSortedMap().forEach { (tenths, list) ->
                val ok = list.sumOf { it.ok }
                val total = list.sumOf { it.total }
                val pct = if (total > 0) ok * 100 / total else 0
                Row(Modifier.fillMaxWidth()) {
                    Text(fmtSpeed(tenths / 10f), modifier = Modifier.weight(1f))
                    Text("$pct% верно", color = if (pct >= 70) GOOD else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    Text("опросов: ${list.size}", modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val df = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
            Text("Последние опросы", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            history.takeLast(10).reversed().forEach {
                Text("${df.format(Date(it.time))} · ${fmtSpeed(it.speed)} · ${it.ok} из ${it.total}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
