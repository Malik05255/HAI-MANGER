package com.hai.manager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hai.manager.catalog.CatalogStatus
import com.hai.manager.catalog.DeviceCatalogRepository
import com.hai.manager.router.CellularSignal
import com.hai.manager.router.RouterAccessStatus
import com.hai.manager.router.RouterActionResult
import com.hai.manager.router.RouterActionService
import com.hai.manager.router.RouterBrand
import com.hai.manager.router.RouterCapability
import com.hai.manager.router.RouterCapabilityProbeService
import com.hai.manager.router.RouterDiscoveryService
import com.hai.manager.router.RouterInspection
import com.hai.manager.router.RouterInspectorService
import com.hai.manager.router.RouterSnapshot
import com.hai.manager.router.firmwareProfileInfo
import com.hai.manager.update.ApkUpdateInstaller
import com.hai.manager.update.AppUpdate
import com.hai.manager.update.UpdateCheckResult
import com.hai.manager.update.UpdateNotifications
import com.hai.manager.update.UpdateRepository
import com.hai.manager.update.UpdateScheduler
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateNotifications.createChannel(this)
        UpdateScheduler.schedule(this)
        requestPermissionsIfNeeded()
        setContent { HaiManagerApp() }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 201)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }
}

private enum class AppTab(val title: String) { HOME("الرئيسية"), DEVICES("الأجهزة"), UPDATES("التحديثات") }

@Composable
fun HaiManagerApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(AppTab.HOME) }
    val colors = lightColorScheme(
        primary = Color(0xFF1F2933), onPrimary = Color.White,
        primaryContainer = Color(0xFFE9EEF2), onPrimaryContainer = Color(0xFF1F2933),
        background = Color(0xFFF6F7F5), surface = Color.White,
        onSurface = Color(0xFF202428), outlineVariant = Color(0xFFE5E8E5)
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    AppTab.entries.forEach { item ->
                        val icon = when (item) {
                            AppTab.HOME -> Icons.Outlined.Home
                            AppTab.DEVICES -> Icons.Outlined.Router
                            AppTab.UPDATES -> Icons.Outlined.SystemUpdate
                        }
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(icon, contentDescription = item.title) },
                            label = { Text(item.title) }
                        )
                    }
                }
            }) { padding ->
                when (tab) {
                    AppTab.HOME -> HomeScreen(context, Modifier.padding(padding))
                    AppTab.DEVICES -> DevicesScreen(context, Modifier.padding(padding))
                    AppTab.UPDATES -> UpdatesScreen(context, Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(Modifier.fillMaxWidth()) {
        Text("HAI MANAGER", fontSize = 25.sp, style = MaterialTheme.typography.titleLarge)
        Text("Huawei + ZTE • إدارة الراوتر بدون تعقيد", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
    }
}

@Composable
private fun HomeScreen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val discovery = remember { RouterDiscoveryService(context.applicationContext) }
    val inspector = remember { RouterInspectorService() }
    val capabilityProbe = remember { RouterCapabilityProbeService() }
    val actions = remember { RouterActionService() }
    var scanning by remember { mutableStateOf(false) }
    var router by remember { mutableStateOf<RouterSnapshot?>(null) }
    var inspection by remember { mutableStateOf<RouterInspection?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var confirmReboot by remember { mutableStateOf(false) }
    var nrBands by remember { mutableStateOf("78") }

    fun scan() {
        scope.launch {
            scanning = true
            actionMessage = null
            val found = discovery.discover()
            router = found
            val baseInspection = if (found.connected && found.managementUrl != null) inspector.inspect(found) else null
            inspection = baseInspection?.let { capabilityProbe.enrich(it) }
            scanning = false
        }
    }

    fun runAction(block: suspend () -> RouterActionResult) {
        scope.launch {
            actionBusy = true
            val result = block()
            actionMessage = result.message
            actionBusy = false
            if (result.success) {
                inspection = inspection?.let { current -> capabilityProbe.enrich(inspector.inspect(current.snapshot)) }
            }
        }
    }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("إعادة تشغيل الراوتر؟") },
            text = { Text("سينقطع الاتصال لبضع دقائق أثناء إعادة التشغيل.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReboot = false
                    inspection?.let { current -> runAction { actions.reboot(current) } }
                }) { Text("إعادة التشغيل") }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("إلغاء") } }
        )
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(inspection?.device?.model ?: router?.model ?: "الراوتر", style = MaterialTheme.typography.titleLarge)
                        Text(inspection?.message ?: router?.message ?: "اتصل براوتر Huawei أو ZTE ثم ابدأ الفحص")
                    }
                    Icon(Icons.Outlined.Router, null, Modifier.size(40.dp))
                }
                router?.let {
                    HorizontalDivider()
                    DetailRow("الشركة", it.brand.displayName)
                    it.gateway?.let { gateway -> DetailRow("Gateway", gateway) }
                    if (it.confidence > 0) DetailRow("دقة التعرف", "${it.confidence}%")
                }
                inspection?.let { StatusBadge(it.accessStatus) }
                Button(onClick = { scan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(if (router == null) "فحص الراوتر" else "إعادة الفحص")
                }
                router?.managementUrl?.let { url ->
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, RouterLoginActivity::class.java).putExtra(RouterLoginActivity.EXTRA_URL, url)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("تسجيل الدخول إلى الراوتر") }
                    if (router?.brand == RouterBrand.ZTE || router?.brand == RouterBrand.HUAWEI) {
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(context, WifiToolsActivity::class.java).putExtra(WifiToolsActivity.EXTRA_URL, url)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("إدارة Wi-Fi") }
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(context, SimToolsActivity::class.java).putExtra(SimToolsActivity.EXTRA_URL, url)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("إدارة SIM") }
                    }
                }
            }
        }

        inspection?.device?.let { device ->
            SimpleCard("معلومات الجهاز") {
                OptionalDetailRow("الموديل", device.model)
                OptionalDetailRow("IMEI", device.imei)
                OptionalDetailRow("الرقم التسلسلي", device.serialNumber)
                OptionalDetailRow("Firmware", device.firmwareVersion)
                OptionalDetailRow("Hardware", device.hardwareVersion)
                OptionalDetailRow("WebUI", device.webUiVersion)
                OptionalDetailRow("WAN IP", device.wanIp)
            }
        }

        inspection?.let { current ->
            val profile = current.firmwareProfileInfo
            SimpleCard("Firmware Profile") {
                DetailRow("Profile", profile.profileId)
                DetailRow("التحقق", profile.verification.displayName)
                DetailRow("Band Lock", profile.bandLock.displayName)
                DetailRow("Network Lock / NCK", profile.nckEntry.displayName)
                Text(profile.notes, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }

        inspection?.probeReport?.let { report ->
            SimpleCard("Capability Probe") {
                DetailRow("Firmware Fingerprint", report.firmwareFingerprint)
                DetailRow("الملخص", report.summary)
                report.items.forEach { item ->
                    DetailRow(item.label, item.status.displayName)
                    item.detail?.let { detail ->
                        Text(detail, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 12.sp)
                    }
                }
                report.bandSelection?.let { band ->
                    HorizontalDivider()
                    DetailRow("مصدر Band", band.source)
                    OptionalDetailRow("NetworkBand raw", band.networkBandRaw)
                    OptionalDetailRow("LTEBand raw", band.lteBandMaskRaw)
                    OptionalDetailRow("NRBand raw", band.nrBandMaskRaw)
                    if (band.decodedLteBands.isNotEmpty()) {
                        DetailRow("LTE decoded", band.decodedLteBands.joinToString(" + ") { "B$it" })
                    }
                    if (band.decodedNrBands.isNotEmpty()) {
                        DetailRow("NR decoded", band.decodedNrBands.joinToString(" + ") { "n$it" })
                    }
                    if (band.advertisedLteBands.isNotEmpty()) {
                        DetailRow("LTE المعلنة", band.advertisedLteBands.joinToString(", ") { "B$it" })
                    }
                    band.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 12.sp) }
                }
                DetailRow("Network Lock قراءة", if (report.networkLockReadable) "متاحة" else "غير ظاهرة")
                DetailRow("NCK كتابة موثقة", if (report.nckEntryVerified) "نعم" else "لا")
            }
        }

        inspection?.security?.takeIf { it.hasData }?.let { security ->
            SimpleCard("SIM وقفل الشبكة") {
                OptionalDetailRow("حالة SIM", security.simState)
                OptionalDetailRow("حالة PIN", security.pinState)
                OptionalDetailRow("محاولات PIN المتبقية", security.pinAttemptsRemaining)
                OptionalDetailRow("محاولات PUK المتبقية", security.pukAttemptsRemaining)
                OptionalDetailRow("قفل الشبكة", security.networkLockState)
                OptionalDetailRow("محاولات فك الشبكة المتبقية", security.unlockAttemptsRemaining)
                OptionalDetailRow("ICCID", security.iccid)
                OptionalDetailRow("IMSI", security.imsi)
            }
        }

        inspection?.signal?.takeIf { it.hasData }?.let { SignalCard(it) }

        inspection?.let { current ->
            val caps = current.capabilities
            val profile = current.firmwareProfileInfo
            val bandWritable = current.snapshot.brand == RouterBrand.ZTE && profile.bandLock.canWrite
            if (RouterCapability.NETWORK_MODE in caps || RouterCapability.REBOOT in caps || bandWritable) {
                SimpleCard("أدوات الراوتر") {
                    if (RouterCapability.NETWORK_MODE in caps && current.supportedNetworkModes.isNotEmpty()) {
                        Text("أوضاع الشبكة", style = MaterialTheme.typography.titleSmall)
                        current.supportedNetworkModes.forEach { mode ->
                            OutlinedButton(
                                onClick = { runAction { actions.setNetworkMode(current, mode) } },
                                enabled = !actionBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(mode.displayName) }
                        }
                    }
                    if (bandWritable) {
                        Text("قفل نطاقات 5G", style = MaterialTheme.typography.titleSmall)
                        Text("حالة الدعم: ${profile.bandLock.displayName}")
                        OutlinedTextField(
                            value = nrBands,
                            onValueChange = { nrBands = it },
                            label = { Text("مثال: 78 أو 41,78") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                val bands = nrBands.split(',', '+', ' ').mapNotNull { it.trim().toIntOrNull() }
                                runAction { actions.setZteNrBands(current, bands) }
                            },
                            enabled = !actionBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("تطبيق قفل 5G") }
                        if (profile.bandLock.displayName.contains("فحص")) {
                            Text("سيجري التطبيق preflight بإعادة القيمة الحالية أولًا. إذا لم يمكن التحقق منها فلن يرسل القيمة الجديدة.")
                        }
                    }
                    if (RouterCapability.REBOOT in caps) {
                        Button(onClick = { confirmReboot = true }, enabled = !actionBusy, modifier = Modifier.fillMaxWidth()) {
                            Text("إعادة تشغيل الراوتر")
                        }
                    }
                    if (actionBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    actionMessage?.let { Text(it) }
                }
            }
        }

        SimpleCard("التوافق") {
            Text("HAI MANAGER يطابق Model + Firmware ثم يجري Capability Probe قراءة فقط. NCK غير الموثق يبقى قراءة فقط، وBand Lock التجريبي لا يبدأ إلا بعد preflight يحافظ على القيمة الحالية ويتحقق منها.")
        }
    }
}

@Composable
private fun StatusBadge(status: RouterAccessStatus) {
    val label = when (status) {
        RouterAccessStatus.AVAILABLE -> "الإدارة متاحة"
        RouterAccessStatus.AUTH_REQUIRED -> "تسجيل الدخول مطلوب"
        RouterAccessStatus.UNSUPPORTED -> "غير مدعوم في المرحلة الحالية"
        RouterAccessStatus.FAILED -> "لم تُقرأ التفاصيل"
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun SignalCard(signal: CellularSignal) {
    SimpleCard("حالة الشبكة") {
        OptionalDetailRow("نوع الشبكة", signal.networkType)
        OptionalDetailRow("الوضع المضبوط", signal.networkPreference)
        OptionalDetailRow("المشغل", signal.operatorName)
        OptionalDetailRow("RSRP", signal.rsrp)
        OptionalDetailRow("RSRQ", signal.rsrq)
        OptionalDetailRow("SINR", signal.sinr)
        OptionalDetailRow("RSSI", signal.rssi)
        OptionalDetailRow("النطاق الأساسي", signal.primaryBand)
        if (signal.secondaryBands.isNotEmpty()) DetailRow("النطاقات المجمعة", signal.secondaryBands.joinToString(" + "))
        OptionalDetailRow("نطاق 5G", signal.nrBand)
        DetailRow("Carrier Aggregation", if (signal.carrierAggregation) "نشط" else "غير ظاهر")
        OptionalDetailRow("PCI", signal.pci)
        OptionalDetailRow("EARFCN", signal.earfcn)
        OptionalDetailRow("NR-ARFCN", signal.nrarfcn)
    }
}

@Composable
private fun SimpleCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        Text(value)
    }
}

@Composable
private fun OptionalDetailRow(label: String, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let { DetailRow(label, it) }
}

@Composable
private fun DevicesScreen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val repository = remember { DeviceCatalogRepository(context.applicationContext) }
    var status by remember { mutableStateOf(repository.status()) }
    var syncing by remember { mutableStateOf(false) }
    fun sync() {
        scope.launch {
            syncing = true
            status = repository.sync() ?: repository.status()
            syncing = false
        }
    }
    LaunchedEffect(Unit) { if (status.version == 0) sync() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppHeader()
        Text("قاعدة الأجهزة", style = MaterialTheme.typography.titleLarge)
        Text("الأولوية الحالية: Firmware profiles وCapability probes دقيقة لـ Huawei وZTE قبل إضافة أي شركة أخرى.")
        CatalogCard(status, syncing, ::sync)
        SimpleCard("ZTE") {
            Text("MC801A / MC888 / MC889 / MC7010 وعائلات MF286/MF289/MF297: Profile مطابق للـFirmware، Capability Probe، تشخيص SIM/Network Lock، وBand Lock موثق أو Runtime-preflight حسب الحالة.")
        }
        SimpleCard("Huawei") {
            Text("H155/H158/H138/H122/H112 وعائلات B818/B715/B628/B535/B525: HiLink/WebUI مع Profile لكل Firmware، تشخيص LTEBand/NetworkBand وقائمة النطاقات. Band/NCK يبقيان قراءة فقط حتى توثيق الكتابة.")
        }
    }
}

@Composable
private fun CatalogCard(status: CatalogStatus, syncing: Boolean, onSync: () -> Unit) {
    SimpleCard("قاعدة التعريفات الحية") {
        DetailRow("الإصدار", if (status.version > 0) status.version.toString() else "—")
        DetailRow("عدد الأجهزة", if (status.deviceCount > 0) status.deviceCount.toString() else "—")
        DetailRow("آخر تحديث", status.updatedAt.ifBlank { "—" })
        OutlinedButton(onClick = onSync, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
            if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("تحديث قاعدة الأجهزة")
        }
    }
}

@Composable
private fun UpdatesScreen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    fun checkNow() {
        scope.launch {
            checking = true
            result = UpdateRepository().check()
            checking = false
        }
    }
    LaunchedEffect(Unit) { checkNow() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppHeader()
        Text("التحديثات", style = MaterialTheme.typography.titleLarge)
        SimpleCard("HAI MANAGER ${BuildConfig.VERSION_NAME}") {
            when {
                checking -> Text("جارٍ التحقق من آخر إصدار…")
                result is UpdateCheckResult.Failure -> {
                    Text("تعذر التحقق من التحديثات")
                    Text((result as UpdateCheckResult.Failure).message)
                }
                result is UpdateCheckResult.Success -> {
                    val update = (result as UpdateCheckResult.Success).update
                    if (update.available) UpdateAvailableCard(context, update) else Text("أنت تستخدم أحدث إصدار متوفر (${update.versionName}).")
                }
                else -> Text("لم يتم التحقق بعد")
            }
            Button(onClick = { checkNow() }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text("البحث عن تحديثات الآن") }
        }
        Text("قناة التحديث تتحقق كل 6 ساعات، وتتحقق من SHA-256 وشهادة التوقيع الدائمة قبل فتح مثبت Android.")
    }
}

@Composable
private fun UpdateAvailableCard(context: Context, update: AppUpdate) {
    Text("يتوفر الإصدار ${update.versionName}", style = MaterialTheme.typography.titleMedium)
    if (update.notes.isNotBlank()) Text(update.notes)
    Text(if (update.signatureStable) "التوقيع ثابت — يمكن التثبيت فوق النسخة الحالية" else "قناة اختبار — التوقيع الثابت غير مفعّل")
    if (update.downloadable) {
        Button(onClick = { ApkUpdateInstaller.downloadAndInstall(context, update) }, modifier = Modifier.fillMaxWidth()) { Text("تنزيل التحديث") }
    } else Text("ملف APK لم يُنشر بعد")
}
