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
import kotlin.math.sqrt
import kotlin.random.Random

enum class LearnedKind { ZONE, RESTAURANT }

/**
 * عنصر مرصود من الشاشة (شريحة منطقة أو اسم مطعم) محفوظ كـ«بصمة» + صورة مصغّرة.
 * flagged: للمنطقة = مستهدفة، وللمطعم = محظور.
 */
data class LearnedItem(
    val id: String,
    val kind: LearnedKind,
    val signature: FloatArray,
    val thumbPng: ByteArray,
    val label: String,
    val flagged: Boolean,
    /** للمنطقة فقط: مفعّلة لطلبات المطعم */
    val restFlag: Boolean = false,
    /** للمنطقة فقط: مفعّلة للطلبات البعيدة */
    val farFlag: Boolean = false
)

class MatchResult(val item: LearnedItem?, val isNew: Boolean)

object LearnedStore {

    const val SIG_W = 64
    const val SIG_H = 16
    const val SIG_SIZE = SIG_W * SIG_H

    private const val MATCH_THRESHOLD = 0.93f
    private const val MAX_ZONES = 100
    private const val MAX_RESTAURANTS = 300

    private var file: File? = null
    private val lock = Any()
    private val _items = MutableStateFlow<List<LearnedItem>>(emptyList())
    val items: StateFlow<List<LearnedItem>> = _items.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, "learned_items.json")
            file = f
            if (f.exists()) {
                try {
                    _items.value = parse(f.readText())
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** يبحث عن أقرب عنصر محفوظ. إذا ما لقى وكان learn=true يحفظ العنصر كجديد (غير مصنّف) */
    fun matchOrLearn(
        kind: LearnedKind,
        rawSignature: FloatArray,
        learn: Boolean,
        thumb: () -> Bitmap?
    ): MatchResult {
        val sig = normalize(rawSignature) ?: return MatchResult(null, false)

        var best: LearnedItem? = null
        var bestSim = 0f
        for (item in _items.value) {
            if (item.kind != kind) continue
            val sim = dot(item.signature, sig)
            if (sim > bestSim) {
                bestSim = sim
                best = item
            }
        }
        if (best != null && bestSim >= MATCH_THRESHOLD) return MatchResult(best, false)
        if (!learn) return MatchResult(null, false)

        synchronized(lock) {
            val list = ArrayList(_items.value)
            val limit = if (kind == LearnedKind.ZONE) MAX_ZONES else MAX_RESTAURANTS
            val ofKind = list.filter { it.kind == kind }
            if (ofKind.size >= limit) {
                val victim = ofKind.firstOrNull { !it.flagged && !it.restFlag && !it.farFlag } ?: return MatchResult(null, false)
                list.remove(victim)
            }
            val bmp = try {
                thumb()
            } catch (_: Throwable) {
                null
            }
            val png: ByteArray = if (bmp != null) {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bmp.recycle()
                bos.toByteArray()
            } else {
                ByteArray(0)
            }
            val item = LearnedItem(newId(), kind, sig, png, "", false)
            list.add(item)
            _items.value = list
            persistLocked()
            return MatchResult(item, true)
        }
    }

    /** المناطق والمطاعم المحفوظة كنص JSON (للنسخ لجهاز ثاني) */
    fun exportJson(): String {
        val arr = JSONArray()
        for (item in _items.value) {
            val o = JSONObject()
            o.put("id", item.id)
            o.put("kind", item.kind.name)
            o.put("sig", encodeSig(item.signature))
            o.put("thumb", Base64.encodeToString(item.thumbPng, Base64.NO_WRAP))
            o.put("label", item.label)
            o.put("flag", item.flagged)
            o.put("flagR", item.restFlag)
            o.put("flagF", item.farFlag)
            arr.put(o)
        }
        return arr.toString()
    }

    /** يدمج عناصر جهاز ثاني مع الموجود (بدون مسح). يرجع عدد المضاف */
    fun importJson(text: String): Int {
        val incoming = try {
            parse(text)
        } catch (_: Throwable) {
            return 0
        }
        synchronized(lock) {
            val list = ArrayList(_items.value)
            var added = 0
            for (inc in incoming) {
                if (list.any { it.id == inc.id }) continue
                val sig = normalize(inc.signature) ?: continue
                val similar = list.any { it.kind == inc.kind && dot(it.signature, sig) >= MATCH_THRESHOLD }
                if (similar) continue
                list.add(inc)
                added++
            }
            _items.value = list
            persistLocked()
            return added
        }
    }

    fun setFlag(id: String, flagged: Boolean) {
        synchronized(lock) {
            _items.value = _items.value.map { if (it.id == id) it.copy(flagged = flagged) else it }
            persistLocked()
        }
    }

    /** group: 0 = عادي، 1 = مطعم، 2 = بعيد */
    fun setZoneGroupFlag(id: String, group: Int, on: Boolean) {
        synchronized(lock) {
            _items.value = _items.value.map {
                if (it.id != id) it else when (group) {
                    0 -> it.copy(flagged = on)
                    1 -> it.copy(restFlag = on)
                    else -> it.copy(farFlag = on)
                }
            }
            persistLocked()
        }
    }

    /** تشغيل/إطفاء كل المناطق داخل خانة */
    fun setAllZonesGroup(group: Int, on: Boolean) {
        synchronized(lock) {
            _items.value = _items.value.map {
                if (it.kind != LearnedKind.ZONE) it else when (group) {
                    0 -> it.copy(flagged = on)
                    1 -> it.copy(restFlag = on)
                    else -> it.copy(farFlag = on)
                }
            }
            persistLocked()
        }
    }

    /** هل المنطقة مسموحة لهذا النوع من الطلبات */
    fun zoneAllows(item: LearnedItem, restaurant: Boolean): Boolean =
        item.farFlag || (if (restaurant) item.restFlag else item.flagged)

    fun delete(id: String) {
        synchronized(lock) {
            _items.value = _items.value.filter { it.id != id }
            persistLocked()
        }
    }

    fun clearUnflagged(kind: LearnedKind) {
        synchronized(lock) {
            _items.value = _items.value.filter { !(it.kind == kind && !it.flagged && !it.restFlag && !it.farFlag) }
            persistLocked()
        }
    }

    // ───────────────────────── داخلي ─────────────────────────

    private fun newId(): String = System.currentTimeMillis().toString() + "-" + Random.nextInt(1000)

    private fun normalize(raw: FloatArray): FloatArray? {
        if (raw.size != SIG_SIZE) return null
        var mean = 0f
        for (v in raw) mean += v
        mean /= raw.size
        val out = FloatArray(raw.size)
        var norm = 0f
        for (i in raw.indices) {
            val d = raw[i] - mean
            out[i] = d
            norm += d * d
        }
        norm = sqrt(norm)
        if (norm < 1e-4f) return null
        for (i in out.indices) out[i] /= norm
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun encodeSig(sig: FloatArray): String {
        val bb = ByteBuffer.allocate(SIG_SIZE * 4)
        bb.asFloatBuffer().put(sig)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    private fun decodeSig(text: String): FloatArray? {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        if (bytes.size != SIG_SIZE * 4) return null
        val out = FloatArray(SIG_SIZE)
        ByteBuffer.wrap(bytes).asFloatBuffer().get(out)
        return out
    }

    private fun parse(text: String): List<LearnedItem> {
        val arr = JSONArray(text)
        val out = ArrayList<LearnedItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sig = decodeSig(o.getString("sig")) ?: continue
            val kind = runCatching { LearnedKind.valueOf(o.getString("kind")) }.getOrNull() ?: continue
            out.add(
                LearnedItem(
                    id = o.getString("id"),
                    kind = kind,
                    signature = sig,
                    thumbPng = Base64.decode(o.optString("thumb", ""), Base64.NO_WRAP),
                    label = o.optString("label", ""),
                    flagged = o.optBoolean("flag", false),
                    restFlag = o.optBoolean("flagR", false),
                    farFlag = o.optBoolean("flagF", false)
                )
            )
        }
        return out
    }

    private fun persistLocked() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (item in _items.value) {
                val o = JSONObject()
                o.put("id", item.id)
                o.put("kind", item.kind.name)
                o.put("sig", encodeSig(item.signature))
                o.put("thumb", Base64.encodeToString(item.thumbPng, Base64.NO_WRAP))
                o.put("label", item.label)
                o.put("flag", item.flagged)
            o.put("flagR", item.restFlag)
            o.put("flagF", item.farFlag)
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
