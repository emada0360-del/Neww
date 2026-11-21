package com.talabati.smartauto

import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.OutputStream
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * تنفيذ النقر عبر Shizuku بأقل تأخير:
 * نفتح عملية shell واحدة مرة واحدة ونرسل لها أوامر `input tap` عبر stdin،
 * وكل أمر يشتغل بالخلفية (&) فلا ننتظر انتهاء النقرة السابقة.
 */
object AdbTapExecutor {

    private const val REQUEST_CODE_SHIZUKU = 2026

    private var listenerRegistered = false
    private var lastTap: PointF? = null

    private val shellLock = Any()
    private var shellProcess: Process? = null
    private var shellOut: OutputStream? = null

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    AutomationStore.updateLog("تم منح صلاحية Shizuku بنجاح")
                } else {
                    AutomationStore.updateLog("تم رفض صلاحية Shizuku")
                }
            }
        }

    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureListener() {
        if (!listenerRegistered) {
            try {
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
                listenerRegistered = true
            } catch (_: Throwable) {
            }
        }
    }

    fun isShizukuAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!isShizukuAlive()) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE_SHIZUKU) {
        try {
            ensureListener()
            if (!isShizukuAlive()) {
                AutomationStore.updateLog("Shizuku غير شغّال — شغّل الخدمة أولاً")
                return
            }
            if (!hasPermission()) {
                Shizuku.requestPermission(requestCode)
            } else {
                AutomationStore.updateLog("صلاحية Shizuku مفعّلة مسبقاً")
            }
        } catch (e: Throwable) {
            AutomationStore.updateLog("تعذّر طلب إذن Shizuku: ${e.message ?: "خطأ"}")
        }
    }

    // ───────────────────────── عملية shell الدائمة ─────────────────────────

    private fun openShellLocked(): OutputStream? {
        ensureListener()
        if (!isShizukuAlive() || !hasPermission()) return null
        val method = newProcessMethod ?: return null
        val process = method.invoke(null, arrayOf("sh"), null, null) as? Process ?: return null
        shellProcess = process
        shellOut = process.outputStream
        return shellOut
    }

    private fun closeShellLocked() {
        try {
            shellOut?.close()
        } catch (_: Throwable) {
        }
        try {
            shellProcess?.destroy()
        } catch (_: Throwable) {
        }
        shellOut = null
        shellProcess = null
    }

    /** تهيئة مسبقة حتى لا تتأخر أول نقرة */
    fun warmUp() {
        try {
            synchronized(shellLock) {
                if (shellOut == null) openShellLocked()
            }
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        synchronized(shellLock) { closeShellLocked() }
    }

    private fun sendCommand(command: String): Boolean {
        synchronized(shellLock) {
            repeat(2) {
                try {
                    val out = shellOut ?: openShellLocked()
                    if (out == null) {
                        AutomationStore.updateLog("فشل النقر: Shizuku غير جاهز")
                        return false
                    }
                    out.write((command + "\n").toByteArray())
                    out.flush()
                    return true
                } catch (_: Throwable) {
                    closeShellLocked()
                }
            }
        }
        AutomationStore.updateLog("فشل إرسال أمر النقر")
        return false
    }

    private fun tapCommand(p: PointF): String {
        val x = p.x.toInt().coerceAtLeast(0)
        val y = p.y.toInt().coerceAtLeast(0)
        return "input tap $x $y >/dev/null 2>&1 &"
    }

    // ───────────────────────── النقر ─────────────────────────

    /** ضغطة (أو ضغطتان) على زر «موافق»، كل واحدة بإحداثيات عشوائية جديدة داخل حدود الزر */
    suspend fun executeAccept(rect: Rect, gapMs: Long) {
        withContext(Dispatchers.IO) {
            sendCommand(tapCommand(randomPoint(rect)))
            if (gapMs > 0L) {
                delay(gapMs + Random.nextLong(0L, 7L))
                sendCommand(tapCommand(randomPoint(rect)))
            }
        }
    }

    /** ضغطة على زر الرقم بإحداثيات عشوائية داخل الزر */
    suspend fun executeDigit(rect: Rect) {
        withContext(Dispatchers.IO) {
            sendCommand(tapCommand(randomPoint(rect)))
        }
    }

    /** نقطة عشوائية داخل المستطيل (بهامش عن الحواف) ومختلفة عن آخر نقرة */
    private fun randomPoint(rect: Rect): PointF {
        val mx = (rect.width() * 0.15f).toInt()
        val my = (rect.height() * 0.22f).toInt()
        val l = rect.left + mx
        val r = rect.right - mx
        val t = rect.top + my
        val b = rect.bottom - my
        if (r <= l || b <= t) return PointF(rect.exactCenterX(), rect.exactCenterY())

        val prev = lastTap
        val minDx = max(10f, (r - l) * 0.08f)
        val minDy = max(5f, (b - t) * 0.10f)

        var cand = PointF(Random.nextInt(l, r + 1).toFloat(), Random.nextInt(t, b + 1).toFloat())
        for (i in 0 until 8) {
            if (prev == null || abs(cand.x - prev.x) >= minDx || abs(cand.y - prev.y) >= minDy) break
            cand = PointF(Random.nextInt(l, r + 1).toFloat(), Random.nextInt(t, b + 1).toFloat())
        }
        lastTap = cand
        return cand
    }
}
