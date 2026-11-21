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
        val gh: Int
    )

    private val digitRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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

    suspend fun analyzeFrame(bitmap: Bitmap, cfg: AutomationConfig): ScanResult {
        val greenRect = detectOfferBar(bitmap)
        if (greenRect != null) return analyzeOffer(bitmap, greenRect, cfg)

        val keypad = detectKeypad(bitmap)
        if (keypad != null) return analyzeCaptcha(bitmap, keypad, cfg)

        return ScanResult()
    }

    // ───────────────────────── شاشة الطلب ─────────────────────────

    private fun analyzeOffer(bitmap: Bitmap, greenRect: Rect, cfg: AutomationConfig): ScanResult {
        val pill = findZonePill(bitmap)
            ?: return ScanResult(ScreenKind.OFFER, "طلب ظاهر: لم أجد شريحة المنطقة")

        val zoneSig = gridSignature(bitmap, pill) { p -> isWhiteText(p) }
        val zoneMatch = LearnedStore.matchOrLearn(LearnedKind.ZONE, zoneSig, cfg.autoLearn) {
            thumbOf(bitmap, pill, 6)
        }
        val zone = zoneMatch.item
        if (zone == null || !zone.flagged) {
            val note = if (zoneMatch.isNew) {
                "رُصدت منطقة جديدة — صنّفها من التطبيق"
            } else {
                "منطقة غير مستهدفة"
            }
            return ScanResult(ScreenKind.OFFER, note)
        }

        // مطعم محظور؟ (ويُحفظ المطعم الجديد ليظهر في قائمة المطاعم)
        val nameRect = findRestaurantName(bitmap)
        if (nameRect != null) {
            val nameSig = gridSignature(bitmap, nameRect) { p -> isNameRed(p) }
            val rm = LearnedStore.matchOrLearn(LearnedKind.RESTAURANT, nameSig, cfg.autoLearn) {
                thumbOf(bitmap, nameRect, 8)
            }
            val rest = rm.item
            if (rest != null && rest.flagged) {
                return ScanResult(ScreenKind.OFFER, "مطعم محظور — تم التجاهل")
            }
        }

        // نوع الطلب
        if (cfg.orderFilterMode != OrderFilterMode.ALL) {
            val isRestaurant = detectRestaurantTag(bitmap)
            if (cfg.orderFilterMode == OrderFilterMode.NORMAL_ONLY && isRestaurant) {
                return ScanResult(ScreenKind.OFFER, "طلب مطعم — تم التجاهل (الفلتر: عادي)")
            }
            if (cfg.orderFilterMode == OrderFilterMode.RESTAURANT_ONLY && !isRestaurant) {
                return ScanResult(ScreenKind.OFFER, "طلب عادي — تم التجاهل (الفلتر: مطعم)")
            }
        }

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
    private fun findRestaurantName(bitmap: Bitmap): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val top = (h * 0.525f).toInt()
        val rh = (h * 0.78f).toInt() - top
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
        val top = (h * 0.31f).toInt()
        val rh = (h * 0.46f).toInt() - top
        if (rh < 20) return false

        val px = ensureBuf(w * rh)
        bitmap.getPixels(px, 0, w, 0, top, w, rh)

        var count = 0
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
                x += 2
            }
            y += 2
        }
        return count * 4 >= (0.0004f * w * h).toInt()
    }

    // ───────────────────────── شاشة الكابتشا ─────────────────────────

    private suspend fun analyzeCaptcha(bitmap: Bitmap, keypad: Keypad, cfg: AutomationConfig): ScanResult {
        var digit: String? = null
        var features: FloatArray? = null

        val iso = isolateCaptchaDigit(bitmap)
        if (iso != null) {
            features = CaptchaTrainerStore.featureFromMask(iso.mask, iso.gw, iso.gh)
            digit = readSingleDigit(iso.image)
            if (cfg.autoLearn && features != null) {
                CaptchaSamplesStore.addIfNew(features) { iso.image }
            }
            iso.image.recycle()
        }

        // احتياط: قراءة مباشرة من منطقة الصورة إذا فشل العزل
        if (digit == null) {
            digit = readDigitFromCaptchaRegion(bitmap)
        }

        val ai = CaptchaTrainerStore.predict(context, features)

        val finalDigit: String? = when {
            // رقم علّمه المستخدم (ثقة عالية) له الأولوية على قراءة الـOCR
            ai != null && ai.second >= 0.92f -> ai.first.toString()
            digit != null -> digit
            ai != null && ai.second >= 0.80f -> ai.first.toString()
            else -> null
        }

        // تدريب الـAI فقط من القراءة النظيفة، وعند الاتفاق أو عدم ثقة الـAI
        if (digit != null && features != null && iso != null) {
            if (ai == null || ai.first.toString() == digit || ai.second < 0.50f) {
                CaptchaTrainerStore.trainSample(context, digit[0], features)
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

    /** لوحة الأرقام: 3 أعمدة من الأزرار المستديرة عند 25% و50% و75% من العرض */
    private fun detectKeypad(bitmap: Bitmap): Keypad? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 200 || h < 400) return null

        val bg = bitmap.getPixel((w * 0.06f).toInt(), (h * 0.62f).toInt())
        val row = IntArray(w)
        var good = 0
        var colRuns: List<IntArray>? = null

        for (yf in floatArrayOf(0.66f, 0.76f, 0.85f)) {
            bitmap.getPixels(row, 0, w, 0, (h * yf).toInt(), w, 1)
            val runs = horizontalRuns(row, w, bg, 14, (w * 0.10f).toInt(), (w * 0.28f).toInt())
            if (runs.size == 3) {
                val c0 = (runs[0][0] + runs[0][1]) / 2f / w
                val c1 = (runs[1][0] + runs[1][1]) / 2f / w
                val c2 = (runs[2][0] + runs[2][1]) / 2f / w
                if (abs(c0 - 0.25f) < 0.07f && abs(c1 - 0.50f) < 0.07f && abs(c2 - 0.75f) < 0.07f) {
                    good++
                    if (colRuns == null) colRuns = runs
                }
            }
        }
        if (good < 2 || colRuns == null) return null

        // الصفوف: مسح عمودي عند منتصف الشاشة
        val y0 = (h * 0.58f).toInt()
        val len = h - y0
        val col = IntArray(len)
        bitmap.getPixels(col, 0, 1, w / 2, y0, 1, len)
        val vr = verticalRuns(col, len, bg, 14, (h * 0.04f).toInt())
        if (vr.size < 3) return null

        val rows = ArrayList<IntArray>()
        for (r in vr.take(4)) rows.add(intArrayOf(y0 + r[0], y0 + r[1]))
        if (rows.size == 3) {
            val pitch = rows[2][0] - rows[1][0]
            val s = rows[2][0] + pitch
            val e = min(h - 1, s + (rows[2][1] - rows[2][0]))
            rows.add(intArrayOf(s, e))
        }
        return Keypad(colRuns, rows)
    }

    /**
     * عزل رقم الكابتشا: نختار اللون السائد (الرقم سميك والخطوط والنقاط رفيعة)،
     * نحذف البكسلات المعزولة، ونُبقي أكبر الكتل المتصلة فقط. الناتج صورة سوداء على أبيض.
     */
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
                    if (hsv[1] > 0.45f && hsv[2] > 0.45f) {
                        val i = gy * gw + gx
                        sat[i] = true
                        hue[i] = hsv[0]
                        hist[(hsv[0] / 5f).toInt() % 72]++
                    }
                }
            }

            var pk = -1
            var best = 0
            for (i in 0 until 72) {
                val s = hist[(i + 71) % 72] + hist[i] + hist[(i + 1) % 72]
                if (s > best) {
                    best = s
                    pk = i
                }
            }
            if (pk < 0 || best < 60) return null
            val center = pk * 5f + 2.5f

            val keep = BooleanArray(gw * gh)
            for (i in keep.indices) {
                if (sat[i]) {
                    var d = abs(hue[i] - center)
                    if (d > 180f) d = 360f - d
                    keep[i] = d <= 14f
                }
            }

            // فلتر الأغلبية: يحذف الخطوط الرفيعة والنقاط
            val mask = BooleanArray(gw * gh)
            for (gy in 1 until gh - 1) {
                for (gx in 1 until gw - 1) {
                    val i = gy * gw + gx
                    if (!keep[i]) continue
                    var n = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if ((dx != 0 || dy != 0) && keep[i + dy * gw + dx]) n++
                        }
                    }
                    if (n >= 5) mask[i] = true
                }
            }

            // الكتل المتصلة
            val lab = IntArray(gw * gh) { -1 }
            val queue = IntArray(gw * gh)
            val areas = ArrayList<Int>()
            for (start in mask.indices) {
                if (!mask[start] || lab[start] >= 0) continue
                val id = areas.size
                var head = 0
                var tail = 0
                queue[tail++] = start
                lab[start] = id
                var count = 0
                while (head < tail) {
                    val cur = queue[head++]
                    count++
                    val cx = cur % gw
                    val cy = cur / gw
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
            }
            val maxArea = areas.maxOrNull() ?: return null
            if (maxArea < 40) return null

            val fin = BooleanArray(gw * gh)
            var minGx = gw
            var maxGx = -1
            var minGy = gh
            var maxGy = -1
            for (i in mask.indices) {
                if (mask[i] && areas[lab[i]] >= maxArea / 4) {
                    fin[i] = true
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
                    if (!fin[gy * gw + gx]) continue
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
            Isolated(bmp, fin, gw, gh)
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
        maxLen: Int
    ): List<IntArray> {
        val out = ArrayList<IntArray>()
        var s = -1
        for (x in 0..w) {
            val on = x < w && differs(row[x], bg, thr)
            if (on) {
                if (s < 0) s = x
            } else if (s >= 0) {
                val len = x - s
                if (len in minLen..maxLen) out.add(intArrayOf(s, x))
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
        digitRecognizer.close()
    }
}
