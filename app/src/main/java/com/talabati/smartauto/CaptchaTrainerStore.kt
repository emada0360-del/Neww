package com.talabati.smartauto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.sqrt

/** يتعلّم بصمات أرقام الكابتشا من القراءات النظيفة، ويُستعمل كاحتياط إذا فشل الـOCR */
object CaptchaTrainerStore {

    private const val PREFS_NAME = "captcha_ai_trainer_prefs_v3"
    private const val KEY_SAMPLES = "trained_samples_json"
    private const val GRID_SIZE = 16
    private const val MAX_SAMPLES_PER_DIGIT = 14

    private data class TrainedSample(
        val digit: Char,
        val vector: FloatArray,
        val weight: Int
    )

    private var loaded = false
    private val samples = mutableListOf<TrainedSample>()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SAMPLES, null)
            samples.clear()
            if (!raw.isNullOrEmpty()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val dStr = obj.optString("d", "")
                    val w = obj.optInt("w", 1).coerceAtLeast(1)
                    val vArr = obj.optJSONArray("v") ?: continue
                    if (dStr.length != 1 || vArr.length() != GRID_SIZE * GRID_SIZE) continue
                    val vec = FloatArray(GRID_SIZE * GRID_SIZE) { idx ->
                        vArr.optDouble(idx, 0.0).toFloat()
                    }
                    samples.add(TrainedSample(dStr[0], vec, w))
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** بصمة الرقم من قناع الرقم المعزول: نقصّ حدود الرقم ونحوّله لشبكة 16x16 مستقلة عن موقعه */
    fun featureFromMask(mask: BooleanArray, gw: Int, gh: Int): FloatArray? {
        var minX = gw
        var maxX = -1
        var minY = gh
        var maxY = -1
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                if (mask[y * gw + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw < 4 || bh < 4) return null

        val grid = FloatArray(GRID_SIZE * GRID_SIZE)
        val counts = IntArray(GRID_SIZE * GRID_SIZE)
        for (y in minY..maxY) {
            val gyy = min(GRID_SIZE - 1, ((y - minY) * GRID_SIZE) / bh)
            for (x in minX..maxX) {
                val gxx = min(GRID_SIZE - 1, ((x - minX) * GRID_SIZE) / bw)
                val idx = gyy * GRID_SIZE + gxx
                counts[idx] += 1
                if (mask[y * gw + x]) grid[idx] += 1f
            }
        }
        for (i in grid.indices) {
            grid[i] = if (counts[i] > 0) grid[i] / counts[i] else 0f
        }
        normalizeInPlace(grid)
        return grid
    }

    @Synchronized
    fun predict(context: Context, featureVector: FloatArray?): Pair<Char, Float>? {
        ensureLoaded(context)
        if (featureVector == null || samples.isEmpty()) return null

        val scoreByDigit = mutableMapOf<Char, Float>()
        val weightByDigit = mutableMapOf<Char, Float>()

        for (sample in samples) {
            val sim = cosineSimilarity(featureVector, sample.vector)
            if (sim > 0.35f) {
                val w = sample.weight.toFloat()
                scoreByDigit[sample.digit] = (scoreByDigit[sample.digit] ?: 0f) + (sim * w)
                weightByDigit[sample.digit] = (weightByDigit[sample.digit] ?: 0f) + w
            }
        }

        if (scoreByDigit.isEmpty()) return null

        var bestDigit: Char? = null
        var bestConfidence = 0f

        for ((digit, weightedSum) in scoreByDigit) {
            val totalW = weightByDigit[digit] ?: 1f
            val avgSim = weightedSum / totalW
            if (avgSim > bestConfidence) {
                bestConfidence = avgSim
                bestDigit = digit
            }
        }

        return if (bestDigit != null) bestDigit to bestConfidence else null
    }

    @Synchronized
    fun trainSample(context: Context, digit: Char, featureVector: FloatArray?) {
        if (digit !in '0'..'9' || featureVector == null) return
        ensureLoaded(context)

        var merged = false
        for (i in samples.indices) {
            val current = samples[i]
            if (current.digit == digit && cosineSimilarity(current.vector, featureVector) > 0.80f) {
                val newWeight = min(60, current.weight + 1)
                val updatedVec = FloatArray(featureVector.size) { idx ->
                    ((current.vector[idx] * current.weight) + featureVector[idx]) / (current.weight + 1)
                }
                normalizeInPlace(updatedVec)
                samples[i] = TrainedSample(digit, updatedVec, newWeight)
                merged = true
                break
            }
        }

        if (!merged) {
            val digitSamples = samples.filter { it.digit == digit }
            if (digitSamples.size >= MAX_SAMPLES_PER_DIGIT) {
                val weakest = digitSamples.minByOrNull { it.weight }
                if (weakest != null) {
                    samples.remove(weakest)
                }
            }
            samples.add(TrainedSample(digit, featureVector.copyOf(), 1))
        }

        persist(context)
    }

    /** يمسح كل ما تعلّمه الـAI (يبدأ من جديد) */
    @Synchronized
    fun clearAll(context: Context) {
        ensureLoaded(context)
        samples.clear()
        persist(context)
    }

    /** يتراجع عن تدريب خاطئ: ينقص وزن العينة الأقرب لنفس الرقم أو يحذفها */
    @Synchronized
    fun untrainSample(context: Context, digit: Char, featureVector: FloatArray?) {
        if (featureVector == null) return
        ensureLoaded(context)
        var bestIdx = -1
        var bestSim = 0.80f
        for (i in samples.indices) {
            if (samples[i].digit != digit) continue
            val sim = cosineSimilarity(samples[i].vector, featureVector)
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }
        if (bestIdx < 0) return
        val cur = samples[bestIdx]
        if (cur.weight <= 1) samples.removeAt(bestIdx)
        else samples[bestIdx] = TrainedSample(cur.digit, cur.vector, cur.weight - 1)
        persist(context)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(-1f, 1f)
    }

    private fun normalizeInPlace(vec: FloatArray) {
        var normSq = 0f
        for (v in vec) normSq += v * v
        val norm = sqrt(normSq)
        if (norm > 1e-4f) {
            for (i in vec.indices) vec[i] = vec[i] / norm
        }
    }

    private fun persist(context: Context) {
        try {
            val arr = JSONArray()
            for (s in samples) {
                val obj = JSONObject()
                obj.put("d", s.digit.toString())
                obj.put("w", s.weight)
                val vArr = JSONArray()
                for (v in s.vector) vArr.put(v.toDouble())
                obj.put("v", vArr)
                arr.put(obj)
            }
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAMPLES, arr.toString())
                .apply()
        } catch (_: Throwable) {
        }
    }
}
