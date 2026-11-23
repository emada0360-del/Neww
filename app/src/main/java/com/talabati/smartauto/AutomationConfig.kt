package com.talabati.smartauto

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class OrderFilterMode {
    ALL,
    NORMAL_ONLY,
    RESTAURANT_ONLY
}

enum class ScreenKind {
    NONE,
    OFFER,
    CAPTCHA
}

/** نتيجة تحليل لقطة واحدة */
data class ScanResult(
    val kind: ScreenKind = ScreenKind.NONE,
    /** نص يظهر في السجل */
    val note: String = "",
    /** حدود الزر المطلوب الضغط داخله (موافق أو زر الرقم). null = لا ضغط */
    val tapRect: Rect? = null,
    val digit: String? = null,
    /** للنقر اليدوي: مستطيل يُضغط عند لمسة المستخدم حتى لو غير مستهدف (موافق، أو رقم غلط) */
    val manualRect: Rect? = null
)

data class AutomationConfig(
    val isRunning: Boolean = false,
    val statusText: String = "متوقف",
    val lastLog: String = "جاهز للعمل",
    val orderFilterMode: OrderFilterMode = OrderFilterMode.ALL,
    /** الفاصل بين ضغطتي «موافق» بالمللي ثانية. 0 = ضغطة واحدة فقط */
    val tapGapMs: Long = 18L,
    /** رصد المناطق والمطاعم الجديدة تلقائياً وحفظها لتصنيفها */
    val autoLearn: Boolean = true,
    /** نقر يدوي أوتوماتيكي: لمسة المستخدم بأي مكان تنفّذ الضغطة المناسبة */
    val manualMode: Boolean = false
)

object AutomationStore {

    private const val PREFS_NAME = "smart_auto_settings_v2"
    private const val KEY_MODE = "mode"
    private const val KEY_GAP = "gap"
    private const val KEY_LEARN = "learn"
    private const val KEY_MANUAL = "manual"

    private var prefs: SharedPreferences? = null
    private val _config = MutableStateFlow(AutomationConfig())
    val config: StateFlow<AutomationConfig> = _config.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val mode = p.getString(KEY_MODE, null)
        val gap = p.getLong(KEY_GAP, -1L)
        val learn = p.getBoolean(KEY_LEARN, true)
        _config.update { cur ->
            var c = cur.copy(autoLearn = learn, manualMode = p.getBoolean(KEY_MANUAL, false))
            if (mode != null) {
                val m = runCatching { OrderFilterMode.valueOf(mode) }.getOrNull()
                if (m != null) c = c.copy(orderFilterMode = m)
            }
            if (gap >= 0) c = c.copy(tapGapMs = gap.coerceIn(0L, 100L))
            c
        }
    }

    private fun save() {
        val p = prefs ?: return
        val c = _config.value
        p.edit()
            .putString(KEY_MODE, c.orderFilterMode.name)
            .putLong(KEY_GAP, c.tapGapMs)
            .putBoolean(KEY_LEARN, c.autoLearn)
            .putBoolean(KEY_MANUAL, c.manualMode)
            .apply()
    }

    fun setRunning(running: Boolean, status: String) {
        _config.update { it.copy(isRunning = running, statusText = status) }
    }

    fun updateLog(log: String) {
        _config.update { current ->
            if (current.lastLog == log) current else current.copy(lastLog = log)
        }
    }

    fun setOrderFilterMode(mode: OrderFilterMode) {
        _config.update { it.copy(orderFilterMode = mode) }
        save()
    }

    fun setTapGapMs(ms: Long) {
        _config.update { it.copy(tapGapMs = ms.coerceIn(0L, 100L)) }
        save()
    }

    fun setManualMode(enabled: Boolean) {
        _config.update { it.copy(manualMode = enabled) }
        save()
    }

    fun setAutoLearn(enabled: Boolean) {
        _config.update { it.copy(autoLearn = enabled) }
        save()
    }
}
