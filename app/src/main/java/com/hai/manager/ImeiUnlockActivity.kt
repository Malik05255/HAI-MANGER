package com.hai.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import com.hai.manager.unlock.ImeiUnlockEngine
import com.hai.manager.unlock.ImeiUnlockReport
import com.hai.manager.unlock.UnlockBrand
import com.hai.manager.unlock.UnlockConfidence

class ImeiUnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialImei = intent.getStringExtra(EXTRA_IMEI).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL)
        val brand = intent.getStringExtra(EXTRA_BRAND)
            ?.let { runCatching { UnlockBrand.valueOf(it) }.getOrNull() }
            ?: UnlockBrand.AUTO

        setContent {
            HaiTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ImeiUnlockScreen(
                        initialImei = initialImei,
                        initialBrand = brand,
                        modelHint = model,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_IMEI = "unlock_imei"
        const val EXTRA_MODEL = "unlock_model"
        const val EXTRA_BRAND = "unlock_brand"
    }
}

@Composable
private fun ImeiUnlockScreen(
    initialImei: String,
    initialBrand: UnlockBrand,
    modelHint: String?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var imei by remember { mutableStateOf(initialImei.filter(Char::isDigit).take(15)) }
    var brand by remember { mutableStateOf(initialBrand) }
    var report by remember { mutableStateOf<ImeiUnlockReport?>(null) }

    HaiPage(
        title = "فك قفل الشبكة",
        subtitle = "IMEI Unlock Lab — Huawei + ZTE"
    ) {
        HaiCard {
            HaiSectionTitle("IMEI")
            OutlinedTextField(
                value = imei,
                onValueChange = { value ->
                    imei = value.filter(Char::isDigit).take(15)
                    report = null
                },
                label = { Text("15 رقمًا") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (!modelHint.isNullOrBlank()) {
                HaiValueRow("الموديل المكتشف", modelHint)
            }

            if (initialBrand == UnlockBrand.AUTO) {
                Text("الشركة", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.dp(8f))) {
                    UnlockBrand.entries.filter { it != UnlockBrand.AUTO }.forEach { item ->
                        FilterChip(
                            selected = brand == item,
                            onClick = {
                                brand = item
                                report = null
                            },
                            label = { Text(item.displayName) }
                        )
                    }
                }
            } else {
                HaiValueRow("الشركة", initialBrand.displayName)
            }

            Button(
                onClick = { report = ImeiUnlockEngine.analyze(imei, brand, modelHint) },
                enabled = imei.length == 15 && brand != UnlockBrand.AUTO,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("تحليل واستخراج الكود")
            }
        }

        report?.let { result ->
            HaiCard {
                HaiSectionTitle("النتيجة")
                HaiValueRow("IMEI", result.imei)
                HaiValueRow("Luhn", if (result.luhnValid) "صحيح" else "غير مطابق — راجع الرقم")
                HaiValueRow("الشركة", result.brand.displayName)
                HaiValueRow("الجيل", result.generation.displayName)
                HaiValueRow("العائلة", result.family)
                Text(result.warning)
            }

            if (result.codes.isNotEmpty()) {
                result.codes.forEach { candidate ->
                    HaiCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(candidate.label, fontWeight = FontWeight.SemiBold)
                            HaiStatusChip(
                                candidate.confidence.displayName,
                                active = candidate.confidence == UnlockConfidence.VERIFIED
                            )
                        }
                        Text(candidate.code, fontWeight = FontWeight.Bold)
                        Text(candidate.family)
                        Text(candidate.note)
                        OutlinedButton(
                            onClick = { copyCode(context, candidate.code) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("نسخ الكود") }
                    }
                }
            } else {
                HaiCard {
                    HaiSectionTitle("لا يوجد مولد موثّق لهذا الجيل")
                    Text("لن يعرض HAI كودًا تخمينيًا قد يستهلك محاولات NCK. استخدم صفحة قفل المشغل لقراءة الحالة والعداد عند الاتصال بالراوتر.")
                }
            }

            if (result.sourceNotes.isNotEmpty()) {
                HaiCard {
                    HaiSectionTitle("مصادر المحرك")
                    result.sourceNotes.forEachIndexed { index, note ->
                        if (index > 0) HorizontalDivider()
                        Text(note)
                    }
                }
            }
        }

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("رجوع")
        }
    }
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("NCK", code))
}
