package com.hai.manager

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hai.manager.router.CarrierLockState
import com.hai.manager.router.RouterBrand
import com.hai.manager.router.RouterCarrierLockProbeService
import com.hai.manager.router.RouterCarrierLockSummary
import com.hai.manager.router.RouterInspection
import com.hai.manager.unlock.ConnectedLockState
import com.hai.manager.unlock.UnlockBrand

@Composable
fun RouterCarrierLockCard(inspection: RouterInspection) {
    val context = LocalContext.current
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

        Button(
            onClick = {
                val brand = when (inspection.snapshot.brand) {
                    RouterBrand.HUAWEI -> UnlockBrand.HUAWEI
                    RouterBrand.ZTE -> UnlockBrand.ZTE
                    else -> UnlockBrand.AUTO
                }
                val lockState = when (summary?.state) {
                    CarrierLockState.UNLOCKED -> ConnectedLockState.UNLOCKED
                    CarrierLockState.LOCKED -> ConnectedLockState.LOCKED
                    else -> ConnectedLockState.UNKNOWN
                }
                context.startActivity(
                    Intent(context, ImeiUnlockActivity::class.java)
                        .putExtra(ImeiUnlockActivity.EXTRA_IMEI, inspection.device?.imei)
                        .putExtra(ImeiUnlockActivity.EXTRA_MODEL, inspection.device?.model)
                        .putExtra(ImeiUnlockActivity.EXTRA_BRAND, brand.name)
                        .putExtra(ImeiUnlockActivity.EXTRA_CONNECTED, true)
                        .putExtra(ImeiUnlockActivity.EXTRA_LOCK_STATE, lockState.name)
                        .putExtra(ImeiUnlockActivity.EXTRA_ATTEMPTS, summary?.attemptsRemaining)
                        .putExtra(ImeiUnlockActivity.EXTRA_FIRMWARE, inspection.device?.firmwareVersion)
                        .putExtra(ImeiUnlockActivity.EXTRA_OPERATOR, summary?.currentOperator)
                        .putExtra(ImeiUnlockActivity.EXTRA_LOCK_SOURCE, summary?.source)
                )
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("فتح مركز فك وتشخيص القفل")
        }
    }
}
