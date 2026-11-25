package com.talabati.smartauto

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LicenseStatus(
    val active: Boolean,
    /** 0 = دائم، غير ذلك = وقت انتهاء بالمللي ثانية */
    val expiresAt: Long
) {
    fun isValidNow(): Boolean = active && (expiresAt == 0L || System.currentTimeMillis() < expiresAt)
}

/**
 * يدير ترخيص الجهاز عبر Firebase Realtime Database:
 *  - licenses/{id} : يقرأها التطبيق فقط (القراءة مسموحة، الكتابة ممنوعة إلا من لوحة التحكم).
 *  - requests/{id} : يكتبها التطبيق فقط (الكتابة مسموحة، القراءة ممنوعة) لتبليغ المطوّر بجهاز جديد.
 * النتيجة تُخزَّن محلياً حتى يبقى آخر ترخيص صالح شغّال بدون نت لحين انتهاء مدته أو تحديث جديد.
 */
object LicenseManager {

    private const val PREFS = "smart_auto_license_v1"
    private const val KEY_ACTIVE = "active"
    private const val KEY_EXPIRES = "expires"
    private const val KEY_LAST_REQUEST = "last_req"

    fun deviceId(context: Context): String {
        val id = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) {
            null
        }
        return if (id.isNullOrBlank() || id == "9774d56d682e549c") fallbackId(context) else id
    }

    private fun fallbackId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var v = p.getString("fallback_id", null)
        if (v == null) {
            v = java.util.UUID.randomUUID().toString()
            p.edit().putString("fallback_id", v).apply()
        }
        return v
    }

    fun loadCached(context: Context): LicenseStatus? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_ACTIVE)) return null
        return LicenseStatus(p.getBoolean(KEY_ACTIVE, false), p.getLong(KEY_EXPIRES, 0L))
    }

    private fun saveCached(context: Context, status: LicenseStatus) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, status.active)
            .putLong(KEY_EXPIRES, status.expiresAt)
            .apply()
    }

    /** يجلب حالة الترخيص من الخادم ويحدّث النسخة المحلية. يرجع null عند فشل الاتصال (لا يغيّر المحفوظ) */
    suspend fun refresh(context: Context): LicenseStatus? = withContext(Dispatchers.IO) {
        val id = deviceId(context)
        try {
            val url = URL("${Endpoints.BASE}/${Endpoints.LICENSES}/$id.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (text == "null" || text.isBlank()) {
                // لا يوجد ترخيص بعد: أبلغ المطوّر بطلب تفعيل (إن لم نفعل سابقاً خلال ساعة)
                maybeSubmitRequest(context, id)
                val status = LicenseStatus(false, 0L)
                saveCached(context, status)
                return@withContext status
            }
            val o = JSONObject(text)
            val status = LicenseStatus(
                active = o.optBoolean("active", false),
                expiresAt = o.optLong("expiresAt", 0L)
            )
            saveCached(context, status)
            status
        } catch (_: Throwable) {
            null
        }
    }

    private fun maybeSubmitRequest(context: Context, id: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_LAST_REQUEST, 0L)
        if (now - last < 3600_000L) return
        p.edit().putLong(KEY_LAST_REQUEST, now).apply()
        try {
            val url = URL("${Endpoints.BASE}/${Endpoints.REQUESTS}/$id.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "PUT"
            conn.doOutput = true
            val body = JSONObject()
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("requestedAt", now)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Throwable) {
        }
    }
}
