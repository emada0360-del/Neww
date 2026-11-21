package com.talabati.smartauto

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * قائمة العناصر المرصودة من الشاشة (مناطق أو مطاعم) مع صورتها.
 * المفتاح: للمنطقة = «مستهدفة»، وللمطعم = «محظور».
 */
@Composable
fun LearnedItemsSection(
    kind: LearnedKind,
    flagLabel: String,
    helper: String,
    emptyHint: String
) {
    val all by LearnedStore.items.collectAsState()
    val list = all.filter { it.kind == kind }.sortedByDescending { it.flagged }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        if (list.isEmpty()) {
            Text(text = emptyHint, style = MaterialTheme.typography.bodyMedium)
        }

        for (item in list) {
            LearnedItemRow(item = item, flagLabel = flagLabel)
        }

        if (list.any { !it.flagged }) {
            TextButton(onClick = { LearnedStore.clearUnflagged(kind) }) {
                Text("مسح غير المصنّفة")
            }
        }
    }
}

@Composable
private fun LearnedItemRow(item: LearnedItem, flagLabel: String) {
    val image = remember(item.id) {
        try {
            BitmapFactory.decodeByteArray(item.thumbPng, 0, item.thumbPng.size)?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

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
                    .heightIn(max = 56.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(text = "(بدون صورة)", modifier = Modifier.weight(1f))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = item.flagged,
                onCheckedChange = { LearnedStore.setFlag(item.id, it) }
            )
            Text(text = flagLabel, style = MaterialTheme.typography.bodySmall)
        }

        TextButton(onClick = { LearnedStore.delete(item.id) }) {
            Text("حذف")
        }
    }
}
