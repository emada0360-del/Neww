package com.talabati.smartauto

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/** رقم كابتشا مرصود: صورة + بصمة + الرقم الذي يكتبه المستخدم (فارغ = لم يُحدَّد بعد) */
data class CaptchaSample(
    val id: String,
    val features: FloatArray,
    val thumbPng: ByteArray,
    val label: String
)

object CaptchaSamplesStore {

    const val MAX_ITEMS = 1000
    private const val SAME_THRESHOLD = 0.90f
    private const val LABEL_WEIGHT = 3

    private var file: File? = null
    private var appContext: Context? = null
    private val lock = Any()
    private val _items = MutableStateFlow<List<CaptchaSample>>(emptyList())
    val items: StateFlow<List<CaptchaSample>> = _items.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            appContext = context.applicationContext
            val f = File(context.applicationContext.filesDir, "captcha_samples.json")
            file = f
            if (f.exists()) {
                try {
                    _items.value = parse(f.readText())
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** يحفظ رقم كابتشا جديد (إن لم يكن شبيهاً بمحفوظ). الصورة تُضغط هنا ولا تُحذف */
    fun addIfNew(features: FloatArray, thumb: () -> Bitmap?) {
        for (s in _items.value) {
            if (dot(s.features, features) > SAME_THRESHOLD) return
        }
        synchronized(lock) {
            val list = ArrayList(_items.value)
            if (list.size >= MAX_ITEMS) {
                val victim = list.lastOrNull { it.label.isEmpty() } ?: list.last()
                list.remove(victim)
            }
            val bmp = try {
                thumb()
            } catch (_: Throwable) {
                null
            }
            val png = if (bmp != null) {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            } else {
                ByteArray(0)
            }
            list.add(0, CaptchaSample(newId(), features.copyOf(), png, ""))
            _items.value = list
            persistLocked()
        }
    }

    fun hasSimilar(features: FloatArray): Boolean =
        _items.value.any { dot(it.features, features) > SAME_THRESHOLD }

    /** الرقم المؤكَّد لنفس الصورة إن وُجد */
    fun findLabel(features: FloatArray): String? {
        var best: CaptchaSample? = null
        var bestSim = SAME_THRESHOLD
        for (s in _items.value) {
            val sim = dot(s.features, features)
            if (sim > bestSim) {
                bestSim = sim
                best = s
            }
        }
        return best?.label?.takeIf { it.isNotEmpty() }
    }

    /** المستخدم ضغط رقماً بنفسه: نكتبه جانب الصورة (ننشئ الصورة إن لم تكن محفوظة) */
    fun learnFromUser(features: FloatArray, digit: Char, thumb: () -> Bitmap?) {
        if (digit !in '0'..'9') return
        var found: CaptchaSample? = null
        var bestSim = SAME_THRESHOLD
        for (s in _items.value) {
            val sim = dot(s.features, features)
            if (sim > bestSim) {
                bestSim = sim
                found = s
            }
        }
        if (found != null) {
            if (found.label.isEmpty()) setLabel(found.id, digit.toString())
            return
        }
        addIfNew(features, thumb)
        val added = _items.value.firstOrNull { dot(it.features, features) > SAME_THRESHOLD } ?: return
        setLabel(added.id, digit.toString())
    }

    /** يحفظ الرقم الذي كتبه المستخدم ويعلّم الـAI عليه */
    fun setLabel(id: String, label: String) {
        val digit = label.firstOrNull()?.takeIf { it in '0'..'9' }
        synchronized(lock) {
            val cur = _items.value.firstOrNull { it.id == id } ?: return
            val newLabel = digit?.toString() ?: ""
            if (cur.label == newLabel) return
            val ctx0 = appContext
            val oldDigit = cur.label.firstOrNull()
            if (oldDigit != null && ctx0 != null) {
                repeat(LABEL_WEIGHT) { CaptchaTrainerStore.untrainSample(ctx0, oldDigit, cur.features) }
            }
            _items.value = _items.value.map { if (it.id == id) it.copy(label = newLabel) else it }
            persistLocked()
            val ctx = appContext
            if (digit != null && ctx != null) {
                repeat(LABEL_WEIGHT) { CaptchaTrainerStore.trainSample(ctx, digit, cur.features) }
            }
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            _items.value = _items.value.filter { it.id != id }
            persistLocked()
        }
    }

    fun clearUnlabeled() {
        synchronized(lock) {
            _items.value = _items.value.filter { it.label.isNotEmpty() }
            persistLocked()
        }
    }

    private fun newId(): String = System.currentTimeMillis().toString() + "-" + Random.nextInt(1000)

    private fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun encode(v: FloatArray): String {
        val bb = ByteBuffer.allocate(v.size * 4)
        bb.asFloatBuffer().put(v)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    private fun decode(t: String): FloatArray {
        val bytes = Base64.decode(t, Base64.NO_WRAP)
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).asFloatBuffer().get(out)
        return out
    }

    private fun parse(text: String): List<CaptchaSample> {
        val arr = JSONArray(text)
        val out = ArrayList<CaptchaSample>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CaptchaSample(
                    id = o.getString("id"),
                    features = decode(o.getString("f")),
                    thumbPng = Base64.decode(o.optString("t", ""), Base64.NO_WRAP),
                    label = o.optString("l", "")
                )
            )
        }
        return out
    }

    private fun persistLocked() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (s in _items.value) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("f", encode(s.features))
                o.put("t", Base64.encodeToString(s.thumbPng, Base64.NO_WRAP))
                o.put("l", s.label)
                arr.put(o)
            }
            val text = arr.toString()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
