package com.hai.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hai.manager.catalog.DeviceCatalogRepository
import com.hai.manager.router.FirmwareCandidate
import com.hai.manager.router.FirmwareSearchSource
import com.hai.manager.router.RouterCapabilityProbeService
import com.hai.manager.router.RouterDiscoveryService
import com.hai.manager.router.RouterFirmwareService
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
    val catalog = remember { DeviceCatalogRepository(context.applicationContext) }

    var loading by remember { mutableStateOf(true) }
    var inspection by remember { mutableStateOf<RouterInspection?>(null) }
    var selectedSource by remember { mutableStateOf(FirmwareSearchSource.OFFICIAL) }
    var candidate by remember { mutableStateOf<FirmwareCandidate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var searching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableIntStateOf(0) }
    var searchStage by remember { mutableStateOf("") }

    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableIntStateOf(0) }
    var installStage by remember { mutableStateOf("") }
    var confirmInstall by remember { mutableStateOf(false) }

    suspend fun loadRouter() {
        loading = true
        message = null
        val found = discovery.discover()
        inspection = if (found.connected && found.managementUrl != null) {
            runCatching { capabilityProbe.enrich(inspector.inspect(found)) }.getOrNull()
        } else null
        loading = false
    }

    fun chooseSource(source: FirmwareSearchSource) {
        if (searching || installing) return
        selectedSource = source
        candidate = null
        message = null
        searchProgress = 0
        searchStage = ""
    }

    fun searchUpdates() {
        val current = inspection ?: return
        scope.launch {
            searching = true
            candidate = null
            message = null
            searchProgress = 0
            searchStage = "بدء البحث"

            val catalogJson = if (selectedSource == FirmwareSearchSource.COMPANIES) {
                searchStage = "تحديث قاعدة التوافق"
                searchProgress = 5
                catalog.sync()
                catalog.cachedJson()
            } else {
                catalog.cachedJson()
            }

            val result = firmware.search(current, selectedSource, catalogJson) { progress, stage ->
                searchProgress = maxOf(searchProgress, progress.coerceIn(0, 100))
                searchStage = stage
            }
            candidate = result.candidate
            message = result.message
            searching = false
        }
    }

    fun executeUpdate() {
        val current = inspection ?: return
        val update = candidate ?: return
        scope.launch {
            installing = true
            message = null
            installProgress = 0
            installStage = "بدء التحقق"
            val result = firmware.execute(current, update) { progress, stage ->
                installProgress = maxOf(installProgress, progress.coerceIn(0, 100))
                installStage = stage
            }
            message = result.message
            installing = false
        }
    }

    LaunchedEffect(Unit) { loadRouter() }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { if (!installing) confirmInstall = false },
            title = { Text("تنفيذ التحديث؟") },
            text = { Text("سيتم التحقق من التوافق أولًا ثم يبدأ التحديث. لا تفصل الكهرباء عن الراوتر.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmInstall = false
                    executeUpdate()
                }) { Text("تنفيذ") }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false }) { Text("إلغاء") }
            }
        )
    }

    HaiPage(title = "تحديث نظام الراوتر") {
        when {
            loading -> HaiCard { CircularProgressIndicator() }
            inspection == null -> HaiCard {
                Text("تعذر العثور على الراوتر")
                Button(onClick = { scope.launch { loadRouter() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("إعادة المحاولة")
                }
            }
            else -> {
                val current = inspection!!

                HaiCard {
                    HaiSectionTitle("النظام الحالي")
                    HaiValueRow("الراوتر", current.device?.model ?: current.snapshot.brand.displayName)
                    HaiValueRow("Firmware", current.device?.firmwareVersion)
                    HaiValueRow("Hardware", current.device?.hardwareVersion)
                }

                HaiCard {
                    HaiSectionTitle("مصدر التحديث")

                    if (selectedSource == FirmwareSearchSource.OFFICIAL) {
                        Button(
                            onClick = { chooseSource(FirmwareSearchSource.OFFICIAL) },
                            enabled = !searching && !installing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(FirmwareSearchSource.OFFICIAL.displayName) }
                    } else {
                        OutlinedButton(
                            onClick = { chooseSource(FirmwareSearchSource.OFFICIAL) },
                            enabled = !searching && !installing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(FirmwareSearchSource.OFFICIAL.displayName) }
                    }

                    if (selectedSource == FirmwareSearchSource.COMPANIES) {
                        Button(
                            onClick = { chooseSource(FirmwareSearchSource.COMPANIES) },
                            enabled = !searching && !installing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(FirmwareSearchSource.COMPANIES.displayName) }
                    } else {
                        OutlinedButton(
                            onClick = { chooseSource(FirmwareSearchSource.COMPANIES) },
                            enabled = !searching && !installing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(FirmwareSearchSource.COMPANIES.displayName) }
                    }

                    Button(
                        onClick = ::searchUpdates,
                        enabled = !searching && !installing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                    ) { Text("جلب التحديث") }
                }

                if (searching) {
                    ProgressCard(
                        title = "البحث عن تحديث",
                        progress = searchProgress,
                        stage = searchStage
                    )
                }

                candidate?.let { update ->
                    HaiCard {
                        HaiSectionTitle("التحديث المتاح")
                        Text(update.version, fontWeight = FontWeight.SemiBold)
                        HaiValueRow("المصدر", update.sourceLabel)
                        HaiValueRow("الحجم", update.size)
                        Text(update.summaryArabic)
                        HaiStatusChip(
                            if (update.installable) "جاهز للتنفيذ" else "التثبيت غير متاح بعد",
                            active = update.installable
                        )
                    }

                    Button(
                        onClick = { confirmInstall = true },
                        enabled = update.installable && !searching && !installing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text("تنفيذ التحديث") }
                }

                if (installing) {
                    ProgressCard(
                        title = "تحديث الراوتر",
                        progress = installProgress,
                        stage = installStage
                    )
                }

                message?.let {
                    HaiCard { Text(it) }
                }
            }
        }

        OutlinedButton(
            onClick = onClose,
            enabled = !installing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("رجوع") }
    }
}

@Composable
private fun ProgressCard(title: String, progress: Int, stage: String) {
    HaiCard {
        HaiSectionTitle(title)
        Text("$progress%", fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        if (stage.isNotBlank()) Text(stage)
    }
}
