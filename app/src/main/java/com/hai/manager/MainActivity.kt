package com.hai.manager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.hai.manager.router.RouterCapability
import com.hai.manager.router.RouterDiscoveryService
import com.hai.manager.router.RouterInspection
import com.hai.manager.router.RouterInspectorService
import com.hai.manager.router.RouterSnapshot
import com.hai.manager.update.ApkUpdateInstaller
import com.hai.manager.update.AppUpdate
import com.hai.manager.update.UpdateNotifications
import com.hai.manager.update.UpdateRepository
import com.hai.manager.update.UpdateScheduler
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateNotifications.createChannel(this)
        UpdateScheduler.schedule(this)
        requestLocalNetworkPermission()
        requestNotificationPermission()
        setContent { HaiManagerApp() }
    }

    private fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 201)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }
}

private enum class AppTab(val title: String) {
    HOME("الرئيسية"),
    DEVICES("الأجهزة"),
    UPDATES("التحديثات")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaiManagerApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(AppTab.HOME) }
    val colors = lightColorScheme(
        primary = Color(0xFF1F2933),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9EEF2),
        onPrimaryContainer = Color(0xFF1F2933),
        background = Color(0xFFF6F7F5),
        surface = Color.White,
        onSurface = Color(0xFF202428),
        outlineVariant = Color(0xFFE5E8E5)
    )

    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(
                bottomBar = {
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
                }
            ) { padding ->
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("HAI MANAGER", fontSize = 25.sp, style = MaterialTheme.typography.titleLarge)
        Text(
            "تعرف على الراوتر، اقرأ حالته، ثم اعرض الأدوات المتوافقة فقط",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun HomeScreen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val discovery = remember { RouterDiscoveryService(context.applicationContext) }
    val inspector = remember { RouterInspectorService() }
    var scanning by remember { mutableStateOf(false) }
    var router by remember { mutableStateOf<RouterSnapshot?>(null) }
    var inspection by remember { mutableStateOf<RouterInspection?>(null) }

    fun scan() {
        scope.launch {
            scanning = true
            inspection = null
            val result = discovery.discover()
            router = result
            inspection = if (result.connected && result.managementUrl != null) {
                inspector.inspect(result)
            } else {
                null
            }
            scanning = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader()
        Spacer(Modifier.height(2.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            inspection?.device?.model ?: router?.model ?: router?.brand?.displayName ?: "الراوتر",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            inspection?.message ?: router?.message ?: "اتصل بشبكة الراوتر ثم ابدأ الفحص",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        )
                    }
                    Icon(Icons.Outlined.Router, null, modifier = Modifier.size(40.dp))
                }

                router?.let { snapshot ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DetailRow("الحالة", if (snapshot.connected) "متصل بالشبكة" else "غير متصل")
                    snapshot.gateway?.let { DetailRow("Gateway", it) }
                    if (snapshot.brand.displayName.isNotBlank()) DetailRow("الشركة", snapshot.brand.displayName)
                    if (snapshot.confidence > 0) DetailRow("دقة التعرف", "${snapshot.confidence}%")
                }

                inspection?.let { StatusBadge(it.accessStatus) }

                Button(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !scanning,
                    shape = RoundedCornerShape(16.dp),
                    onClick = { scan() }
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(if (router == null) "فحص الراوتر" else "إعادة الفحص")
                    }
                }

                router?.managementUrl?.let { url ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = { openRouterPanel(context, url) }
                    ) {
                        Text("فتح لوحة الراوتر")
                    }
                }
            }
        }

        inspection?.device?.let { device ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("معلومات الجهاز", style = MaterialTheme.typography.titleMedium)
                    OptionalDetailRow("الموديل", device.model)
                    OptionalDetailRow("IMEI", device.imei)
                    OptionalDetailRow("الرقم التسلسلي", device.serialNumber)
                    OptionalDetailRow("Firmware", device.firmwareVersion)
                    OptionalDetailRow("Hardware", device.hardwareVersion)
                    OptionalDetailRow("WebUI", device.webUiVersion)
                    OptionalDetailRow("WAN IP", device.wanIp)
                    if (listOf(
                            device.model,
                            device.imei,
                            device.serialNumber,
                            device.firmwareVersion,
                            device.hardwareVersion,
                            device.webUiVersion,
                            device.wanIp
                        ).all { it.isNullOrBlank() }
                    ) {
                        Text(
                            "لم تُتح واجهة الإدارة معلومات إضافية بدون تسجيل الدخول.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        )
                    }
                }
            }
        }

        inspection?.signal?.takeIf { it.hasData }?.let { signal ->
            SignalCard(signal)
        }

        inspection?.capabilities?.takeIf { it.isNotEmpty() }?.let { capabilities ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("المتاح لهذا الراوتر", style = MaterialTheme.typography.titleMedium)
                    Text(
                        capabilities.joinToString(" • ") { capability -> capability.displayName },
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("طريقة العمل", style = MaterialTheme.typography.titleMedium)
                Text(
                    "النسخة الحالية تنفذ قراءة فقط. أوامر التغيير مثل إعادة التشغيل أو إعدادات الشبكة لن تظهر إلا بعد إضافة Adapter موثوق للموديل وإصدار WebUI المحدد.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: RouterAccessStatus) {
    val label = when (status) {
        RouterAccessStatus.AVAILABLE -> "قراءة مباشرة متاحة"
        RouterAccessStatus.AUTH_REQUIRED -> "تسجيل الدخول مطلوب"
        RouterAccessStatus.UNSUPPORTED -> "التعرف متاح — الإدارة قيد الإضافة"
        RouterAccessStatus.FAILED -> "لم تُقرأ التفاصيل"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun SignalCard(signal: CellularSignal) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("حالة الشبكة", style = MaterialTheme.typography.titleMedium)
            OptionalDetailRow("نوع الشبكة", signal.networkType)
            OptionalDetailRow("المشغل", signal.operatorName)
            OptionalDetailRow("RSRP", signal.rsrp)
            OptionalDetailRow("RSRQ", signal.rsrq)
            OptionalDetailRow("SINR", signal.sinr)
            OptionalDetailRow("RSSI", signal.rssi)
            if (signal.bands.isNotEmpty()) DetailRow("النطاقات", signal.bands.joinToString(" + "))
            OptionalDetailRow("Cell ID", signal.cellId)
            OptionalDetailRow("PCI", signal.pci)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        Text(value)
    }
}

@Composable
private fun OptionalDetailRow(label: String, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let { DetailRow(label, it) }
}

private fun openRouterPanel(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
private fun DevicesScreen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val repository = remember { DeviceCatalogRepository(context.applicationContext) }
    var status by remember { mutableStateOf(repository.status()) }
    var syncing by remember { mutableStateOf(false) }

    fun syncCatalog() {
        scope.launch {
            syncing = true
            status = repository.sync() ?: repository.status()
            syncing = false
        }
    }

    LaunchedEffect(Unit) {
        if (status.version == 0) syncCatalog()
    }

    val brands = listOf(
        "ZTE" to "قراءة معلومات الجهاز والإشارة عبر goform عند دعم WebUI",
        "Huawei" to "قراءة معلومات الجهاز والإشارة عبر HiLink عند دعم WebUI",
        "Nokia" to "اكتشاف وبصمة — Adapter الإدارة قيد الإضافة",
        "Netgear" to "اكتشاف وبصمة — Adapter الإدارة قيد الإضافة",
        "TP-Link" to "اكتشاف وبصمة — Adapter الإدارة قيد الإضافة",
        "Zyxel / D-Link" to "تعريفات قاعدة الأجهزة متاحة، والإدارة قيد الإضافة"
    )

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppHeader()
        Text("قاعدة الأجهزة", style = MaterialTheme.typography.titleLarge)

        CatalogCard(status, syncing, onSync = { syncCatalog() })

        brands.forEach { (name, itemStatus) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(itemStatus, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(status: CatalogStatus, syncing: Boolean, onSync: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("قاعدة التعريفات الحية", style = MaterialTheme.typography.titleMedium)
            DetailRow("الإصدار", if (status.version > 0) status.version.toString() else "—")
            DetailRow("عدد الأجهزة", if (status.deviceCount > 0) status.deviceCount.toString() else "—")
            DetailRow("آخر تحديث", status.updatedAt.ifBlank { "—" })
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !syncing,
                onClick = onSync
            ) {
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("تحديث قاعدة الأجهزة")
                }
            }
        }
    }
}

@Composable
private fun UpdatesScreen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }

    fun checkNow() {
        scope.launch {
            checking = true
            update = UpdateRepository().check()
            checked = true
            checking = false
        }
    }

    LaunchedEffect(Unit) { checkNow() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppHeader()
        Text("التحديثات", style = MaterialTheme.typography.titleLarge)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("HAI MANAGER ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                when {
                    checking -> Text("جارٍ البحث عن تحديث جديد…")
                    update?.available == true -> {
                        Text("يتوفر الإصدار ${update?.versionName}")
                        if (!update?.notes.isNullOrBlank()) Text(update?.notes.orEmpty())
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { update?.let { ApkUpdateInstaller.downloadAndInstall(context, it) } }
                        ) { Text("تنزيل وتثبيت التحديث") }
                    }
                    checked -> Text("أنت تستخدم أحدث إصدار متوفر.")
                    else -> Text("لم يتم التحقق بعد.")
                }
                Button(modifier = Modifier.fillMaxWidth(), enabled = !checking, onClick = { checkNow() }) {
                    Text("البحث عن تحديثات الآن")
                }
            }
        }

        Text(
            "يفحص التطبيق وجود إصدار جديد دوريًا. التثبيت فوق النسخة الحالية يتطلب أن تكون الحزمة موقعة بنفس مفتاح التوقيع، ويطلب Android تأكيد المستخدم قبل التثبيت.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
    }
}
