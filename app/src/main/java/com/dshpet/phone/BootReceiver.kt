package com.dshpet.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 覆盖安装后按设置自动把宠物放出来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.autoStart) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Diag.w("Boot", "没有悬浮窗权限，跳过自启")
            return
        }
        runCatching { PetService.start(context) }
            .onFailure { Diag.w("Boot", "自启失败：${it.message}") }
    }
}
