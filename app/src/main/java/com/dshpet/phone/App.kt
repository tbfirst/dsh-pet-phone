package com.dshpet.phone

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        PetRuntime.init(this)
    }

    /** 未捕获异常落盘：悬浮窗/前台服务出问题时，至少能在设置页看到一句为什么 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                val f = File(filesDir, "last_crash.txt")
                f.writeText("$stamp\nthread=${thread.name}\n\n${sw}")
                Diag.e("Crash", "未捕获异常已落盘", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
