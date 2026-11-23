package com.talabati.smartauto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, res.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                AutomationStore.setRunning(false, "تم إلغاء إذن الشاشة")
                AutomationStore.updateLog("لم يتم منح إذن تصوير الشاشة")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationStore.init(applicationContext)
        LearnedStore.init(applicationContext)
        CaptchaSamplesStore.init(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val cfg by AutomationStore.config.collectAsState()
            AppTheme {
                SmartAutoScreen(
                    cfg = cfg,
                    onRequestShizuku = { AdbTapExecutor.requestPermission() },
                    onStart = { requestScreenCapture() },
                    onStop = { stopScreenCapture() }
                )
            }
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopScreenCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
    }
}

private val GreenOk = Color(0xFF2E7D32)
private val RedStop = Color(0xFFC62828)
private val GrayOff = Color(0xFF9E9E9E)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFC62828),
            background = Color(0xFFF4F5F7),
            surface = Color.White
        ),
        content = content
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun SmartAutoScreen(
    cfg: AutomationConfig,
    onRequestShizuku: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    // حالة Shizuku تتحدث كل ثانية
    var shizukuReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            shizukuReady = AdbTapExecutor.hasPermission()
            delay(1000L)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Talabati Smart Auto",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // ───── التحكم والحالة ─────
                SectionCard(title = "التحكم") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(if (cfg.isRunning) GreenOk else GrayOff, CircleShape)
                        )
                        Text(text = cfg.statusText)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(if (shizukuReady) GreenOk else RedStop, CircleShape)
                        )
                        Text(text = if (shizukuReady) "Shizuku: جاهز" else "Shizuku: غير جاهز")
                    }

                    if (!shizukuReady) {
                        Text(
                            text = "شغّل تطبيق Shizuku أولاً ثم اضغط «إذن Shizuku». بدونه لن يتم أي ضغط.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RedStop
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRequestShizuku,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إذن Shizuku")
                        }

                        if (!cfg.isRunning) {
                            Button(
                                onClick = onStart,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                            ) {
                                Text("تشغيل")
                            }
                        } else {
                            Button(
                                onClick = onStop,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = RedStop)
                            ) {
                                Text("إيقاف")
                            }
                        }
                    }
                }

                // ───── آخر نشاط ─────
                SectionCard(title = "آخر نشاط") {
                    Text(
                        text = cfg.lastLog,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // ───── المناطق ─────
                SectionCard(title = "المناطق المرصودة") {
                    ZoneGroupsSection()
                }

                // ───── المطاعم ─────
                SectionCard(title = "المطاعم المرصودة") {
                    LearnedItemsSection(
                        kind = LearnedKind.RESTAURANT,
                        flagLabel = "مفعّل",
                        helper = "تظهر هنا مطاعم الطلبات المطابقة لمناطقك. المطعم الجديد يُتجاهل لين تفعّله «مفعّل».",
                        emptyHint = "لم تُرصد مطاعم بعد"
                    )
                }

                // ───── نسخ التعلم ─────
                SectionCard(title = "نسخ التعلم") {
                    LearningTransferSection()
                }

                // ───── الكابتشا ─────
                SectionCard(title = "الكابتشا المرصودة") {
                    CaptchaSamplesSection()
                }

                SectionCard(title = "الرصد التلقائي") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "حفظ المناطق والمطاعم الجديدة تلقائياً")
                        Switch(
                            checked = cfg.autoLearn,
                            onCheckedChange = { AutomationStore.setAutoLearn(it) }
                        )
                    }
                }

                // ───── نقر يدوي أوتوماتيكي ─────
                SectionCard(title = "نقر يدوي أوتوماتيكي") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "تفعيل")
                        Switch(
                            checked = cfg.manualMode,
                            onCheckedChange = { AutomationStore.setManualMode(it) }
                        )
                    }
                    Text(
                        text = "عند التفعيل يتوقف النقر التلقائي. انقر بإصبعك أي مكان بالشاشة: النقرة الأولى تضغط «موافق» والثانية تضغط الرقم الصحيح. إذا الطلب من منطقة/مطعم غير مستهدف يضغط موافق ثم رقم غلط.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GrayOff
                    )
                }

                // ───── السرعة ─────
                SectionCard(title = "السرعة") {
                    Text(
                        text = if (cfg.tapGapMs == 0L) {
                            "ضغطة واحدة على موافق"
                        } else {
                            "ضغطتان على موافق، الفاصل: ${cfg.tapGapMs} ms"
                        }
                    )
                    Slider(
                        value = cfg.tapGapMs.toFloat(),
                        onValueChange = { AutomationStore.setTapGapMs(it.roundToLong()) },
                        valueRange = 0f..100f
                    )
                    Text(
                        text = "0 = ضغطة واحدة فقط. كل ضغطة تنزل بإحداثيات عشوائية داخل الزر.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GrayOff
                    )
                }
            }
        }
    }
}
