package com.hai.manager

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    HaiCard {
        HaiSectionTitle("قفل المشغل")
        when {
            loading -> CircularProgressIndicator()
            summary == null -> Text("غير معروف")
            else -> {
                val result = summary!!
                HaiValueRow("الحالة", result.state.displayName)
                HaiValueRow("المشغل الحالي", result.currentOperator)
                if (result.state == CarrierLockState.LOCKED) {
                    HaiValueRow("المشغل المقيد", result.lockedOperator ?: "غير مكشوف")
                }
                HaiValueRow("المحاولات المتبقية", result.attemptsRemaining)
            }
        }
    }
}
