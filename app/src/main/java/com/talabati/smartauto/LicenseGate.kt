package com.talabati.smartauto

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** يعرض محتوى التطبيق فقط إذا كان الجهاز مفعّلاً، وإلا يعرض شاشة قفل مع آيدي الجهاز */
@Composable
fun LicenseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(LicenseManager.loadCached(context)) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val fresh = LicenseManager.refresh(context)
            if (fresh != null) status = fresh
            checked = true
            delay(20_000L)
        }
    }

    val valid = status?.isValidNow() == true
    if (valid) {
        content()
    } else {
        LockedScreen(deviceId = LicenseManager.deviceId(context), status = status, checked = checked)
    }
}

@Composable
private fun LockedScreen(deviceId: String, status: LicenseStatus?, checked: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = statusTitle(status, checked),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
            statusSubtitle(status, checked)?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(10.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "آيدي الجهاز (أرسله للمطوّر):", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = deviceId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(10.dp))

            Button(onClick = { clipboard.setText(AnnotatedString(deviceId)) }) {
                Text("نسخ الآيدي")
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
            OutlinedButton(onClick = { shareId(context, deviceId) }) {
                Text("إرسال الآيدي")
            }

            if (!checked) {
                androidx.compose.foundation.layout.Spacer(Modifier.padding(14.dp))
                CircularProgressIndicator()
            }
        }
    }
}

private fun shareId(context: Context, id: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "آيدي جهازي لتفعيل التطبيق:\n$id")
    }
    context.startActivity(Intent.createChooser(intent, "إرسال الآيدي"))
}

private fun statusTitle(status: LicenseStatus?, checked: Boolean): String {
    if (!checked) return "جارٍ التحقق من التفعيل..."
    if (status == null) return "تعذّر الاتصال بالخادم"
    if (!status.active) return "التطبيق غير مفعّل"
    return "انتهت صلاحية التفعيل"
}

private fun statusSubtitle(status: LicenseStatus?, checked: Boolean): String? {
    if (!checked) return null
    if (status == null) return "تأكد من الاتصال بالإنترنت وحاول مرة ثانية"
    if (status.active && status.expiresAt > 0L) {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "انتهى بتاريخ: ${f.format(Date(status.expiresAt))}"
    }
    return "أرسل آيدي الجهاز بالأسفل للمطوّر لتفعيله"
}
