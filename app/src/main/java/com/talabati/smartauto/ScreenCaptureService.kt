package com.talabati.smartauto

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_CAPTURE"
        const val ACTION_STOP = "ACTION_STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "smart_auto_capture_channel"
        private const val NOTIF_ID = 4041

        // أوقات التهدئة بعد كل ضغطة (حتى لا نعتمد على لقطة قديمة للشاشة السابقة)
        private const val ACCEPT_COOLDOWN_MS = 2000L
        private const val DIGIT_COOLDOWN_MS = 350L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val aiEngine by lazy { AiScreenEngine(applicationContext) }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var scanJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AutomationStore.init(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundWithProjectionType()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startCapture(resultCode, resultData)
                } else {
                    AutomationStore.setRunning(false, "فشل إذن تصوير الشاشة")
                    AutomationStore.updateLog("فشل بدء التصوير: بيانات الإذن غير مكتملة")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** الحجم الحقيقي للشاشة (يشمل شريط التنقل) حتى تطابق إحداثيات الصورة إحداثيات الضغط */
    private fun realScreenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(real)
            Triple(real.widthPixels, real.heightPixels, dpi)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            val cached = LicenseManager.loadCached(applicationContext)
            if (cached?.isValidNow() != true) {
                AutomationStore.setRunning(false, "التطبيق غير مفعّل")
                AutomationStore.updateLog("التطبيق غير مفعّل — فعّله من الشاشة الرئيسية أولاً")
                stopSelf()
                return
            }
            stopCapture()

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                AutomationStore.setRunning(false, "تعذّر تشغيل MediaProjection")
                AutomationStore.updateLog("تعذّر الحصول على MediaProjection")
                stopSelf()
                return
            }

            mediaProjection = projection
            // أندرويد 14+ يتطلب تسجيل callback قبل إنشاء VirtualDisplay
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, null)

            val (width, height, density) = realScreenSize()

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay = projection.createVirtualDisplay(
                "SmartAutoDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            AutomationStore.setRunning(true, "المراقبة الحية شغالة الآن...")
            AutomationStore.updateLog("تم تشغيل مراقبة الشاشة بنجاح")
            AdbTapExecutor.warmUp()

            scanJob = serviceScope.launch {
                var nextAcceptAt = 0L
                var nextDigitAt = 0L
                var lastLicenseCheckAt = 0L
                while (isActive && AutomationStore.config.value.isRunning) {
                    val bitmap = acquireLatestBitmap(reader)
                    if (bitmap == null) {
                        delay(6L)
                        continue
                    }
                    try {
                        val nowLic = System.currentTimeMillis()
                        if (nowLic - lastLicenseCheckAt > 15_000L) {
                            lastLicenseCheckAt = nowLic
                            if (LicenseManager.loadCached(applicationContext)?.isValidNow() != true) {
                                AutomationStore.updateLog("تم إيقاف المراقبة: التفعيل غير صالح")
                                stopCapture()
                                stopSelf()
                                break
                            }
                            launch(Dispatchers.IO) { LicenseManager.refresh(applicationContext) }
                        }
                        val cfg = AutomationStore.config.value
                        val result = aiEngine.analyzeFrame(bitmap, cfg)
                        val now = System.currentTimeMillis()
                        val rect = result.tapRect

                        when (result.kind) {
                            ScreenKind.OFFER -> {
                                if (rect != null) {
                                    if (now >= nextAcceptAt) {
                                        AutomationStore.updateLog(result.note)
                                        AdbTapExecutor.executeAccept(rect, cfg.tapGapMs)
                                        nextAcceptAt = System.currentTimeMillis() +
                                            ACCEPT_COOLDOWN_MS + Random.nextLong(0L, 60L)
                                    }
                                } else {
                                    AutomationStore.updateLog(result.note)
                                }
                            }

                            ScreenKind.CAPTCHA -> {
                                if (rect != null) {
                                    if (now >= nextDigitAt) {
                                        AutomationStore.updateLog(result.note)
                                        AdbTapExecutor.executeDigit(rect)
                                        nextDigitAt = System.currentTimeMillis() +
                                            DIGIT_COOLDOWN_MS + Random.nextLong(0L, 60L)
                                    }
                                } else {
                                    AutomationStore.updateLog(result.note)
                                }
                            }

                            ScreenKind.NONE -> Unit
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AutomationStore.updateLog("خطأ تحليل: ${e.message ?: "غير معروف"}")
                    } finally {
                        bitmap.recycle()
                    }
                    delay(6L)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AutomationStore.setRunning(false, "تعذّر بدء المراقبة")
            AutomationStore.updateLog("خطأ بدء التصوير: ${e.message ?: "غير معروف"}")
            stopSelf()
        }
    }

    private fun acquireLatestBitmap(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                rawBitmap
            } else {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
                rawBitmap.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        scanJob?.cancel()
        scanJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        AutomationStore.setRunning(false, "متوقف")
    }

    override fun onDestroy() {
        stopCapture()
        aiEngine.close()
        AdbTapExecutor.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مراقبة الشاشة الذكية",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Talabati Smart Auto")
            .setContentText("مراقبة الشاشة والضغط التلقائي عبر ADB/Shizuku")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
