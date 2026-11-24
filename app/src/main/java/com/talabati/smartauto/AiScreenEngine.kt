package com.talabati.smartauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * تحليل الشاشة بدون OCR عربي (مكتبة ML Kit العربية غير متوفرة):
 *  - شاشة الطلب: تُعرف من زر أخضر (يمين) + زر أحمر (يسار) أسفل الشاشة.
 *  - المنطقة / المطعم: تُعرف ببصمة صورة الشريحة (مطابقة مع ما صنّفته من التطبيق).
 *  - الكابتشا: تُعرف من لوحة الأرقام، ويُقرأ الرقم بـ OCR لاتيني على صورة منظّفة.
 */
class AiScreenEngine(private val context: Context) {

    private class Keypad(val cols: List<IntArray>, val rows: List<IntArray>) {
        fun rectFor(digit: Char): Rect? {
            val idx = when (digit) {
                in '1'..'9' -> digit - '1'
                '0' -> 10
                else -> return null
            }
            val row = if (idx == 10) 3 else idx / 3
            val col = if (idx == 10) 1 else idx % 3
            if (row >= rows.size || col >= cols.size) return null
            val r = rows[row]
            val c = cols[col]
            return Rect(c[0], r[0], c[1], r[1])
        }
    }

    private class Isolated(
        val image: Bitmap,
        val mask: BooleanArray,
        val gw: Int,
        val gh: Int,
        /** مستطيل حول الرقم بإحداثيات الشاشة (لصورة العرض الملوّنة) */
        val box: Rect
    )

    private val digitRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // حفظ صور الكابتشا بالخلفية حتى لا يتأخر الضغط
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // مخزن بكسلات مشترك لتقليل الـGC (التحليل يتم بشكل متسلسل)
    private var buf = IntArray(0)

    private fun ensureBuf(n: Int): IntArray {
        if (buf.size < n) buf = IntArray(n)
        return buf
    }

    init {
        CaptchaTrainerStore.ensureLoaded(context)
        LearnedStore.init(context)
        CaptchaSamplesStore.init(context)
    }

    // ───────────────────────── التحليل الرئيسي ─────────────────────────

    /** يتأكد إن المستطيل ثابت لعدة لقطات (حتى لا نحفظ لقطات حركة دخول البطاقة) */
    private class RectStability {
        private var prev: Rect? = null
        private var count = 0
        private var lastAt = 0L

        fun update(r: Rect): Int {
            val now = System.currentTimeMillis()
            val p = prev
            if (p != null && now - lastAt < 600 &&
                abs(p.left - r.left) <= 3 && abs(p.right - r.right) <= 3 &&
                abs(p.top - r.top) <= 3 && abs(p.bottom - r.bottom) <= 3
            ) {
                count++
            } else {
                count = 0
            }
            prev = Rect(r)
            lastAt = now
            return count
        }
    }

    private val pillStability = RectStability()
    private val nameStability = RectStability()

    // نتيجة آخر طلب شفناه: هل منطقته مستهدفة؟ (نستعملها لتجاهل كابتشا طلب ضغطته بالغلط)
    private var lastOfferTargeted: Boolean? = null
    private var lastOfferAt = 0L

    private fun markOffer(targeted: Boolean) {
        lastOfferTargeted = targeted
        lastOfferAt = System.currentTimeMillis()
    }

    suspend fun analyzeFrame(bitmap: Bitmap, cfg: AutomationConfig): ScanResult {
        val greenRect = detectOfferBar(bitmap)
        if (greenRect != null) return analyzeOffer(bitmap, greenRect, cfg)

        val keypad = detectKeypad(bitmap)
        if (keypad != null) {
            val skip = lastOfferTargeted == false &&
                System.currentTimeMillis() - lastOfferAt < 120_000L
            if (skip) {
                // غير مستهدف: ما نقرأ الرقم أبداً، بس نجهّز زر «إلغاء» للنقر اليدوي
                val rows = keypad.rows
                val pitch = if (rows.size >= 2) rows[1][0] - rows[0][0] else 0
                val bottom = if (rows.size >= 4) rows[3][1] else rows[0][1] + 3 * pitch
                val hh = bitmap.height
                val ww = bitmap.width
                val cancel = Rect(
                    (ww * 0.36f).toInt(), bottom + (hh * 0.014f).toInt(),
                    (ww * 0.64f).toInt(), bottom + (hh * 0.052f).toInt()
                )
                return ScanResult(
                    ScreenKind.CAPTCHA,
                    "كابتشا: الطلب من منطقة/مطعم غير مستهدف — تم التجاهل",
                    manualRect = cancel
                )
            }
            return analyzeCaptcha(bitmap, keypad, cfg)
        }

        return ScanResult()
    }

    // ───────────────────────── شاشة الطلب ─────────────────────────

    /** اسم المطعم كامل وثابت؟ (محاذٍ لليمين، عريض كفاية، ومستقر لقطتين) */
    private fun nameSettled(r: Rect, w: Int): Boolean {
        val stable = nameStability.update(r) >= 2
        val shapeOk = r.width() >= w * 0.30f && r.right >= w * 0.76f
        return stable && shapeOk
    }

    private fun analyzeOffer(bitmap: Bitmap, greenRect: Rect, cfg: AutomationConfig): ScanResult {
        val pill = findZonePill(bitmap)
            ?: return ScanResult(ScreenKind.OFFER, "طلب ظاهر: لم أجد شريحة المنطقة")

        val pillStable = pillStability.update(pill) >= 2
        val zoneSig = gridSignature(bitmap, pill) { p -> isWhiteText(p) }
        val zoneMatch = LearnedStore.matchOrLearn(LearnedKind.ZONE, zoneSig, cfg.autoLearn && pillStable) {
            thumbOf(bitmap, pill, 6)
        }
        val zone = zoneMatch.item
        if (zone == null && !pillStable && cfg.autoLearn) {
            return ScanResult(ScreenKind.OFFER, "انتظار استقرار الطلب...")
        }
        val isRestaurantOrder = detectRestaurantTag(bitmap)
        if (zone == null || !LearnedStore.zoneAllows(zone, isRestaurantOrder)) {
            markOffer(false)
            // نحفظ المطعم حتى لو المنطقة غير مستهدفة (ليظهر بالقائمة وتفعّله)
            if (cfg.autoLearn) {
                val nr = findRestaurantName(bitmap, pill.top)
                if (nr != null && nameSettled(nr, bitmap.width)) {
                    val sig = gridSignature(bitmap, nr) { p -> isNameRed(p) }
                    LearnedStore.matchOrLearn(LearnedKind.RESTAURANT, sig, true) {
                        thumbOf(bitmap, nr, 8)
                    }
                }
            }
            val note = if (zoneMatch.isNew) {
                "رُصدت منطقة جديدة — صنّفها من التطبيق"
            } else {
                "منطقة غير مستهدفة"
            }
            return ScanResult(ScreenKind.OFFER, note, manualRect = greenRect)
        }

        // مطعم مفعّل؟ (ويُحفظ المطعم الجديد ليظهر في قائمة المطاعم)
        val nameRect = findRestaurantName(bitmap, pill.top)
        if (nameRect != null) {
            val settled = nameSettled(nameRect, bitmap.width)
            val nameSig = gridSignature(bitmap, nameRect) { p -> isNameRed(p) }
            val rm = LearnedStore.matchOrLearn(LearnedKind.RESTAURANT, nameSig, cfg.autoLearn && settled) {
                thumbOf(bitmap, nameRect, 8)
            }
            val rest = rm.item
            // بطاقة لسه تتحرك/غير مكتملة: ننتظر لقطة مستقرة
            if (rest == null && !settled) {
                return ScanResult(ScreenKind.OFFER, "انتظار استقرار الطلب...")
            }
            // المطعم لازم يكون «مفعّل» من التطبيق، والجديد يُتجاهل لين تفعّله
            if (rest == null || !rest.flagged) {
                markOffer(false)
                val note = if (rm.isNew) {
                    "مطعم جديد — فعّله من التطبيق ليشتغل"
                } else {
                    "مطعم غير مفعّل — تم التجاهل"
                }
                return ScanResult(ScreenKind.OFFER, note, manualRect = greenRect)
            }
        }

        markOffer(true)
        return ScanResult(ScreenKind.OFFER, "طلب مطابق — ضغط على موافق", tapRect = greenRect)
    }

    /**
     * شاشة عرض الطلب فيها زرّان بجنب بعض: أحمر (إلغاء) يسار وأخضر (موافق) يمين.
     * الشاشات الأخرى (بعد القبول) فيها زر أخضر واحد بعرض كامل فلا تطابق.
     * يرجع حدود الزر الأخضر (بإحداثيات الشاشة) أو null.
     */
    private fun detectOfferBar(bitmap: Bitmap): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val top = (h * 0.905f).toInt()
        val rh = (h * 0.99f).toInt() - top
        if (rh < 20 || w < 200) return null

        val px = ensureBuf(w * rh)
        bitmap.getPixels(px, 0, w, 0, top, w, rh)

        val gCols = IntArray(w)
        val gRows = IntArray(rh)
        val rCols = IntArray(w)
        val rRows = IntArray(rh)
        var gTotal = 0
        var rTotal = 0

        var y = 0
        while (y < rh) {
            val base = y * w
            var x = 0
            while (x < w) {
                val p = px[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (g > 110 && g - max(r, b) > 45) {
                    gCols[x]++
                    gRows[y]++
                    gTotal++
                } else if (r > 200 && g < 100 && b < 90 && r - g > 120) {
                    rCols[x]++
                    rRows[y]++
                    rTotal++
                }
                x += 3
            }
            y += 3
        }
        if (gTotal < 150 || rTotal < 150) return null

        val gl = percentileIndex(gCols, gTotal, 0.02)
        val gr = percentileIndex(gCols, gTotal, 0.98)
        val gt = percentileIndex(gRows, gTotal, 0.02)
        val gb = percentileIndex(gRows, gTotal, 0.98)
        val rl = percentileIndex(rCols, rTotal, 0.02)
        val rr = percentileIndex(rCols, rTotal, 0.98)

        if (gr - gl < w * 0.30f || gl < w * 0.30f) return null
        if (rr - rl < w * 0.25f || rr > w * 0.65f || rr > gl + w * 0.05f) return null
        if (gb - gt < 20) return null

        return Rect(gl, top + gt, gr, top + gb)
    }

    /** شريحة المنطقة: الشريحة المرجانية (حمراء فاتحة) ذات الحافة المستديرة أسفل بيانات الزبون */
    private fun findZonePill(bitmap: Bitmap): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val top = (h * 0.52f).toInt()
        val rh = (h * 0.905f).toInt() - top
        if (rh < 20) return null

        val px = ensureBuf(w * rh)
        bitmap.getPixels(px, 0, w, 0, top, w, rh)

        val rowCnt = IntArray(rh)
        for (y in 0 until rh) {
            val base = y * w
            var c = 0
            var x = 0
            while (x < w) {
                if (isCoral(px[base + x])) c += 2
                x += 2
            }
            rowCnt[y] = c
        }

        val minRow = (w * 0.10f).toInt()
        val minBand = (h * 0.015f).toInt()
        var bestS = -1
        var bestE = -1
        var bestArea = 0
        var s = -1
        var area = 0
        for (y in 0..rh) {
            val ok = y < rh && rowCnt[y] >= minRow
            if (ok) {
                if (s < 0) {
                    s = y
                    area = 0
                }
                area += rowCnt[y]
            } else if (s >= 0) {
                if (y - s >= minBand && area > bestArea) {
                    bestArea = area
                    bestS = s
                    bestE = y
                }
                s = -1
            }
        }
        if (bestS < 0) return null

        var minX = w
        var maxX = -1
        for (y in bestS until bestE) {
            val base = y * w
            for (x in 0 until w) {
                if (isCoral(px[base + x])) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        if (maxX < 0) return null

        val pw = maxX - minX
        val ph = bestE - bestS
        if (pw < w * 0.12f || pw > w * 0.85f) return null
        if (ph < h * 0.02f || ph > h * 0.09f) return null

        return Rect(minX, top + bestS, maxX, top + bestE - 1)
    }

    /** اسم المطعم: نص أحمر داخل المربع الرمادي أعلى بيانات الزبون */
    private fun findRestaurantName(bitmap: Bitmap, pillTop: Int): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        // من تحت تبويبات (التفاصيل...) إلى فوق شريحة المنطقة (موقعها يتغير حسب عدد أسطر الاسم)
        val top = (h * 0.485f).toInt()
        val rh = (pillTop - 4) - top
        if (rh < 20) return null

        val px = ensureBuf(w * rh)
        bitmap.getPixels(px, 0, w, 0, top, w, rh)

        val cols = IntArray(w)
        val rows = IntArray(rh)
        var total = 0
        for (y in 0 until rh) {
            val base = y * w
            for (x in 0 until w) {
                if (isNameRed(px[base + x])) {
                    cols[x]++
                    rows[y]++
                    total++
                }
            }
        }
        if (total < 150) return null

        val l = percentileIndex(cols, total, 0.01)
        val r = percentileIndex(cols, total, 0.99)
        val t = percentileIndex(rows, total, 0.01)
        val b = percentileIndex(rows, total, 0.99)
        if (r - l < 40 || b - t < 10) return null
        return Rect(l, top + t, r, top + b)
    }

    /** وسم «طلب مطعم»: لون سماوي مميز ضمن شريط الوسوم (يُستعمل فقط عند تفعيل الفلتر) */
    private fun detectRestaurantTag(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val top = (h * 0.32f).toInt()
        val rh = (h * 0.44f).toInt() - top
        if (rh < 20) return false

        val px = ensureBuf(w * rh)
        bitmap.getPixels(px, 0, w, 0, top, w, rh)

        var count = 0
        var green = 0
        var y = 0
        while (y < rh) {
            val base = y * w
            var x = 0
            while (x < w) {
                val p = px[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r < 110 && g > 150 && b > 160 && b - r > 80 && abs(g - b) < 60) count++
                // الوسم الجديد «طلب مطعم» أخضر
                if (g > 110 && g - r > 35 && g - b > 35) green++
                x += 2
            }
            y += 2
        }
        return green >= 120 || count * 4 >= (0.0004f * w * h).toInt()
    }

    // ───────────────────────── شاشة الكابتشا ─────────────────────────

    private suspend fun analyzeCaptcha(bitmap: Bitmap, keypad: Keypad, cfg: AutomationConfig): ScanResult {
        val iso = isolateCaptchaDigit(bitmap)
        try {
            return analyzeCaptchaWith(bitmap, keypad, cfg, iso)
        } finally {
            iso?.image?.recycle()
        }
    }

    private suspend fun analyzeCaptchaWith(
        bitmap: Bitmap,
        keypad: Keypad,
        cfg: AutomationConfig,
        iso: Isolated?
    ): ScanResult {
        var digit: String? = null
        var features: FloatArray? = null

        if (iso != null) {
            features = CaptchaTrainerStore.featureFromMask(iso.mask, iso.gw, iso.gh)
        }

        // صورة محفوظة برقم مؤكَّد: ضغط فوري بدون OCR
        val known = if (features != null) CaptchaSamplesStore.findLabel(features) else null
        if (known != null) {
            val r = keypad.rectFor(known[0])
            if (r != null) {
                return ScanResult(ScreenKind.CAPTCHA, "كابتشا | رقم: $known (محفوظ)", tapRect = r, digit = known)
            }
        }

        val ai = CaptchaTrainerStore.predict(context, features)

        // حفظ صورة الكابتشا بالخلفية (لا يؤخر الضغط)
        if (iso != null && cfg.autoLearn && features != null && !CaptchaSamplesStore.hasSimilar(features)) {
            val f = features
            val crop = colorCrop(bitmap, iso)
            if (crop != null) {
                bgScope.launch {
                    CaptchaSamplesStore.addIfNew(f) { crop }
                }
            }
        }

        // الـAI واثق (تعلّم الشكل): ضغط فوري بدون OCR
        if (ai != null && ai.second >= 0.92f) {
            val r = keypad.rectFor(ai.first)
            if (r != null) {
                return ScanResult(
                    ScreenKind.CAPTCHA,
                    "كابتشا | رقم: ${ai.first} (سريع)",
                    tapRect = r,
                    digit = ai.first.toString()
                )
            }
        }

        if (iso != null) {
            digit = readSingleDigit(iso.image)
        }

        // احتياط: قراءة مباشرة من منطقة الصورة إذا فشل العزل
        if (digit == null) {
            digit = readDigitFromCaptchaRegion(bitmap)
        }

        val finalDigit: String? = when {
            known != null -> known
            // رقم علّمه المستخدم (ثقة عالية) له الأولوية على قراءة الـOCR
            ai != null && ai.second >= 0.92f -> ai.first.toString()
            digit != null -> digit
            ai != null && ai.second >= 0.80f -> ai.first.toString()
            else -> null
        }

        // تدريب الـAI فقط من القراءة النظيفة، وعند الاتفاق أو عدم ثقة الـAI
        if (known == null && digit != null && features != null && iso != null) {
            if (ai == null || ai.first.toString() == digit || ai.second < 0.50f) {
                CaptchaTrainerStore.trainSample(context, digit[0], features)
            }
        }

        // تعلّم تلقائي: إذا ضغط المستخدم رقماً بنفسه (يظهر فوق اللوحة) نحفظه مع صورة الكابتشا
        if (cfg.autoLearn && features != null && known == null && iso != null) {
            val entered = readEnteredDigit(bitmap, keypad)
            if (entered != null && entered != finalDigit) {
                CaptchaSamplesStore.learnFromUser(features, entered[0]) { colorCrop(bitmap, iso) }
            }
        }

        if (finalDigit == null) {
            return ScanResult(ScreenKind.CAPTCHA, "كابتشا: تعذّر قراءة الرقم")
        }
        val rect = keypad.rectFor(finalDigit[0])
            ?: return ScanResult(ScreenKind.CAPTCHA, "كابتشا: زر الرقم غير موجود")

        return ScanResult(
            kind = ScreenKind.CAPTCHA,
            note = "كابتشا | رقم: $finalDigit",
            tapRect = rect,
            digit = finalDigit
        )
    }

    /**
     * لوحة الأرقام: نكشف الصفوف بمسح عمودي قرب حافة العمود الأول (موقع اللوحة يتغير حسب عدد أسطر النص)،
     * ثم الأعمدة بمسح أفقي داخل أحد الصفوف.
     */
    private fun detectKeypad(bitmap: Bitmap): Keypad? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 200 || h < 400) return null

        val bg = bitmap.getPixel((w * 0.06f).toInt(), (h * 0.62f).toInt())

        // الصفوف
        val y0 = (h * 0.35f).toInt()
        val len = h - y0
        val col = IntArray(len)
        bitmap.getPixels(col, 0, 1, (w * 0.21f).toInt(), y0, 1, len)
        val minH = (h * 0.05f).toInt()
        val maxH = (h * 0.09f).toInt()
        val vr = verticalRuns(col, len, bg, 14, minH).filter { it[1] - it[0] <= maxH }
        var triple: List<IntArray>? = null
        for (i in 0..vr.size - 3) {
            val p1 = vr[i + 1][0] - vr[i][0]
            val p2 = vr[i + 2][0] - vr[i + 1][0]
            if (abs(p1 - p2) <= 14 && p1 >= (h * 0.06f).toInt() && p1 <= (h * 0.10f).toInt()) {
                triple = listOf(vr[i], vr[i + 1], vr[i + 2])
                break
            }
        }
        if (triple == null) return null

        val rows = ArrayList<IntArray>()
        for (r in triple) rows.add(intArrayOf(y0 + r[0], y0 + r[1]))
        val pitch = rows[2][0] - rows[1][0]
        val s4 = rows[2][0] + pitch
        val e4 = min(h - 1, s4 + (rows[2][1] - rows[2][0]))
        rows.add(intArrayOf(s4, e4))

        // الأعمدة
        val row = IntArray(w)
        var colRuns: List<IntArray>? = null
        val bh = rows[0][1] - rows[0][0]
        for (f in floatArrayOf(0.12f, 0.88f, 0.5f)) {
            bitmap.getPixels(row, 0, w, 0, rows[0][0] + (bh * f).toInt(), w, 1)
            val runs = horizontalRuns(row, w, bg, 14, (w * 0.10f).toInt(), (w * 0.28f).toInt(), (w * 0.05f).toInt())
            if (runs.size == 3) {
                val c0 = (runs[0][0] + runs[0][1]) / 2f / w
                val c1 = (runs[1][0] + runs[1][1]) / 2f / w
                val c2 = (runs[2][0] + runs[2][1]) / 2f / w
                if (abs(c0 - 0.25f) < 0.07f && abs(c1 - 0.50f) < 0.07f && abs(c2 - 0.75f) < 0.07f) {
                    colRuns = runs
                    break
                }
            }
        }
        if (colRuns == null) return null
        return Keypad(colRuns, rows)
    }

    /** الرقم الذي ظهر فوق لوحة الأرقام بعد أن ضغط المستخدم (أبيض على خلفية داكنة) */
    private suspend fun readEnteredDigit(bitmap: Bitmap, keypad: Keypad): String? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val rowTop = keypad.rows[0][0]
            val top = max(0, rowTop - (h * 0.052f).toInt())
            val bottom = rowTop - (h * 0.005f).toInt()
            val left = (w * 0.38f).toInt()
            val cw = (w * 0.24f).toInt()
            val chh = bottom - top
            if (chh < 30 || cw < 30) return null

            val px = IntArray(cw * chh)
            bitmap.getPixels(px, 0, cw, left, top, cw, chh)
            var white = 0
            val m = 24
            val ow = cw + 2 * m
            val oh = chh + 2 * m
            val out = IntArray(ow * oh) { Color.WHITE }
            for (y in 0 until chh) {
                for (x in 0 until cw) {
                    if (isWhiteText(px[y * cw + x])) {
                        out[(y + m) * ow + (x + m)] = Color.BLACK
                        white++
                    }
                }
            }
            if (white < 300) return null
            val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, ow, 0, 0, ow, oh)
            try {
                readSingleDigit(bmp)
            } finally {
                bmp.recycle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * عزل رقم الكابتشا: نختار اللون السائد (الرقم سميك والخطوط والنقاط رفيعة)،
     * نحذف البكسلات المعزولة، ونُبقي أكبر الكتل المتصلة فقط. الناتج صورة سوداء على أبيض.
     */
    /** إغلاق مورفولوجي 3x3 (تكبير ثم تصغير) لسدّ الشقوق الرفيعة */
    private fun closeMask(a: BooleanArray, gw: Int, gh: Int, t: BooleanArray) {
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                t[i] = a[i] || (x > 0 && a[i - 1]) || (x < gw - 1 && a[i + 1])
            }
        }
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                a[i] = t[i] || (y > 0 && t[i - gw]) || (y < gh - 1 && t[i + gw])
            }
        }
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                t[i] = a[i] && x > 0 && a[i - 1] && x < gw - 1 && a[i + 1]
            }
        }
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                a[i] = t[i] && y > 0 && t[i - gw] && y < gh - 1 && t[i + gw]
            }
        }
    }

    /** نسخة ملوّنة مقصوصة حول الرقم (للعرض في القائمة). المستلِم هو من يُحرّرها */
    private fun colorCrop(bitmap: Bitmap, iso: Isolated): Bitmap? {
        return try {
            val b = iso.box
            val w = b.width()
            val h = b.height()
            if (w < 8 || h < 8) null else Bitmap.createBitmap(bitmap, b.left, b.top, w, h)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isolateCaptchaDigit(bitmap: Bitmap): Isolated? {
        return try {
            val top = (bitmap.height * 0.17f).toInt()
            val bottom = (bitmap.height * 0.45f).toInt()
            val left = (bitmap.width * 0.05f).toInt()
            val cw = (bitmap.width * 0.90f).toInt()
            val ch = bottom - top
            if (cw < 40 || ch < 40 || left + cw > bitmap.width) return null

            val px = ensureBuf(cw * ch)
            bitmap.getPixels(px, 0, cw, left, top, cw, ch)

            val gw = cw / 2
            val gh = ch / 2
            val hsv = FloatArray(3)
            val hue = FloatArray(gw * gh)
            val sat = BooleanArray(gw * gh)
            val hist = IntArray(72)

            for (gy in 0 until gh) {
                val base = gy * 2 * cw
                for (gx in 0 until gw) {
                    Color.colorToHSV(px[base + gx * 2], hsv)
                    if (hsv[1] > 0.38f && hsv[2] > 0.40f) {
                        val i = gy * gw + gx
                        sat[i] = true
                        hue[i] = hsv[0]
                        hist[(hsv[0] / 5f).toInt() % 72]++
                    }
                }
            }

            // أقوى ألوان في الصورة (الخطوط الملوّنة قد تغلب لون الرقم، فنجرّب عدة ألوان)
            val order = (0 until 72).sortedByDescending {
                hist[(it + 71) % 72] + hist[it] + hist[(it + 1) % 72]
            }
            val cands = ArrayList<Int>()
            for (i in order) {
                val sc = hist[(i + 71) % 72] + hist[i] + hist[(i + 1) % 72]
                if (sc < 60) break
                if (cands.all { j -> min(abs(i - j), 72 - abs(i - j)) >= 3 }) cands.add(i)
                if (cands.size >= 6) break
            }
            if (cands.isEmpty()) return null

            val n = gw * gh
            val keep = BooleanArray(n)
            val mask = BooleanArray(n)
            val lab = IntArray(n)
            val queue = IntArray(n)
            val tmp = BooleanArray(n)
            var fin: BooleanArray? = null
            var bestArea = 0

            for (pk in cands) {
                val center = pk * 5f + 2.5f
                java.util.Arrays.fill(keep, false)
                java.util.Arrays.fill(mask, false)
                java.util.Arrays.fill(lab, -1)
                for (i in 0 until n) {
                    if (sat[i]) {
                        var d = abs(hue[i] - center)
                        if (d > 180f) d = 360f - d
                        keep[i] = d <= 18f
                    }
                }
                // سدّ الشقوق اللي تسببها الخطوط الرفيعة العابرة فوق الرقم
                closeMask(keep, gw, gh, tmp)
                // فلتر الأغلبية: يحذف الخطوط الرفيعة والنقاط
                for (gy in 1 until gh - 1) {
                    for (gx in 1 until gw - 1) {
                        val i = gy * gw + gx
                        if (!keep[i]) continue
                        var c = 0
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if ((dx != 0 || dy != 0) && keep[i + dy * gw + dx]) c++
                            }
                        }
                        if (c >= 6) mask[i] = true
                    }
                }
                // الكتل المتصلة + صندوق كل كتلة
                val areas = ArrayList<Int>()
                val boxes = ArrayList<IntArray>()
                for (start in 0 until n) {
                    if (!mask[start] || lab[start] >= 0) continue
                    val id = areas.size
                    var head = 0
                    var tail = 0
                    queue[tail++] = start
                    lab[start] = id
                    var count = 0
                    var x0 = gw
                    var x1 = -1
                    var y0 = gh
                    var y1 = -1
                    while (head < tail) {
                        val cur = queue[head++]
                        count++
                        val cx = cur % gw
                        val cy = cur / gw
                        if (cx < x0) x0 = cx
                        if (cx > x1) x1 = cx
                        if (cy < y0) y0 = cy
                        if (cy > y1) y1 = cy
                        for (dy in -1..1) {
                            val ny = cy + dy
                            if (ny < 0 || ny >= gh) continue
                            for (dx in -1..1) {
                                val nx = cx + dx
                                if (nx < 0 || nx >= gw) continue
                                val ni = ny * gw + nx
                                if (mask[ni] && lab[ni] < 0) {
                                    lab[ni] = id
                                    queue[tail++] = ni
                                }
                            }
                        }
                    }
                    areas.add(count)
                    boxes.add(intArrayOf(x0, x1, y0, y1))
                }
                // الرقم كتلة «ممتلئة» وصغيرة؛ الخطوط طويلة وفارغة فتُستبعد
                val ok = BooleanArray(areas.size)
                var maxA = 0
                var maxId = -1
                for (id in areas.indices) {
                    val bx = boxes[id]
                    val bw = bx[1] - bx[0] + 1
                    val bh = bx[3] - bx[2] + 1
                    val fill = areas[id].toFloat() / (bw * bh)
                    if (areas[id] >= 40 && fill >= 0.2f && bh >= 12 && bh <= gh * 0.6f && bw <= gw * 0.4f) {
                        ok[id] = true
                        if (areas[id] > maxA) {
                            maxA = areas[id]
                            maxId = id
                        }
                    }
                }
                if (maxA > bestArea) {
                    val f = BooleanArray(n)
                    val mb = boxes[maxId]
                    val ex = 14
                    for (i in 0 until n) {
                        if (!mask[i]) continue
                        val id = lab[i]
                        val bx = boxes[id]
                        // قطع الرقم الصغيرة القريبة من الكتلة الرئيسية تبقى
                        val near = areas[id] >= 10 &&
                            bx[1] >= mb[0] - ex && bx[0] <= mb[1] + ex &&
                            bx[3] >= mb[2] - ex && bx[2] <= mb[3] + ex
                        if ((ok[id] && areas[id] >= maxA / 4) || near) f[i] = true
                    }
                    fin = f
                    bestArea = maxA
                }
                if (bestArea >= 250) break
            }
            val finMask = fin ?: return null
            var minGx = gw
            var maxGx = -1
            var minGy = gh
            var maxGy = -1
            for (i in 0 until n) {
                if (finMask[i]) {
                    val gx = i % gw
                    val gy = i / gw
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                }
            }
            if (maxGx < 0 || maxGx - minGx < 12 || maxGy - minGy < 12) return null

            val m = 10
            val ow = (maxGx - minGx + 1 + 2 * m) * 2
            val oh = (maxGy - minGy + 1 + 2 * m) * 2
            val out = IntArray(ow * oh) { Color.WHITE }
            for (gy in minGy..maxGy) {
                for (gx in minGx..maxGx) {
                    if (!finMask[gy * gw + gx]) continue
                    val ox = (gx - minGx + m) * 2
                    val oy = (gy - minGy + m) * 2
                    for (yy in (oy - 1)..(oy + 2)) {
                        if (yy < 0 || yy >= oh) continue
                        for (xx in (ox - 1)..(ox + 2)) {
                            if (xx < 0 || xx >= ow) continue
                            out[yy * ow + xx] = Color.BLACK
                        }
                    }
                }
            }
            val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            bmp.setPixels(out, 0, ow, 0, 0, ow, oh)
            val mg = 36
            val box = Rect(
                max(0, left + minGx * 2 - mg),
                max(0, top + minGy * 2 - mg),
                min(bitmap.width, left + (maxGx + 1) * 2 + mg),
                min(bitmap.height, top + (maxGy + 1) * 2 + mg)
            )
            Isolated(bmp, finMask, gw, gh, box)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun readSingleDigit(image: Bitmap): String? {
        return try {
            val t = runOcr(digitRecognizer, image)
            var found: String? = null
            for (block in t.textBlocks) {
                for (line in block.lines) {
                    if (found == null) found = extractSingleDigit0to9(line.text)
                }
            }
            found
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun readDigitFromCaptchaRegion(bitmap: Bitmap): String? {
        return try {
            val top = (bitmap.height * 0.20f).toInt()
            val bottom = (bitmap.height * 0.44f).toInt()
            val left = (bitmap.width * 0.05f).toInt()
            val cw = (bitmap.width * 0.90f).toInt()
            if (cw < 40 || bottom - top < 40 || left + cw > bitmap.width) return null
            val region = Bitmap.createBitmap(bitmap, left, top, cw, bottom - top)
            try {
                readSingleDigit(region)
            } finally {
                region.recycle()
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ───────────────────────── أدوات ─────────────────────────

    private fun isCoral(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r > 225 && g in 71..134 && b in 71..134 && abs(g - b) < 40
    }

    private fun isNameRed(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r > 210 && g in 46..114 && b < 82 && r - b > 140
    }

    private fun isWhiteText(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return min(r, min(g, b)) > 200
    }

    /** بصمة شبكة 64x16: نسبة بكسلات النص في كل خلية داخل المستطيل */
    private inline fun gridSignature(bitmap: Bitmap, rect: Rect, isOn: (Int) -> Boolean): FloatArray {
        val cw = min(rect.width() + 1, bitmap.width - rect.left)
        val ch = min(rect.height() + 1, bitmap.height - rect.top)
        val sums = FloatArray(LearnedStore.SIG_SIZE)
        if (cw <= 0 || ch <= 0) return sums
        val cnts = IntArray(LearnedStore.SIG_SIZE)
        val px = ensureBuf(cw * ch)
        bitmap.getPixels(px, 0, cw, rect.left, rect.top, cw, ch)
        for (y in 0 until ch) {
            val gy = min(LearnedStore.SIG_H - 1, y * LearnedStore.SIG_H / ch)
            val base = y * cw
            for (x in 0 until cw) {
                val gx = min(LearnedStore.SIG_W - 1, x * LearnedStore.SIG_W / cw)
                val idx = gy * LearnedStore.SIG_W + gx
                cnts[idx]++
                if (isOn(px[base + x])) sums[idx] += 1f
            }
        }
        for (i in sums.indices) {
            sums[i] = if (cnts[i] > 0) sums[i] / cnts[i] else 0f
        }
        return sums
    }

    /** صورة مصغّرة للمستطيل (عرض أقصى 360px) لعرضها في التطبيق */
    private fun thumbOf(bitmap: Bitmap, r: Rect, pad: Int): Bitmap? {
        return try {
            val l = max(0, r.left - pad)
            val t = max(0, r.top - pad)
            val rr = min(bitmap.width, r.right + 1 + pad)
            val bb = min(bitmap.height, r.bottom + 1 + pad)
            val cw = rr - l
            val ch = bb - t
            if (cw < 8 || ch < 8) return null
            val crop = Bitmap.createBitmap(bitmap, l, t, cw, ch)
            if (cw <= 360) {
                crop
            } else {
                val sh = max(1, ch * 360 / cw)
                val scaled = Bitmap.createScaledBitmap(crop, 360, sh, true)
                if (scaled !== crop) crop.recycle()
                scaled
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun percentileIndex(counts: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).toInt()
        var acc = 0
        for (i in counts.indices) {
            acc += counts[i]
            if (acc > target) return i
        }
        return counts.size - 1
    }

    private fun differs(p: Int, bg: Int, thr: Int): Boolean {
        val d = abs(((p shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) +
            abs(((p shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) +
            abs((p and 0xFF) - (bg and 0xFF))
        return d > thr
    }

    private fun horizontalRuns(
        row: IntArray,
        w: Int,
        bg: Int,
        thr: Int,
        minLen: Int,
        maxLen: Int,
        gapTol: Int = 0
    ): List<IntArray> {
        val out = ArrayList<IntArray>()
        var s = -1
        var last = -1
        for (x in 0..w) {
            val on = x < w && differs(row[x], bg, thr)
            if (on) {
                if (s < 0) s = x
                last = x
            } else if (s >= 0 && (x >= w || x - last > gapTol)) {
                val len = last + 1 - s
                if (len in minLen..maxLen) out.add(intArrayOf(s, last + 1))
                s = -1
            }
        }
        return out
    }

    private fun verticalRuns(col: IntArray, len: Int, bg: Int, thr: Int, minLen: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var s = -1
        for (y in 0..len) {
            val on = y < len && differs(col[y], bg, thr)
            if (on) {
                if (s < 0) s = y
            } else if (s >= 0) {
                if (y - s >= minLen) out.add(intArrayOf(s, y))
                s = -1
            }
        }
        return out
    }

    private fun extractSingleDigit0to9(text: String): String? {
        val digitsOnly = buildString {
            for (ch in text) {
                when (ch) {
                    in '٠'..'٩' -> append('0' + (ch - '٠'))
                    in '۰'..'۹' -> append('0' + (ch - '۰'))
                    in '0'..'9' -> append(ch)
                }
            }
        }
        return if (digitsOnly.length == 1) digitsOnly else null
    }

    private suspend fun runOcr(recognizer: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    fun close() {
        bgScope.cancel()
        digitRecognizer.close()
    }
}
