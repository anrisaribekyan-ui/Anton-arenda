@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.anri.audioreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEAKERS = listOf(
    "xenia" to "Ксения",
    "kseniya" to "Ксения 2",
    "baya" to "Бая",
    "aidar" to "Айдар",
    "eugene" to "Евгений",
)

private val PRESETS = listOf(1f, 1.5f, 2f, 2.5f, 3f, 3.5f)

private val GOOD = Color(0xFF3E9B57)

private fun fmtSpeed(v: Float) = "х" + String.format(Locale.US, "%.1f", v).replace('.', ',')

private fun percent(pos: Int, count: Int) = if (count > 0) pos * 100 / count else 0

@Composable
fun AppRoot() {
    ReaderTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by rememberSaveable {
                mutableStateOf(if (Engine.state.value.bookId != null) "player" else "library")
            }
            BackHandler(enabled = screen != "library") { screen = if (screen == "quotes") "player" else "library" }
            var crash by remember { mutableStateOf(CrashLog.read()) }
            crash?.let { text -> CrashDialog(text) { CrashLog.clear(); crash = null } }
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                when (screen) {
                    "player" -> PlayerScreen(onBack = { screen = "library" }, onQuotes = { screen = "quotes" })
                    "quotes" -> QuotesScreen(onBack = { screen = "player" })
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

// ======================= Библиотека =======================

@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
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

    fun progressOf(b: BookMeta) = if (current.bookId == b.id) current.index else Engine.settings.getProgress(b.id)
    val continueBook = books.firstOrNull { it.id == current.bookId }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Библиотека", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (books.isEmpty()) "Пока ни одной книги" else "Книг: ${books.size}",
                            color = cs.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Настройки") }
                }
            }

            if (Engine.settings.serverUrl.isBlank()) item {
                Notice(
                    text = "Подключи сервер озвучки, чтобы книги начали звучать.",
                    action = "Открыть настройки",
                    onAction = onSettings,
                )
            }

            message?.let { m -> item { Text(m, color = cs.error) } }

            continueBook?.let { b ->
                item {
                    val pos = progressOf(b)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("ПРОДОЛЖИТЬ", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp,
                                color = cs.onPrimaryContainer.copy(alpha = 0.7f))
                            Text(b.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif,
                                color = cs.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ThinProgress(percent(pos, b.count) / 100f, cs.primary, cs.onPrimaryContainer.copy(alpha = 0.15f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${percent(pos, b.count)}% · фрагмент ${pos + 1} из ${b.count}",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer,
                                    modifier = Modifier.weight(1f))
                                Button(onClick = { onOpen(b.id) }) { Text("Слушать") }
                            }
                        }
                    }
                }
            }

            if (books.isEmpty() && !busy) item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Добавь первую книгу", style = MaterialTheme.typography.titleMedium)
                    Text("FB2, EPUB или TXT из памяти телефона", color = cs.onSurfaceVariant)
                }
            }

            items(books.filter { it.id != continueBook?.id }, key = { it.id }) { b ->
                BookRow(
                    book = b,
                    pos = progressOf(b),
                    confirming = confirmDelete == b.id,
                    onOpen = { onOpen(b.id) },
                    onDeleteAsk = { confirmDelete = b.id },
                    onDeleteCancel = { confirmDelete = null },
                    onDeleteConfirm = {
                        Engine.forget(b.id)
                        Library.delete(ctx, b.id)
                        books = Library.list(ctx)
                        confirmDelete = null
                    },
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
            icon = {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Add, contentDescription = null)
            },
            text = { Text(if (busy) "Загружаю книгу…" else "Добавить книгу") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun BookRow(
    book: BookMeta,
    pos: Int,
    confirming: Boolean,
    onOpen: () -> Unit,
    onDeleteAsk: () -> Unit,
    onDeleteCancel: () -> Unit,
    onDeleteConfirm: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tints = listOf(cs.primaryContainer to cs.onPrimaryContainer, cs.tertiaryContainer to cs.onTertiaryContainer,
        cs.secondaryContainer to cs.onSecondaryContainer)
    val (bg, fg) = tints[abs(book.title.hashCode()) % tints.size]

    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 46.dp, height = 62.dp).clip(RoundedCornerShape(8.dp)).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(book.title.trim().take(1).uppercase(), color = fg, fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ThinProgress(percent(pos, book.count) / 100f, cs.primary, cs.surfaceVariant)
                Text("${percent(pos, book.count)}% · ${book.count} фрагментов",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (confirming) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onDeleteConfirm) { Text("Удалить", color = cs.error) }
                    TextButton(onClick = onDeleteCancel) { Text("Отмена") }
                }
            } else {
                IconButton(onClick = onDeleteAsk) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить книгу", tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ThinProgress(value: Float, color: Color, track: Color) {
    LinearProgressIndicator(
        progress = { value.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(4.dp),
        color = color,
        trackColor = track,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = cs.onTertiaryContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action, color = cs.onTertiaryContainer, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ======================= Плеер =======================

@Composable
fun PlayerScreen(onBack: () -> Unit, onQuotes: () -> Unit) {
    val s by Engine.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val st = Engine.settings
    val scope = rememberCoroutineScope()
    val pulse = remember { Animatable(0f) }
    val onWord: () -> Unit = {
        scope.launch {
            pulse.snapTo((pulse.value + 0.5f).coerceAtMost(1f))
            pulse.animateTo(0f, tween(700))
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (st.bgMode == "gradient") BreathingBackground(s.mood, { pulse.value }, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К книгам") }
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Серия ${s.index / Engine.EPISODE + 1} · фрагмент ${s.index + 1} из ${s.count} · ${percent(s.index + 1, s.count)}%",
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onQuotes) { Icon(Icons.Filled.Favorite, contentDescription = "Цитаты", tint = cs.tertiary) }
            }
            if (!st.landscape) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    ThinProgress(if (s.count > 0) (s.index + 1f) / s.count else 0f, cs.primary, cs.surfaceVariant)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(s.index) {
                    KaraokeReader(s.index, Engine.segmentText(s.index), Modifier.fillMaxSize(), onWord)
                }
                Column(Modifier.align(Alignment.TopCenter)) {
                    s.hook?.let { HookCardView(it) }
                    s.episodeDone?.let { EpisodeBanner(it) }
                }
                s.micro?.let { MicroCard(it, Modifier.align(Alignment.BottomCenter)) }
            }

            if (st.bgMode == "split") {
                FlowPanel(s.mood, Modifier.fillMaxWidth().height(140.dp))
            }
            if (st.landscape) {
                LandscapeProgress(
                    seed = s.bookId ?: "",
                    progress = if (s.count > 0) (s.index + 0.5f) / s.count else 0f,
                    line = cs.primary,
                    fill = cs.primary.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ControlPanel(s)
        }
    }

    s.quiz?.let { QuizDialog(it) }
}

@Composable
private fun HookCardView(h: HookCard) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(h) {
        delay(14000)
        Engine.dismissHook()
    }
    Surface(
        onClick = { Engine.dismissHook() },
        color = cs.tertiaryContainer,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(12.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("СЕРИЯ ${h.episode + 1} · ЧТО ДАЛЬШЕ", style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp, color = cs.onTertiaryContainer.copy(alpha = 0.75f))
            Text(h.text, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif,
                color = cs.onTertiaryContainer)
        }
    }
}

@Composable
private fun EpisodeBanner(e: EpisodeSummary) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.primaryContainer,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Серия ${e.episode + 1} пройдена", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
            val parts = buildList {
                if (e.total > 0) add("понимание ${e.ok} из ${e.total}")
                if (e.bestCombo > 1) add("лучшее комбо ×${e.bestCombo}")
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun MicroCard(m: MicroState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(m.q) {
        delay(20000)
        Engine.dismissMicro()
    }
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 14.dp,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = modifier.padding(12.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("БЫСТРЫЙ ВОПРОС", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp,
                    color = cs.tertiary, modifier = Modifier.weight(1f))
                if (m.picked == null) {
                    Text("звук не останавливается", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            Text(m.q.q, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                m.q.options.forEachIndexed { i, opt ->
                    val answered = m.picked != null
                    val isRight = i == m.q.answer
                    val bg = when {
                        answered && isRight -> GOOD.copy(alpha = 0.18f)
                        answered && i == m.picked -> cs.error.copy(alpha = 0.15f)
                        else -> cs.surfaceVariant
                    }
                    val fg = when {
                        answered && isRight -> GOOD
                        answered && i == m.picked -> cs.error
                        else -> cs.onSurface
                    }
                    Surface(
                        onClick = { Engine.answerMicro(i) },
                        enabled = !answered,
                        shape = RoundedCornerShape(16.dp),
                        color = bg,
                        modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                    ) {
                        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                            Text(opt, color = fg, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            m.picked?.let { p ->
                val right = p == m.q.answer
                val speedNote = when {
                    m.speedDelta > 0f -> " Скорость +0,1"
                    m.speedDelta < 0f -> " Скорость −0,1"
                    else -> ""
                }
                Text(
                    (if (right) "Верно!" else "Мимо.") + speedNote,
                    color = if (right) GOOD else cs.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Текст фрагмента: прочитанные слова яркие, текущее — маркером, строка — подложкой. Двойной тап — в цитаты. */
@Composable
fun KaraokeReader(index: Int, text: String, modifier: Modifier, onWord: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val words = remember(text) { Karaoke.words(text) }
    var sentences by remember { mutableStateOf<List<Sentence>?>(null) }
    var pos by remember { mutableFloatStateOf(-1f) }
    var dur by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(index, text) {
        var tick = 0
        while (true) {
            if (sentences == null && tick % 20 == 0) {
                sentences = withContext(Dispatchers.IO) { Engine.timing(index) }
            }
            val p = Engine.position(index)
            if (p != null) { pos = p.first; dur = p.second } else pos = -1f
            tick++
            delay(50)
        }
    }

    val starts = remember(words, sentences, dur) {
        val ss = sentences ?: if (dur > 0f) Karaoke.fallback(text, dur) else null
        ss?.let { Karaoke.schedule(text, words, it) }
    }
    val cur = if (starts != null && pos >= 0f && words.isNotEmpty()) Karaoke.current(starts, pos) else -1
    LaunchedEffect(cur) { if (cur >= 0) onWord() }

    val upcoming = cs.onSurface.copy(alpha = 0.42f)
    val annotated = remember(text, cur, cs) {
        buildAnnotatedString {
            append(text)
            if (cur >= 0) words.forEachIndexed { i, w ->
                val style = when {
                    i < cur -> SpanStyle(color = cs.onSurface)
                    i == cur -> SpanStyle(color = cs.onTertiaryContainer, background = cs.tertiaryContainer,
                        fontWeight = FontWeight.SemiBold)
                    else -> SpanStyle(color = upcoming)
                }
                addStyle(style, w.start, w.end)
            }
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val padH: Dp = 22.dp
    val padV: Dp = 18.dp
    val curLine = layout?.let { l ->
        if (cur >= 0 && words[cur].start < l.layoutInput.text.length) l.getLineForOffset(words[cur].start) else null
    }

    // Держим текущую строку в верхней трети экрана
    LaunchedEffect(curLine) {
        val l = layout ?: return@LaunchedEffect
        val line = curLine ?: return@LaunchedEffect
        val top = l.getLineTop(line) + with(density) { padV.toPx() }
        scroll.animateScrollTo((top - viewport * 0.3f).roundToInt().coerceAtLeast(0))
    }

    // Лайк: сердечко в месте тапа и короткая подпись
    var heartAt by remember { mutableStateOf<Offset?>(null) }
    val heart = remember { Animatable(0f) }
    var likeNote by remember { mutableStateOf<String?>(null) }

    val band = cs.primary.copy(alpha = 0.09f)
    Box(modifier.fillMaxWidth().onSizeChanged { viewport = it.height }) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Text(
                text = annotated,
                style = ReadingStyle.copy(color = cs.onSurface),
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(text) {
                        detectTapGestures(onDoubleTap = { off ->
                            val l = layout ?: return@detectTapGestures
                            val o = l.getOffsetForPosition(Offset(off.x - padH.toPx(), off.y - padV.toPx()))
                            val (a, b) = sentenceAround(text, sentences, o)
                            if (b > a) {
                                val added = Engine.likeSentence(index, text.substring(a, b))
                                likeNote = if (added) "Фраза сохранена в цитаты" else "Эта фраза уже в цитатах"
                                heartAt = off
                                scope.launch {
                                    heart.snapTo(0f)
                                    heart.animateTo(1f, tween(900))
                                    heartAt = null
                                }
                                scope.launch {
                                    delay(1800)
                                    likeNote = null
                                }
                            }
                        })
                    }
                    .drawBehind {
                        val l = layout
                        val line = curLine
                        if (l != null && line != null) {
                            val ext = 3.dp.toPx()
                            val inset = (padH / 2).toPx()
                            drawRoundRect(
                                color = band,
                                topLeft = Offset(inset, l.getLineTop(line) + padV.toPx() - ext),
                                size = Size(size.width - inset * 2, l.getLineBottom(line) - l.getLineTop(line) + ext * 2),
                                cornerRadius = CornerRadius(12.dp.toPx()),
                            )
                        }
                    }
                    .padding(horizontal = padH, vertical = padV),
            )
            heartAt?.let { p ->
                val k = heart.value
                Text(
                    "♥",
                    color = cs.tertiary,
                    fontSize = 46.sp,
                    modifier = Modifier
                        .offset { IntOffset((p.x - 40).roundToInt(), (p.y - 90 - k * 120).roundToInt()) }
                        .graphicsLayer {
                            alpha = 1f - k
                            scaleX = 0.7f + k * 0.8f
                            scaleY = 0.7f + k * 0.8f
                        },
                )
            }
        }
        likeNote?.let {
            Surface(
                color = cs.inverseSurface,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                Text(it, color = cs.inverseOnSurface, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/** Границы предложения вокруг позиции: по таймингам сервера или по знакам препинания. */
private fun sentenceAround(text: String, sentences: List<Sentence>?, offset: Int): Pair<Int, Int> {
    sentences?.firstOrNull { offset >= it.charStart && offset <= it.charEnd }?.let {
        return it.charStart to it.charEnd.coerceAtMost(text.length)
    }
    val o = offset.coerceIn(0, text.length)
    var a = o
    while (a > 0 && text[a - 1] !in ".!?…\n") a--
    var b = o
    while (b < text.length && text[b] !in ".!?…\n") b++
    while (b < text.length && text[b] in ".!?…»\"") b++
    while (a < b && text[a].isWhitespace()) a++
    return a to b
}

@Composable
private fun ControlPanel(s: UiState) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // строка состояния
            Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                when {
                    s.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.error ?: "", color = cs.error, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Engine.retry() }) { Text("Повторить") }
                    }
                    s.buffering -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Озвучиваю текст…", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val st = Engine.settings
                        fun mmss(sec: Int) = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
                        if (s.combo > 0) {
                            Chip("Комбо ×${s.combo}", cs.tertiaryContainer, cs.onTertiaryContainer)
                        }
                        if (st.microEnabled) {
                            Chip("Вопрос через ${mmss(maxOf(0, st.microMinutes * 60 - s.microSec))}",
                                cs.secondaryContainer, cs.onSecondaryContainer)
                        }
                        if (st.quizEnabled) {
                            Chip("Опрос через ${mmss(maxOf(0, st.quizMinutes * 60 - s.listenedSec))}",
                                cs.secondaryContainer, cs.onSecondaryContainer)
                        }
                        if (!st.microEnabled && !st.quizEnabled && s.combo == 0) {
                            Chip("Вопросы выключены", cs.secondaryContainer, cs.onSecondaryContainer)
                        }
                    }
                }
            }

            // скорость
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(size = 48.dp, filled = false, enabled = s.speed > 0.5f, onClick = { Engine.setSpeed(s.speed - 0.1f) }) {
                    Text("−", fontSize = 26.sp, color = cs.onSurface)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtSpeed(s.speed), fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(
                        if (s.baseWpm > 0f) "≈ ${(s.baseWpm * s.speed).roundToInt()} слов/мин" else "скорость",
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    )
                }
                RoundButton(size = 48.dp, filled = false, enabled = s.speed < 8f, onClick = { Engine.setSpeed(s.speed + 0.1f) }) {
                    Text("+", fontSize = 24.sp, color = cs.onSurface)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEach { p ->
                    val selected = abs(s.speed - p) < 0.05f
                    Surface(
                        onClick = { Engine.setSpeed(p) },
                        shape = CircleShape,
                        color = if (selected) cs.primary else cs.surfaceVariant,
                        modifier = Modifier.weight(1f).height(34.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(fmtSpeed(p).drop(1), style = MaterialTheme.typography.labelLarge,
                                color = if (selected) cs.onPrimary else cs.onSurfaceVariant)
                        }
                    }
                }
            }

            // управление
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(size = 56.dp, filled = false, enabled = s.index > 0, onClick = { Engine.prev() }) {
                    SkipGlyph(forward = false, color = cs.onSurface, modifier = Modifier.size(22.dp))
                }
                val active = s.playing || s.buffering
                RoundButton(size = 78.dp, filled = true, enabled = s.count > 0,
                    onClick = { if (active) Engine.pause() else Engine.play() }) {
                    PlayPauseGlyph(playing = active, color = cs.onPrimary, modifier = Modifier.size(30.dp))
                }
                RoundButton(size = 56.dp, filled = false, enabled = s.index < s.count - 1, onClick = { Engine.next() }) {
                    SkipGlyph(forward = true, color = cs.onSurface, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = CircleShape) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun RoundButton(size: Dp, filled: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (filled) cs.primary else cs.surfaceVariant,
        modifier = Modifier.size(size).alpha(if (enabled) 1f else 0.4f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

// ======================= Опрос =======================

@Composable
fun QuizDialog(q: QuizState) {
    val cs = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.surface, modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(
                Modifier.padding(22.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("ПРОВЕРКА ПОНИМАНИЯ", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp,
                    color = cs.tertiary)
                when {
                    q.loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("Составляю вопросы по прослушанному…")
                        }
                        TextButton(onClick = { Engine.skipQuiz() }) { Text("Пропустить опрос") }
                    }
                    q.error != null -> {
                        Text("Не получилось: ${q.error}", color = cs.error)
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
    val cs = MaterialTheme.colorScheme
    val picks = remember(questions) { mutableStateListOf<Int?>().apply { repeat(questions.size) { add(null) } } }
    var checked by remember(questions) { mutableStateOf(false) }

    questions.forEachIndexed { qi, item ->
        Text("${qi + 1}. ${item.q}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item.options.forEachIndexed { oi, opt ->
                val picked = picks[qi] == oi
                val right = checked && oi == item.answer
                val wrong = checked && picked && oi != item.answer
                val border = when {
                    right -> GOOD
                    wrong -> cs.error
                    picked -> cs.primary
                    else -> cs.outlineVariant
                }
                Surface(
                    onClick = { if (!checked) picks[qi] = oi },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(if (picked || right) 2.dp else 1.dp, border),
                    color = if (picked && !checked) cs.primaryContainer.copy(alpha = 0.5f) else cs.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked, onClick = null, enabled = !checked)
                        Text(opt, modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 10.dp),
                            color = when { right -> GOOD; wrong -> cs.error; else -> cs.onSurface })
                    }
                }
            }
        }
    }
    val ok = questions.indices.count { picks[it] == questions[it].answer }
    if (!checked) {
        Button(onClick = { checked = true }, enabled = picks.none { it == null }, modifier = Modifier.fillMaxWidth()) {
            Text("Проверить")
        }
    } else {
        Text("Верно $ok из ${questions.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = if (ok * 10 >= questions.size * 7) GOOD else cs.error)
        Button(onClick = { Engine.finishQuiz(ok, questions.size) }, modifier = Modifier.fillMaxWidth()) {
            Text("Слушать дальше")
        }
    }
}

// ======================= Настройки =======================

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val st = Engine.settings
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(st.serverUrl) }
    var token by remember { mutableStateOf(st.serverToken) }
    var speaker by remember { mutableStateOf(st.speaker) }
    var key by remember { mutableStateOf(st.deepseekKey) }
    var minutes by remember { mutableStateOf(st.quizMinutes.toString()) }
    var count by remember { mutableStateOf(st.quizCount.toString()) }
    var micro by remember { mutableStateOf(st.microMinutes.toString()) }
    var adaptive by remember { mutableStateOf(st.adaptive) }
    var haptics by remember { mutableStateOf(st.haptics) }
    var hooks by remember { mutableStateOf(st.hooks) }
    var bgMode by remember { mutableStateOf(st.bgMode) }
    var landscape by remember { mutableStateOf(st.landscape) }
    var ambient by remember { mutableStateOf(st.ambient) }
    var volume by remember { mutableFloatStateOf(st.ambientVolume) }
    var saved by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    var cacheNote by remember { mutableStateOf<String?>(null) }
    val history = remember { st.history() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К книгам") }
            Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Section("Сервер озвучки") {
                OutlinedTextField(
                    value = server, onValueChange = { server = it; saved = false; check = null },
                    label = { Text("Адрес") }, placeholder = { Text("http://1.2.3.4:8010") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it; saved = false },
                    label = { Text("Токен") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        check = "Проверяю…"
                        scope.launch {
                            check = try {
                                val model = withContext(Dispatchers.IO) { checkServer(server) }
                                "Сервер на связи, модель $model"
                            } catch (e: Exception) {
                                "Нет связи: ${e.message}"
                            }
                        }
                    }) { Text("Проверить сервер") }
                    Spacer(Modifier.width(12.dp))
                    check?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Сервер")) GOOD else cs.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            Section("Голос") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SPEAKERS.forEach { (id, name) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { speaker = id; saved = false }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = speaker == id, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(name)
                        }
                    }
                }
            }

            Section("Опросы") {
                OutlinedTextField(
                    value = key, onValueChange = { key = it; saved = false },
                    label = { Text("Ключ DeepSeek API") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = minutes, onValueChange = { v -> minutes = v.filter { it.isDigit() }.take(3); saved = false },
                        label = { Text("Каждые, мин") }, singleLine = true, supportingText = { Text("0 — выключить") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = count, onValueChange = { v -> count = v.filter { it.isDigit() }.take(1); saved = false },
                        label = { Text("Вопросов") }, singleLine = true, supportingText = { Text("от 1 до 5") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
            }

            Section("Внимание") {
                OutlinedTextField(
                    value = micro, onValueChange = { v -> micro = v.filter { it.isDigit() }.take(2); saved = false },
                    label = { Text("Быстрый вопрос каждые, мин") }, singleLine = true,
                    supportingText = { Text("Один вопрос поверх звука, 0 — выключить") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow("Адаптивная скорость", "Два верных ответа подряд: +0,1. Ошибка: −0,1", adaptive) { adaptive = it; saved = false }
                ToggleRow("Крючок перед серией", "Интригующая фраза перед каждыми 10 фрагментами", hooks) { hooks = it; saved = false }
                ToggleRow("Вибрация", "Ответы, лайки и конец серии", haptics) { haptics = it; saved = false }
                ToggleRow("Пейзаж прогресса", "Линия гор, которая прорисовывается по мере чтения", landscape) { landscape = it; saved = false }
            }

            Section("Фон") {
                Choice(listOf("gradient" to "Дышащий градиент", "split" to "Сплит-скрин", "plain" to "Без фона"), bgMode) {
                    bgMode = it; saved = false
                }
            }

            Section("Фоновый звук") {
                Choice(
                    listOf("auto" to "Авто, по настроению серии", "brown" to "Коричневый шум", "rain" to "Дождь",
                        "fire" to "Камин", "off" to "Выключен"),
                    ambient,
                ) { ambient = it; saved = false }
                Text("Громкость", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Slider(value = volume, onValueChange = { volume = it; saved = false }, valueRange = 0.05f..1f)
            }

            Button(onClick = {
                st.microMinutes = micro.toIntOrNull() ?: 0
                st.adaptive = adaptive
                st.haptics = haptics
                st.hooks = hooks
                st.bgMode = bgMode
                st.landscape = landscape
                st.ambient = ambient
                st.ambientVolume = volume
                Engine.applySettings()
                st.serverUrl = server
                st.serverToken = token
                st.speaker = speaker
                st.deepseekKey = key
                st.quizMinutes = minutes.toIntOrNull() ?: 0
                st.quizCount = (count.toIntOrNull() ?: 3).coerceIn(1, 5)
                saved = true
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (saved) "Сохранено" else "Сохранить") }

            Section("Понимание по скоростям") {
                if (history.isEmpty()) {
                    Text("Здесь появится доля верных ответов на каждой скорости после первых опросов.",
                        color = cs.onSurfaceVariant)
                } else {
                    history.groupBy { (it.speed * 10).roundToInt() }.toSortedMap().forEach { (tenths, list) ->
                        val ok = list.sumOf { it.ok }
                        val total = list.sumOf { it.total }
                        val pct = if (total > 0) ok * 100 / total else 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtSpeed(tenths / 10f), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                            Box(Modifier.weight(1f)) {
                                ThinProgress(pct / 100f, if (pct >= 70) GOOD else cs.error, cs.surfaceVariant)
                            }
                            Text("$pct%", modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                        }
                    }
                    val byMode = history.filter { it.mode.isNotEmpty() }.groupBy { it.mode }
                    if (byMode.isNotEmpty()) {
                        HorizontalDivider(color = cs.outlineVariant)
                        Text("По фону", fontWeight = FontWeight.SemiBold)
                        val names = mapOf("gradient" to "Градиент", "split" to "Сплит-скрин", "plain" to "Без фона")
                        byMode.forEach { (mode, list) ->
                            val ok = list.sumOf { it.ok }
                            val total = list.sumOf { it.total }
                            val pct = if (total > 0) ok * 100 / total else 0
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(names[mode] ?: mode, modifier = Modifier.width(110.dp))
                                Box(Modifier.weight(1f)) {
                                    ThinProgress(pct / 100f, if (pct >= 70) GOOD else cs.error, cs.surfaceVariant)
                                }
                                Text("$pct%", modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                            }
                        }
                        Text("Ответов: ${byMode.values.sumOf { l -> l.sumOf { it.total } }}. Чем больше, тем точнее сравнение.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                    val df = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
                    history.takeLast(8).reversed().forEach {
                        Text("${df.format(Date(it.time))} · ${fmtSpeed(it.speed)} · ${it.ok} из ${it.total}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }

            FilledTonalButton(onClick = { Engine.clearAudioCache(); cacheNote = "Скачанное аудио удалено" }) {
                Text("Очистить скачанное аудио")
            }
            cacheNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!value) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (id, name) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == id, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                Text(name)
            }
        }
    }
}

// ======================= Цитаты =======================

@Composable
fun QuotesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val id = Engine.currentBookId()
    var quotes by remember { mutableStateOf(if (id != null) Library.quotes(ctx, id).reversed() else emptyList()) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Column {
                Text("Цитаты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(Engine.state.value.title, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (quotes.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Пока пусто", style = MaterialTheme.typography.titleMedium)
                Text("Дважды коснись фразы во время чтения, и она появится здесь.",
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(quotes, key = { it.time }) { q ->
                Card(colors = CardDefaults.cardColors(containerColor = cs.surface), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                        Box(Modifier.width(3.dp).height(48.dp).clip(CircleShape).background(cs.tertiary))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(q.text, style = ReadingStyle.copy(fontSize = 18.sp, lineHeight = 27.sp, color = cs.onSurface))
                            Text("Фрагмент ${q.segment + 1}", style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            if (id != null) {
                                Library.deleteQuote(ctx, id, q.time)
                                quotes = Library.quotes(ctx, id).reversed()
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = cs.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CrashDialog(text: String, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("В прошлый раз приложение упало", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Скопируй отчёт и пришли его в чат, по нему видно причину.", color = cs.onSurfaceVariant)
                Surface(color = cs.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(max = 220.dp)) {
                    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                        copied = true
                    }) { Text(if (copied) "Скопировано" else "Скопировать") }
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
            }
        }
    }
}
