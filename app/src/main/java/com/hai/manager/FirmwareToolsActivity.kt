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
    var findings by remember { mutableStateOf<List<FirmwareFinding>>(emptyList()) }
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
        findings = emptyList()
        message = null
        searchProgress = 0
        searchStage = ""
    }

    fun searchUpdates() {
        val current = inspection ?: return
        scope.launch {
            searching = true
            candidate = null
            findings = emptyList()
            message = null
            searchProgress = 0
            searchStage = "بدء البحث"

            val catalogJson = if (selectedSource == FirmwareSearchSource.COMPANIES) {
                searchStage = "تحديث قاعدة المصادر"
                searchProgress = 5
                catalog.sync()
                catalog.firmwareJson()
            } else {
                catalog.firmwareJson()
            }

            val result = firmware.search(current, selectedSource, catalogJson) { progress, stage ->
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
            val result = firmware.execute(current, update) { progress, stage ->
                installProgress = maxOf(installProgress, progress.coerceIn(0, 100))
                installStage = stage
            }
            message = result.message
            installing = false
        }
    }

    LaunchedEffect(Unit) { loadRouter() }

    val pendingUpdate = candidate
    if (confirmInstall && pendingUpdate != null) {
        val relation = firmwareRelation(pendingUpdate.currentVersion, pendingUpdate.version)
        val body = when (relation) {
            FirmwareRelation.DOWNGRADE -> "سيتم الرجوع من ${pendingUpdate.currentVersion ?: "الإصدار الحالي"} إلى ${pendingUpdate.version}. هذا الداون قريد ظاهر كمسار قابل للتنفيذ، وسيعاد فحص التوافق قبل البدء. لا تفصل الكهرباء عن الراوتر."
            FirmwareRelation.UPGRADE -> "سيتم التحديث من ${pendingUpdate.currentVersion ?: "الإصدار الحالي"} إلى ${pendingUpdate.version}. سيعاد فحص التوافق قبل البدء. لا تفصل الكهرباء عن الراوتر."
            else -> "سيتم الانتقال إلى ${pendingUpdate.version} بعد إعادة فحص التوافق. لا تفصل الكهرباء عن الراوتر."
        }
        AlertDialog(
            onDismissRequest = { if (!installing) confirmInstall = false },
            title = { Text(if (relation == FirmwareRelation.DOWNGRADE) "تنفيذ الداون قريد؟" else "تنفيذ التحديث؟") },
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
                    HaiSectionTitle("مصدر البحث")
                    HaiTwoPane(
                        first = {
                            SourceButton(
                                title = "رسمي",
                                selected = selectedSource == FirmwareSearchSource.OFFICIAL,
                                enabled = !searching && !installing,
                                onClick = { chooseSource(FirmwareSearchSource.OFFICIAL) }
                            )
                        },
                        second = {
                            SourceButton(
                                title = "الشركات",
                                selected = selectedSource == FirmwareSearchSource.COMPANIES,
                                enabled = !searching && !installing,
                                onClick = { chooseSource(FirmwareSearchSource.COMPANIES) }
                            )
                        }
                    )
                    Button(
                        onClick = ::searchUpdates,
                        enabled = !searching && !installing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        Text(if (selectedSource == FirmwareSearchSource.OFFICIAL) "بحث عن تحديث رسمي" else "بحث في تحديثات الشركات")
                    }
                }

                if (searching) {
                    ProgressCard(
                        title = "البحث",
                        progress = searchProgress,
                        stage = searchStage
                    )
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
                                relation == FirmwareRelation.DOWNGRADE -> "تثبيت الداون قريد"
                                else -> "تثبيت الإصدار"
                            }
                        )
                    }
                }

                if (findings.isNotEmpty()) {
                    HaiSectionTitle("إصدارات أخرى وجدها التطبيق")
                    findings.forEach { finding ->
                        FirmwareFindingCard(currentVersion, finding)
                    }
                }

                if (installing) {
                    ProgressCard(
                        title = "تنفيذ النظام",
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
                } else if (installing.not()) {
                    message?.takeIf { it.contains("فشل") || it.contains("تعذر") }?.let {
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
    if (current.isNullOrBlank() || target.isBlank()) return FirmwareRelation.DIFFERENT
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
