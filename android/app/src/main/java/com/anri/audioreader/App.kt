package com.anri.audioreader

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Engine.init(this)
    }
}

/** Держит воспроизведение в фоне и показывает уведомление с кнопками. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, Engine.player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()   // сам плеер живёт в Engine и не освобождается
        session = null
        super.onDestroy()
    }
}

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        controller = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()
        setContent { AppRoot() }
    }

    override fun onDestroy() {
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

/** Если приложение упадёт, причина сохранится и покажется при следующем запуске. */
object CrashLog {
    private lateinit var file: java.io.File

    fun install(ctx: android.content.Context) {
        file = java.io.File(ctx.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                file.writeText("Версия ${BuildConfigInfo.version(ctx)}\nПоток: ${t.name}\n\n" + e.stackTraceToString().take(6000))
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun read(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
    fun clear() { runCatching { file.delete() } }
}

object BuildConfigInfo {
    fun version(ctx: android.content.Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
