package com.hai.manager

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.hai.manager.router.CarrierLockState
import com.hai.manager.router.RouterCarrierLockProbeService
import com.hai.manager.router.RouterCarrierLockSummary
import com.hai.manager.router.RouterInspection

@Composable
fun RouterCarrierLockCard(inspection: RouterInspection) {
    val service = remember { RouterCarrierLockProbeService() }
    val identity = listOf(
        inspection.snapshot.managementUrl,
        inspection.snapshot.brand.name,
        inspection.device?.model,
        inspection.device?.firmwareVersion,
        inspection.security?.networkLockState
    ).joinToString("|")
    var loading by remember(identity) { mutableStateOf(true) }
    var summary by remember(identity) { mutableStateOf<RouterCarrierLockSummary?>(null) }

    LaunchedEffect(identity) {
        loading = true
        summary = runCatching { service.probe(inspection) }.getOrNull()
        loading = false
    }

    SimpleCard("قفل المشغل") {
        when {
            loading -> Text("جارٍ التحقق من حالة قفل الشبكة…")
            summary == null -> Text("لم يكشف WebUI حالة قفل المشغل لهذا Firmware.")
            else -> {
                val result = summary!!
                DetailRow("الحالة", result.state.displayName)
                OptionalDetailRow("المشغل الحالي", result.currentOperator)
                when (result.state) {
                    CarrierLockState.LOCKED -> OptionalDetailRow(
                        "المشغل المقيد عليه",
                        result.lockedOperator ?: "غير مكشوف من الراوتر"
                    )
                    CarrierLockState.UNLOCKED -> DetailRow("المشغل المقيد عليه", "لا يوجد قفل ظاهر")
                    CarrierLockState.UNKNOWN -> DetailRow("المشغل المقيد عليه", "غير معروف")
                }
                OptionalDetailRow("محاولات الفك المتبقية", result.attemptsRemaining)
                OptionalDetailRow("القيمة الخام", result.rawState)
                Text(result.source, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                result.note?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
                }
            }
        }
    }
}
