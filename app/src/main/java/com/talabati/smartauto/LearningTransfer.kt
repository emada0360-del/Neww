package com.talabati.smartauto

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** تصدير/استيراد كل ما تعلّمه التطبيق (مناطق، مطاعم، كابتشا) في ملف zip واحد */
object LearningTransfer {

    private const val F_ITEMS = "learned_items.json"
    private const val F_CAPTCHA = "captcha_samples.json"
    private const val F_TRAINER = "captcha_trainer.json"
    private const val F_SCREEN = "screen.txt"
    private const val THUMB_DIR = "thumbs/"

    private fun screenText(context: Context): String {
        val m = context.resources.displayMetrics
        return "${m.widthPixels}x${m.heightPixels}"
    }

    fun export(context: Context, uri: Uri): String {
        return try {
            val out = context.contentResolver.openOutputStream(uri) ?: return "تعذّر فتح الملف"
            ZipOutputStream(out).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                put(F_SCREEN, screenText(context).toByteArray())
                put(F_ITEMS, LearnedStore.exportJson().toByteArray())
                put(F_CAPTCHA, CaptchaSamplesStore.exportJson().toByteArray())
                put(F_TRAINER, CaptchaTrainerStore.exportJson(context).toByteArray())
                for (s in CaptchaSamplesStore.items.value) {
                    val tb = CaptchaSamplesStore.thumbBytes(s.id) ?: continue
                    put(THUMB_DIR + s.id + ".img", tb)
                }
            }
            "تم التصدير ✓"
        } catch (e: Throwable) {
            "فشل التصدير: ${e.message ?: "غير معروف"}"
        }
    }

    fun import(context: Context, uri: Uri): String {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return "تعذّر فتح الملف"
            var items: String? = null
            var captcha: String? = null
            var trainer: String? = null
            var screen: String? = null
            val thumbs = HashMap<String, ByteArray>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val bos = ByteArrayOutputStream()
                    zip.copyTo(bos)
                    val bytes = bos.toByteArray()
                    when {
                        e.name == F_ITEMS -> items = String(bytes)
                        e.name == F_CAPTCHA -> captcha = String(bytes)
                        e.name == F_TRAINER -> trainer = String(bytes)
                        e.name == F_SCREEN -> screen = String(bytes)
                        e.name.startsWith(THUMB_DIR) ->
                            thumbs[e.name.removePrefix(THUMB_DIR).removeSuffix(".img")] = bytes
                    }
                }
            }
            if (items == null && captcha == null && trainer == null) return "الملف ما فيه تعلّم"
            val z = items?.let { LearnedStore.importJson(it) } ?: 0
            val c = captcha?.let { CaptchaSamplesStore.importFrom(it, thumbs) } ?: 0
            val t = trainer?.let { CaptchaTrainerStore.importJson(context, it) } ?: 0
            val warn = if (screen != null && screen != screenText(context)) {
                "\\nتنبيه: دقة شاشة المصدر ($screen) تختلف عن جهازك، وقد لا تتطابق المناطق والمطاعم."
            } else {
                ""
            }
            "تم الاستيراد ✓ (مناطق/مطاعم: $z، كابتشا: $c، تدريب: $t)$warn".replace("\\n", "\n")
        } catch (e: Throwable) {
            "فشل الاستيراد: ${e.message ?: "غير معروف"}"
        }
    }
}

@Composable
fun LearningTransferSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            msg = "جاري التصدير..."
            scope.launch {
                msg = withContext(Dispatchers.IO) { LearningTransfer.export(ctx, uri) }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            msg = "جاري الاستيراد..."
            scope.launch {
                msg = withContext(Dispatchers.IO) { LearningTransfer.import(ctx, uri) }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "انسخ كل اللي تعلّمه التطبيق (مناطق، مطاعم، كابتشا) بملف واحد وأرسله لصاحبك. الاستيراد يضيف على اللي عنده بدون ما يمسح شي.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { exportLauncher.launch("talabati-learning.zip") },
                modifier = Modifier.weight(1f)
            ) { Text("تصدير") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("استيراد") }
        }
        if (msg.isNotEmpty()) {
            Text(text = msg, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "يشتغل أحسن إذا جهاز صاحبك نفس الدقة وحجم الخط.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}
