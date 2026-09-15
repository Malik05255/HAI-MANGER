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
import androidx.compose.ui.unit.dp
import com.hai.manager.unlock.ConnectedLockState
import com.hai.manager.unlock.ConnectedUnlockContext
import com.hai.manager.unlock.ImeiUnlockFacade
import com.hai.manager.unlock.ImeiUnlockReport
import com.hai.manager.unlock.PlatformResolver
import com.hai.manager.unlock.TacResolver
import com.hai.manager.unlock.UnlockBrand
import com.hai.manager.unlock.UnlockConfidence
import com.hai.manager.unlock.UnlockStrategyPlanner

class ImeiUnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialImei = intent.getStringExtra(EXTRA_IMEI).orEmpty()
        val model = intent.getStringExtra(EXTRA_MODEL)
        val brand = intent.getStringExtra(EXTRA_BRAND)
            ?.let { runCatching { UnlockBrand.valueOf(it) }.getOrNull() }
            ?: UnlockBrand.AUTO
        val connectedContext = if (intent.getBooleanExtra(EXTRA_CONNECTED, false)) {
            ConnectedUnlockContext(
                state = intent.getStringExtra(EXTRA_LOCK_STATE)
                    ?.let { runCatching { ConnectedLockState.valueOf(it) }.getOrNull() }
                    ?: ConnectedLockState.UNKNOWN,
                attemptsRemaining = intent.getStringExtra(EXTRA_ATTEMPTS),
                firmware = intent.getStringExtra(EXTRA_FIRMWARE),
                currentOperator = intent.getStringExtra(EXTRA_OPERATOR),
                source = intent.getStringExtra(EXTRA_LOCK_SOURCE),
                modemState = intent.getStringExtra(EXTRA_MODEM_STATE),
                waitingForNck = intent.getBooleanExtra(EXTRA_WAITING_NCK, false),
                lockedHplmns = intent.getStringExtra(EXTRA_LOCKED_HPLMNS),
                nckRelatedValue = intent.getStringExtra(EXTRA_NCK_RAW),
                webVersion = intent.getStringExtra(EXTRA_WEB_VERSION),
                diagnostic = intent.getStringExtra(EXTRA_DIAGNOSTIC)
            )
        } else null

        setContent {
            HaiTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ImeiUnlockScreen(
                        initialImei = initialImei,
                        initialBrand = brand,
                        modelHint = model,
                        connectedContext = connectedContext,
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
        const val EXTRA_CONNECTED = "unlock_connected"
        const val EXTRA_LOCK_STATE = "unlock_lock_state"
        const val EXTRA_ATTEMPTS = "unlock_attempts"
        const val EXTRA_FIRMWARE = "unlock_firmware"
        const val EXTRA_OPERATOR = "unlock_operator"
        const val EXTRA_LOCK_SOURCE = "unlock_lock_source"
        const val EXTRA_MODEM_STATE = "unlock_modem_state"
        const val EXTRA_WAITING_NCK = "unlock_waiting_nck"
        const val EXTRA_LOCKED_HPLMNS = "unlock_locked_hplmns"
        const val EXTRA_NCK_RAW = "unlock_nck_raw"
        const val EXTRA_WEB_VERSION = "unlock_web_version"
        const val EXTRA_DIAGNOSTIC = "unlock_diagnostic"
    }
}

@Composable
private fun ImeiUnlockScreen(
    initialImei: String,
    initialBrand: UnlockBrand,
    modelHint: String?,
    connectedContext: ConnectedUnlockContext?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val normalizedInitialImei = initialImei.filter(Char::isDigit).take(15)
    var imei by remember { mutableStateOf(normalizedInitialImei) }
    var brand by remember { mutableStateOf(initialBrand) }
    var report by remember(normalizedInitialImei, initialBrand, modelHint, connectedContext) {
        mutableStateOf(
            if (connectedContext != null && normalizedInitialImei.length == 15) {
                ImeiUnlockFacade.analyze(normalizedInitialImei, initialBrand, modelHint)
            } else null
        )
    }

    HaiPage(
        title = "فك قفل الشبكة",
        subtitle = if (connectedContext != null) "تشخيص مباشر — Huawei + ZTE" else "IMEI Unlock Lab — Huawei + ZTE"
    ) {
        if (connectedContext != null) {
            HaiCard {
                HaiSectionTitle("الراوتر المتصل")
                HaiValueRow("حالة القفل", connectedContext.state.displayName)
                HaiValueRow("المحاولات المتبقية", connectedContext.attemptsRemaining ?: "غير مكشوف")
                connectedContext.firmware?.takeIf { it.isNotBlank() }?.let { HaiValueRow("Firmware", it) }
                connectedContext.webVersion?.takeIf { it.isNotBlank() }?.let { HaiValueRow("WebUI", it) }
                connectedContext.currentOperator?.takeIf { it.isNotBlank() }?.let { HaiValueRow("الشبكة الحالية", it) }
                connectedContext.modemState?.takeIf { it.isNotBlank() }?.let { HaiValueRow("حالة المودم", it) }
                connectedContext.lockedHplmns?.takeIf { it.isNotBlank() }?.let { HaiValueRow("Locked HPLMN", it) }
                connectedContext.nckRelatedValue?.takeIf { it.isNotBlank() }?.let { HaiValueRow("قيمة NCK الخام", it) }
                if (connectedContext.waitingForNck) {
                    Text("المودم يعلن حالة انتظار NCK.", fontWeight = FontWeight.SemiBold)
                }
                connectedContext.diagnostic?.takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                connectedContext.source?.takeIf { it.isNotBlank() }?.let { Text("المصدر: $it") }
                if (connectedContext.attemptsExhausted) {
                    Text("عداد NCK الصريح = 0. يمنع HAI اعتبار إدخال الكود خطوة متاحة.", fontWeight = FontWeight.SemiBold)
                }
                if (connectedContext.nckRelatedValue != null && connectedContext.attemptsRemaining == null) {
                    Text("قيمة unlock_nck_time ليست مصنفة كعدد محاولات؛ لن يستخدمها HAI لحظر أو السماح بإدخال الكود.")
                }
            }
        }

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
            } else {
                Text("إذا كان TAC معروفًا سيحدد HAI الشركة والموديل تلقائيًا من أول 8 أرقام.")
            }

            if (initialBrand == UnlockBrand.AUTO) {
                Text("الشركة — اختياري عند التعرف من TAC", fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UnlockBrand.entries.filter { it != UnlockBrand.AUTO }.forEach { item ->
                        FilterChip(
                            selected = brand == item,
                            onClick = {
                                brand = if (brand == item) UnlockBrand.AUTO else item
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
                onClick = { report = ImeiUnlockFacade.analyze(imei, brand, modelHint) },
                enabled = imei.length == 15,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (connectedContext != null) "إعادة التحليل" else "تحليل واستخراج الكود")
            }
        }

        report?.let { result ->
            val tacMatch = TacResolver.resolve(result.imei)
            val resolvedModel = tacMatch?.model ?: modelHint
            val platform = PlatformResolver.resolve(resolvedModel)
            val strategy = UnlockStrategyPlanner.plan(result, platform, resolvedModel, connectedContext)
            val codeEntryBlocked = connectedContext?.state == ConnectedLockState.UNLOCKED ||
                connectedContext?.attemptsExhausted == true

            HaiCard {
                HaiSectionTitle("النتيجة")
                HaiValueRow("IMEI", result.imei)
                HaiValueRow("Luhn", if (result.luhnValid) "صحيح" else "غير مطابق — راجع الرقم")
                HaiValueRow("الشركة", result.brand.displayName)
                HaiValueRow("الجيل", result.generation.displayName)
                HaiValueRow("العائلة", result.family)
                Text(result.warning)
            }

            if (tacMatch != null) {
                HaiCard {
                    HaiSectionTitle("التعرف من IMEI")
                    HaiValueRow("TAC", tacMatch.tac)
                    HaiValueRow("الموديل", tacMatch.model)
                    HaiValueRow("Profile", tacMatch.profile)
                    HaiValueRow("الجيل المتوقع", tacMatch.generation.displayName)
                    Text("مصدر المطابقة: ${tacMatch.evidence}")
                    Text("TAC يحدد عائلة الجهاز فقط ولا يرفع ثقة خوارزمية NCK تلقائيًا.")
                }
            } else {
                HaiCard {
                    HaiSectionTitle("TAC غير موجود في الكتالوج")
                    Text("لم يتعرف HAI على أول 8 أرقام من IMEI. يمكنك اختيار الشركة يدويًا، لكن لن يتم اعتبار الموديل موثقًا حتى يضاف TAC إلى القاعدة.")
                }
            }

            if (platform != null) {
                HaiCard {
                    HaiSectionTitle("منصة المودم")
                    HaiValueRow("Chipset", platform.name)
                    HaiValueRow("Platform", platform.family)
                    HaiValueRow("الثقة", platform.confidence.displayName)
                    Text(platform.note)
                    HorizontalDivider()
                    Text("SIM personalization", fontWeight = FontWeight.SemiBold)
                    Text(platform.personalizationProtocol)
                    Text("توليد NCK من IMEI", fontWeight = FontWeight.SemiBold)
                    Text(platform.imeiNckDerivation)
                    HorizontalDivider()
                    Text("طبقات البحث", fontWeight = FontWeight.SemiBold)
                    Text(platform.accessLayers.joinToString(" • "))
                    HorizontalDivider()
                    Text("مشاريع مرجعية", fontWeight = FontWeight.SemiBold)
                    platform.researchProjects.forEach { Text("• $it") }
                }
            }

            UnlockStrategyCard(strategy)

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
                        if (codeEntryBlocked) {
                            Text(
                                if (connectedContext?.state == ConnectedLockState.UNLOCKED) {
                                    "الجهاز غير مقفل؛ لا حاجة لاستخدام هذا الكود."
                                } else {
                                    "عداد المحاولات منتهٍ؛ لا تدخل أو تنسخ الكود للاستخدام على الجهاز قبل معالجة حالة العداد."
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = { copyCode(context, candidate.code) },
                            enabled = !codeEntryBlocked,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("نسخ الكود") }
                    }
                }
            } else {
                HaiCard {
                    HaiSectionTitle("لا يوجد مولد موثّق لهذا الجيل")
                    Text("لن يعرض HAI كودًا تخمينيًا قد يستهلك محاولات NCK. إذا كان الراوتر متصلًا، يستخدم HAI حالة القفل والـFirmware Profile لتحديد المسار الصحيح.")
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
