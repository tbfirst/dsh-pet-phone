package com.dshpet.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：只做两件事——把进程钉住、把通知栏按钮接上。
 * 宠物窗口本身由 PetRuntime 管理（同一个进程里的单例）。
 */
class PetService : Service() {

    override fun onCreate() {
        super.onCreate()
        PetRuntime.init(this)
        createChannel()
        Diag.i("Service", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                PetRuntime.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CHAT -> {
                PetRuntime.openChat()
            }
            ACTION_WHISPER -> {
                PetRuntime.whisperNow()
            }
            else -> {
                val count = PetRuntime.prefs().petCount
                PetRuntime.start(count)
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        }.onFailure { Diag.e("Service", "startForeground 失败", it) }
    }

    private fun buildNotification(): Notification {
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val chat = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java).setAction(ACTION_CHAT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val say = PendingIntent.getService(
            this, 2,
            Intent(this, PetService::class.java).setAction(ACTION_WHISPER),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(content)
            .addAction(0, getString(R.string.action_chat), chat)
            .addAction(0, "说句话", say)
            .addAction(0, getString(R.string.action_hide), stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        Diag.i("Service", "onDestroy")
        PetRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "pet_alive"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.dshpet.phone.action.START"
        const val ACTION_STOP = "com.dshpet.phone.action.STOP"
        const val ACTION_CHAT = "com.dshpet.phone.action.CHAT"
        const val ACTION_WHISPER = "com.dshpet.phone.action.WHISPER"

        fun start(ctx: Context) {
            val i = Intent(ctx, PetService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, PetService::class.java).setAction(ACTION_STOP)
            runCatching { ctx.startService(i) }
        }
    }
}
