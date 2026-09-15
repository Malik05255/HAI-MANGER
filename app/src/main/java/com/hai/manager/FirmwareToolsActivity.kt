package com.hai.manager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import com.hai.manager.catalog.DeviceCatalogRepository
import com.hai.manager.router.FirmwareCandidate
import com.hai.manager.router.FirmwareFinding
import com.hai.manager.router.FirmwareSearchSource
import com.hai.manager.router.LocalFirmwareSelection
import com.hai.manager.router.LocalFirmwareService
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

private enum class FirmwareRelation(val label: String) {
    UPGRADE("ترقية"),
    DOWNGRADE("داون قريد"),
    SAME("نفس الإصدار"),
    DIFFERENT("إصدار مختلف")
}

private enum class FirmwareUiSource {
    OFFICIAL,
    COMPANIES,
    LOCAL_FILE
}

@Composable
private fun FirmwareToolsScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { RouterDiscoveryService(context.applicationContext) }
    val inspector = remember { RouterInspectorService() }
    val capabilityProbe = remember { RouterCapabilityProbeService() }
    val firmware = remember { RouterFirmwareService() }
    val localFirmware = remember { LocalFirmwareService(context.applicationContext) }
    val catalog = remember { DeviceCatalogRepository(context.applicationContext) }

    var loading by remember { mutableStateOf(true) }
    var inspection by remember { mutableStateOf<RouterInspection?>(null) }
    var selectedSource by remember { mutableStateOf(FirmwareUiSource.OFFICIAL) }
    var candidate by remember { mutableStateOf<FirmwareCandidate?>(null) }
    var findings by remember { mutableStateOf<List<FirmwareFinding>>(emptyList()) }
    var localSelection by remember { mutableStateOf<LocalFirmwareSelection?>(null) }
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

    fun clearResults() {
        candidate = null
        findings = emptyList()
        localSelection = null
        message = null
        searchProgress = 0
        searchStage = ""
    }

    fun chooseSource(source: FirmwareUiSource) {
        if (searching || installing) return
        selectedSource = source
        clearResults()
    }

    fun searchUpdates() {
        val current = inspection ?: return
        val engineSource = when (selectedSource) {
            FirmwareUiSource.OFFICIAL -> FirmwareSearchSource.OFFICIAL
            FirmwareUiSource.COMPANIES -> FirmwareSearchSource.COMPANIES
            FirmwareUiSource.LOCAL_FILE -> return
        }
        scope.launch {
            searching = true
            candidate = null
            findings = emptyList()
            localSelection = null
            message = null
            searchProgress = 0
            searchStage = "بدء البحث"

            val catalogJson = if (engineSource == FirmwareSearchSource.COMPANIES) {
                searchStage = "تحديث قاعدة المصادر"
                searchProgress = 5
                catalog.sync()
                catalog.firmwareJson()
            } else {
                catalog.firmwareJson()
            }

            val result = firmware.search(current, engineSource, catalogJson) { progress, stage ->
                searchProgress = maxOf(searchProgress, progress.coerceIn(0, 100))
                searchStage = stage
            }
            candidate = result.candidate
            findings = result.findings
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
            val result = if (selectedSource == FirmwareUiSource.LOCAL_FILE && localSelection != null) {
                localFirmware.execute(current, localSelection!!) { progress, stage ->
                    installProgress = maxOf(installProgress, progress.coerceIn(0, 100))
                    installStage = stage
                }
            } else {
                firmware.execute(current, update) { progress, stage ->
                    installProgress = maxOf(installProgress, progress.coerceIn(0, 100))
                    installStage = stage
                }
            }
            message = result.message
            installing = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            inspection?.let { current ->
                scope.launch {
                    selectedSource = FirmwareUiSource.LOCAL_FILE
                    searching = true
                    candidate = null
                    findings = emptyList()
                    localSelection = null
                    message = null
                    searchProgress = 0
                    searchStage = "قراءة الملف"
                    val result = runCatching {
                        localFirmware.prepare(current, catalog.firmwareJson(), uri) { progress, stage ->
                            searchProgress = maxOf(searchProgress, progress.coerceIn(0, 100))
                            searchStage = stage
                        }
                    }
                    result.onSuccess { selection ->
                        localSelection = selection
                        candidate = selection.candidate
                        message = selection.statusMessage
                    }.onFailure { error ->
                        message = error.message ?: "تعذر فحص ملف الـFirmware"
                    }
                    searching = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadRouter() }

    val pendingUpdate = candidate
    if (confirmInstall && pendingUpdate != null) {
        val relation = firmwareRelation(pendingUpdate.currentVersion, pendingUpdate.version)
        val body = when {
            selectedSource == FirmwareUiSource.LOCAL_FILE ->
                "سيتم رفع الملف من الجوال إلى الراوتر بعد إعادة فحص SHA-256 وهوية الموديل والـHardware. لا تفصل الكهرباء أو Wi-Fi حتى تنتهي العملية."
            relation == FirmwareRelation.DOWNGRADE ->
                "سيتم الرجوع من ${pendingUpdate.currentVersion ?: "الإصدار الحالي"} إلى ${pendingUpdate.version}. سيعاد فحص التوافق قبل البدء. لا تفصل الكهرباء عن الراوتر."
            relation == FirmwareRelation.UPGRADE ->
                "سيتم التحديث من ${pendingUpdate.currentVersion ?: "الإصدار الحالي"} إلى ${pendingUpdate.version}. سيعاد فحص التوافق قبل البدء. لا تفصل الكهرباء عن الراوتر."
            else ->
                "سيتم الانتقال إلى ${pendingUpdate.version} بعد إعادة فحص التوافق. لا تفصل الكهرباء عن الراوتر."
        }
        AlertDialog(
            onDismissRequest = { if (!installing) confirmInstall = false },
            title = {
                Text(
                    when {
                        selectedSource == FirmwareUiSource.LOCAL_FILE -> "رفع وتثبيت الملف؟"
                        relation == FirmwareRelation.DOWNGRADE -> "تنفيذ الداون قريد؟"
                        else -> "تنفيذ التحديث؟"
                    }
                )
            },
            text = { Text(body) },
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

    HaiPage(title = "تحديث نظام الراوتر", subtitle = "Huawei / ZTE") {
        when {
            loading -> HaiCard { CircularProgressIndicator() }
            inspection == null -> HaiCard {
                HaiSectionTitle("الراوتر غير متصل")
                Button(onClick = { scope.launch { loadRouter() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("إعادة الفحص")
                }
            }
            else -> {
                val current = inspection!!
                val currentVersion = current.device?.firmwareVersion

                CurrentFirmwareCard(current)

                HaiCard {
                    HaiSectionTitle("مصدر التحديث")
                    HaiTwoPane(
                        first = {
                            SourceButton(
                                title = "رسمي",
                                selected = selectedSource == FirmwareUiSource.OFFICIAL,
                                enabled = !searching && !installing,
                                onClick = { chooseSource(FirmwareUiSource.OFFICIAL) }
                            )
                        },
                        second = {
                            SourceButton(
                                title = "الشركات",
                                selected = selectedSource == FirmwareUiSource.COMPANIES,
                                enabled = !searching && !installing,
                                onClick = { chooseSource(FirmwareUiSource.COMPANIES) }
                            )
                        }
                    )
                    SourceButton(
                        title = "ملف من الجوال",
                        selected = selectedSource == FirmwareUiSource.LOCAL_FILE,
                        enabled = !searching && !installing,
                        onClick = {
                            chooseSource(FirmwareUiSource.LOCAL_FILE)
                            filePicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-binary", "*/*"))
                        }
                    )
                    Button(
                        onClick = {
                            if (selectedSource == FirmwareUiSource.LOCAL_FILE) {
                                filePicker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-binary", "*/*"))
                            } else {
                                searchUpdates()
                            }
                        },
                        enabled = !searching && !installing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text(
                            when (selectedSource) {
                                FirmwareUiSource.OFFICIAL -> "بحث عن تحديث رسمي"
                                FirmwareUiSource.COMPANIES -> "بحث في تحديثات الشركات"
                                FirmwareUiSource.LOCAL_FILE -> "اختيار ملف Firmware من الجوال"
                            }
                        )
                    }
                }

                if (searching) {
                    ProgressCard(
                        title = if (selectedSource == FirmwareUiSource.LOCAL_FILE) "فحص الملف" else "البحث",
                        progress = searchProgress,
                        stage = searchStage
                    )
                }

                localSelection?.let { selection ->
                    HaiCard {
                        HaiSectionTitle("الملف المختار")
                        Text(selection.fileName, fontWeight = FontWeight.SemiBold, maxLines = 3)
                        HaiValueRow("الحجم", selection.candidate.size)
                        HaiValueRow("SHA-256", selection.sha256.take(12) + "…" + selection.sha256.takeLast(8))
                        HaiStatusChip(if (selection.catalogVerified) "مطابق لقاعدة HAI" else "فحص محلي", active = selection.catalogVerified)
                        HaiStatusChip(
                            if (selection.uploadTarget != null) "Local Upgrade متاح" else "Local Upgrade غير معلن",
                            active = selection.uploadTarget != null
                        )
                    }
                }

                candidate?.let { update ->
                    UpdateCandidateCard(update)
                    val relation = firmwareRelation(update.currentVersion, update.version)
                    Button(
                        onClick = { confirmInstall = true },
                        enabled = update.installable && !searching && !installing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    ) {
                        Text(
                            when {
                                !update.installable -> "التثبيت غير متاح"
                                selectedSource == FirmwareUiSource.LOCAL_FILE -> "رفع وتثبيت الملف"
                                relation == FirmwareRelation.DOWNGRADE -> "تثبيت الداون قريد"
                                else -> "تثبيت الإصدار"
                            }
                        )
                    }
                }

                if (findings.isNotEmpty()) {
                    HaiSectionTitle("إصدارات أخرى وجدها التطبيق")
                    findings.forEach { finding -> FirmwareFindingCard(currentVersion, finding) }
                }

                if (installing) {
                    ProgressCard(
                        title = if (selectedSource == FirmwareUiSource.LOCAL_FILE) "رفع وتحديث الراوتر" else "تنفيذ النظام",
                        progress = installProgress,
                        stage = installStage
                    )
                }

                if (candidate == null && findings.isEmpty()) {
                    message?.let {
                        HaiCard {
                            HaiSectionTitle("النتيجة")
                            Text(it)
                        }
                    }
                } else if (!installing) {
                    message?.takeIf { it.contains("فشل") || it.contains("تعذر") || selectedSource == FirmwareUiSource.LOCAL_FILE }?.let {
                        HaiCard { Text(it) }
                    }
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
private fun CurrentFirmwareCard(inspection: RouterInspection) {
    HaiCard {
        Text("النظام الحالي", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(
            inspection.device?.firmwareVersion ?: "غير معروف",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 3
        )
        HaiValueRow("الراوتر", inspection.device?.model ?: inspection.snapshot.brand.displayName)
        HaiValueRow("Hardware", inspection.device?.hardwareVersion)
    }
}

@Composable
private fun SourceButton(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(title) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(title) }
    }
}

@Composable
private fun UpdateCandidateCard(update: FirmwareCandidate) {
    val relation = firmwareRelation(update.currentVersion, update.version)
    HaiCard {
        Text("الإصدار المقترح", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(update.version, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3)
        HaiStatusChip(relation.label, active = relation != FirmwareRelation.SAME)
        HaiValueRow("المصدر", update.sourceLabel)
        HaiValueRow("الحجم", update.size)
        HaiStatusChip(
            when {
                update.installMode == "local_upload" && update.installable -> "ملف جاهز للرفع"
                update.installMode == "local_upload" -> "الملف غير جاهز للتثبيت"
                relation == FirmwareRelation.DOWNGRADE && update.installable -> "داون قريد مسموح"
                relation == FirmwareRelation.DOWNGRADE -> "داون قريد غير موثق للتثبيت"
                update.installable -> "جاهز للتثبيت"
                else -> "للمراجعة فقط"
            },
            active = update.installable
        )
        Text(update.summaryArabic, fontSize = 14.sp)
    }
}

@Composable
private fun FirmwareFindingCard(currentVersion: String?, finding: FirmwareFinding) {
    val relation = firmwareRelation(currentVersion, finding.version)
    HaiCard {
        Text(finding.version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3)
        HaiStatusChip(relation.label, active = relation != FirmwareRelation.SAME)
        HaiValueRow("المصدر", finding.sourceLabel)
        HaiValueRow("الثقة", finding.trustLabel)
        HaiValueRow("الحالة", finding.statusLabel)
        Text(finding.summaryArabic, fontSize = 14.sp)
    }
}

@Composable
private fun ProgressCard(title: String, progress: Int, stage: String) {
    HaiCard {
        HaiSectionTitle(title)
        Text("$progress%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        if (stage.isNotBlank()) Text(stage, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun firmwareRelation(current: String?, target: String): FirmwareRelation {
    if (current.isNullOrBlank() || target.isBlank() || target == "ملف محلي") return FirmwareRelation.DIFFERENT
    if (current.equals(target, ignoreCase = true)) return FirmwareRelation.SAME

    val currentBuild = buildNumber(current)
    val targetBuild = buildNumber(target)
    if (currentBuild != null && targetBuild != null) {
        return when {
            targetBuild > currentBuild -> FirmwareRelation.UPGRADE
            targetBuild < currentBuild -> FirmwareRelation.DOWNGRADE
            else -> FirmwareRelation.DIFFERENT
        }
    }

    val currentDotted = dottedVersion(current)
    val targetDotted = dottedVersion(target)
    if (currentDotted != null && targetDotted != null) {
        val comparison = compareVersionParts(currentDotted, targetDotted)
        return when {
            comparison < 0 -> FirmwareRelation.UPGRADE
            comparison > 0 -> FirmwareRelation.DOWNGRADE
            else -> FirmwareRelation.DIFFERENT
        }
    }
    return FirmwareRelation.DIFFERENT
}

private fun buildNumber(value: String): Int? = Regex("(?i)B(\\d+)")
    .findAll(value)
    .lastOrNull()
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()

private fun dottedVersion(value: String): List<Int>? {
    val match = Regex("(\\d+(?:\\.\\d+){1,})").find(value) ?: return null
    return match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
}

private fun compareVersionParts(first: List<Int>, second: List<Int>): Int {
    val size = maxOf(first.size, second.size)
    for (index in 0 until size) {
        val a = first.getOrElse(index) { 0 }
        val b = second.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}
