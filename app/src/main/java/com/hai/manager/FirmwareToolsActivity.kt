package com.hai.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import com.hai.manager.router.RouterCapabilityProbeService
import com.hai.manager.router.RouterDiscoveryService
import com.hai.manager.router.RouterFirmwareService
import com.hai.manager.router.RouterFirmwareStatus
import com.hai.manager.router.RouterInspection
import com.hai.manager.router.RouterInspectorService
import kotlinx.coroutines.launch

class FirmwareToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HaiTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    FirmwareToolsScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun FirmwareToolsScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { RouterDiscoveryService(context.applicationContext) }
    val inspector = remember { RouterInspectorService() }
    val capabilityProbe = remember { RouterCapabilityProbeService() }
    val firmware = remember { RouterFirmwareService() }

    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf<RouterInspection?>(null) }
    var status by remember { mutableStateOf<RouterFirmwareStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmInstall by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        message = null
        val found = discovery.discover()
        val current = if (found.connected && found.managementUrl != null) {
            runCatching { capabilityProbe.enrich(inspector.inspect(found)) }.getOrNull()
        } else null
        inspection = current
        status = current?.let { runCatching { firmware.check(it) }.getOrNull() }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text("تحديث نظام الراوتر؟") },
            text = { Text("لا تفصل الكهرباء أو الإنترنت عن الراوتر حتى يكتمل التحديث ويعيد التشغيل.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmInstall = false
                    val current = inspection ?: return@TextButton
                    val currentStatus = status ?: return@TextButton
                    scope.launch {
                        installing = true
                        val result = firmware.install(current, currentStatus)
                        message = result.message
                        installing = false
                        if (result.success) {
                            kotlinx.coroutines.delay(1200)
                            status = runCatching { firmware.check(current) }.getOrNull() ?: status
                        }
                    }
                }) { Text("بدء التحديث") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("إلغاء") } }
        )
    }

    HaiPage(title = "نظام الراوتر") {
        when {
            loading -> HaiCard { CircularProgressIndicator() }
            inspection == null -> HaiCard {
                Text("تعذر العثور على الراوتر")
                Button(onClick = { scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("إعادة المحاولة")
                }
            }
            status == null -> HaiCard {
                Text("تعذر قراءة خدمة التحديث")
                Button(onClick = { scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("إعادة الفحص")
                }
            }
            else -> {
                val current = inspection!!
                val update = status!!

                HaiCard {
                    HaiSectionTitle(current.device?.model ?: current.snapshot.brand.displayName)
                    HaiValueRow("الحالي", update.currentVersion)
                    HaiValueRow("الجديد", update.availableVersion)
                    HaiValueRow("الحجم", update.componentSize)
                    HaiValueRow("المصدر", update.source)
                    HaiStatusChip(update.state, active = update.updateAvailable || update.progressPercent != null)
                    update.progressPercent?.let { HaiValueRow("التقدم", "$it%") }
                }

                if (update.updateAvailable && update.canInstall) {
                    Button(
                        onClick = { confirmInstall = true },
                        enabled = !installing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (installing) CircularProgressIndicator()
                        else Text("تحديث الراوتر الآن")
                    }
                } else if (update.updateAvailable) {
                    HaiCard {
                        Text("تم العثور على تحديث، لكن التثبيت المباشر غير مفعّل لهذا Firmware بعد.")
                    }
                }

                message?.let { Text(it) }

                OutlinedButton(
                    onClick = { scope.launch { refresh() } },
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("البحث عن تحديث") }
            }
        }

        OutlinedButton(onClick = onClose, enabled = !installing, modifier = Modifier.fillMaxWidth()) {
            Text("رجوع")
        }
    }
}
