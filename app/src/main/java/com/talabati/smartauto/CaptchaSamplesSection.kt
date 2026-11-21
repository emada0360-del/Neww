package com.talabati.smartauto

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** أرقام الكابتشا المرصودة: صورة الرقم + حقل تكتب فيه الرقم الصحيح ليتعلّمه التطبيق */
@Composable
fun CaptchaSamplesSection() {
    val list by CaptchaSamplesStore.items.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "تظهر هنا صور الكابتشا. إذا ضغطت رقماً بنفسك ينكتب جانبها تلقائياً، وتقدر تعدّله بالحقل.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        val labeled = list.count { it.label.isNotEmpty() }
        Text(
            text = "المحفوظ: ${list.size} / ${CaptchaSamplesStore.MAX_ITEMS}   (بأرقام: $labeled)",
            style = MaterialTheme.typography.bodyMedium
        )
        if (list.isEmpty()) {
            Text(text = "لم تُرصد كابتشا بعد", style = MaterialTheme.typography.bodyMedium)
        }
        if (list.size > 60) {
            Text(
                text = "يعرض آخر 60 فقط (الباقي محفوظ ويشتغل)",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        for (item in list.take(60)) {
            CaptchaRow(item)
        }
        if (list.any { it.label.isEmpty() }) {
            TextButton(onClick = { CaptchaSamplesStore.clearUnlabeled() }) {
                Text("مسح اللي بدون رقم")
            }
        }
    }
}

@Composable
private fun CaptchaRow(item: CaptchaSample) {
    val image = remember(item.id) {
        try {
            BitmapFactory.decodeByteArray(item.thumbPng, 0, item.thumbPng.size)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
    var text by remember(item.id, item.label) { mutableStateOf(item.label) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 90.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(text = "(بدون صورة)", modifier = Modifier.weight(1f))
        }

        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                val d = v.filter { it in '0'..'9' }.takeLast(1)
                text = d
                CaptchaSamplesStore.setLabel(item.id, d)
            },
            modifier = Modifier.width(76.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        TextButton(onClick = { CaptchaSamplesStore.delete(item.id) }) {
            Text("حذف")
        }
    }
}
