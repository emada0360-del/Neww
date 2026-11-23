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

private val GROUP_TITLES = listOf("الطلبات العادية", "طلبات المطعم", "الطلبات البعيدة")

/** ثلاث خانات للمناطق: عادي / مطعم / بعيد، كل خانة بمفتاح رئيسي ومفاتيح مفردة */
@Composable
fun ZoneGroupsSection() {
    val all by LearnedStore.items.collectAsState()
    val zones = all.filter { it.kind == LearnedKind.ZONE }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "المفتاح الرئيسي لكل خانة يشغّل/يطفي كل مناطقها، ويمكنك تعديل كل منطقة لحالها.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        if (zones.isEmpty()) Text("لم تُرصد مناطق بعد")

        for (g in 0..2) {
            fun on(z: LearnedItem) = when (g) { 0 -> z.flagged; 1 -> z.restFlag; else -> z.farFlag }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${GROUP_TITLES[g]} (${zones.count { on(it) }}/${zones.size})",
                    style = MaterialTheme.typography.titleSmall
                )
                Switch(
                    checked = zones.isNotEmpty() && zones.all { on(it) },
                    onCheckedChange = { LearnedStore.setAllZonesGroup(g, it) }
                )
            }
            for (z in zones) {
                ZoneRow(z, g, on(z))
            }
        }

        if (zones.any { !it.flagged && !it.restFlag && !it.farFlag }) {
            TextButton(onClick = { LearnedStore.clearUnflagged(LearnedKind.ZONE) }) {
                Text("مسح غير المصنّفة")
            }
        }
    }
}

@Composable
private fun ZoneRow(item: LearnedItem, group: Int, checked: Boolean) {
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
                    .heightIn(max = 44.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(text = "(بدون صورة)", modifier = Modifier.weight(1f))
        }
        Switch(
            checked = checked,
            onCheckedChange = { LearnedStore.setZoneGroupFlag(item.id, group, it) }
        )
        if (group == 0) {
            TextButton(onClick = { LearnedStore.delete(item.id) }) { Text("حذف") }
        }
    }
}
