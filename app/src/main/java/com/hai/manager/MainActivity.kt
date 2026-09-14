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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hai.manager.router.NetworkMode
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

private enum class AppTab(val title: String) {
    HOME("الرئيسية"), ROUTER("الراوتر"), UPDATES("التحديثات")
}

@Composable
fun HaiManagerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { RouterDiscoveryService(context.applicationContext) }
    val inspector = remember { RouterInspectorService() }
    val probe = remember { RouterCapabilityProbeService() }
    val actions = remember { RouterActionService() }

    var tab by remember { mutableStateOf(AppTab.HOME) }
    var scanning by remember { mutableStateOf(false) }
    var router by remember { mutableStateOf<RouterSnapshot?>(null) }
    var inspection by remember { mutableStateOf<RouterInspection?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var confirmReboot by remember { mutableStateOf(false) }

    fun scan() {
        scope.launch {
            scanning = true
            actionMessage = null
            val found = discovery.discover()
            router = found
            val inspected = if (found.connected && found.managementUrl != null) inspector.inspect(found) else null
            inspection = inspected?.let { probe.enrich(it) }
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
                inspection = inspection?.let { current -> probe.enrich(inspector.inspect(current.snapshot)) }
            }
        }
    }

    LaunchedEffect(Unit) { scan() }

    HaiTheme {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
            loginUrl?.let { url ->
                InAppRouterPanel(
                    url = url,
                    onClose = {
                        loginUrl = null
                        scan()
                    }
                )
                return@CompositionLocalProvider
            }

            if (confirmReboot) {
                AlertDialog(
                    onDismissRequest = { confirmReboot = false },
                    title = { Text("إعادة تشغيل الراوتر؟") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmReboot = false
                            inspection?.let { current -> runAction { actions.reboot(current) } }
                        }) { Text("إعادة التشغيل") }
                    },
                    dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("إلغاء") } }
                )
            }

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppTab.entries.forEach { item ->
                            val icon = when (item) {
                                AppTab.HOME -> Icons.Outlined.Home
                                AppTab.ROUTER -> Icons.Outlined.Router
                                AppTab.UPDATES -> Icons.Outlined.SystemUpdate
                            }
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(icon, contentDescription = null) },
                                label = { Text(item.title) }
                            )
                        }
                    }
                }
            ) { padding ->
                when (tab) {
                    AppTab.HOME -> HomeScreen(
                        context = context,
                        router = router,
                        inspection = inspection,
                        scanning = scanning,
                        modifier = Modifier.padding(padding),
                        onScan = ::scan,
                        onLogin = { loginUrl = router?.managementUrl },
                        onRouter = { tab = AppTab.ROUTER }
                    )
                    AppTab.ROUTER -> RouterScreen(
                        inspection = inspection,
                        router = router,
                        scanning = scanning,
                        actionBusy = actionBusy,
                        actionMessage = actionMessage,
                        modifier = Modifier.padding(padding),
                        onScan = ::scan,
                        onLogin = { loginUrl = router?.managementUrl },
                        onAction = ::runAction,
                        actions = actions,
                        onReboot = { confirmReboot = true }
                    )
                    AppTab.UPDATES -> UpdatesScreen(context, Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    context: Context,
    router: RouterSnapshot?,
    inspection: RouterInspection?,
    scanning: Boolean,
    modifier: Modifier,
    onScan: () -> Unit,
    onLogin: () -> Unit,
    onRouter: () -> Unit
) {
    val connected = router?.connected == true
    val needsLogin = inspection?.accessStatus == RouterAccessStatus.AUTH_REQUIRED
    val url = router?.managementUrl

    HaiPage(
        modifier = modifier,
        title = "HAI MANAGER",
        subtitle = "Huawei + ZTE"
    ) {
        HaiCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        inspection?.device?.model ?: router?.model ?: "الراوتر",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        when {
                            scanning -> "جارٍ الفحص…"
                            !connected -> "غير متصل"
                            needsLogin -> "يحتاج تسجيل دخول"
                            inspection?.accessStatus == RouterAccessStatus.AVAILABLE -> "متصل"
                            else -> router?.brand?.displayName ?: "جاهز للفحص"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HaiStatusChip(
                    text = when {
                        scanning -> "فحص"
                        inspection?.accessStatus == RouterAccessStatus.AVAILABLE -> "جاهز"
                        needsLogin -> "دخول"
                        connected -> "متصل"
                        else -> "—"
                    },
                    active = inspection?.accessStatus == RouterAccessStatus.AVAILABLE
                )
            }

            inspection?.signal?.let { signal ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MiniMetric("الشبكة", signal.networkType ?: "—")
                    MiniMetric("RSRP", signal.rsrp ?: "—")
                    MiniMetric("SINR", signal.sinr ?: "—")
                }
            }

            when {
                scanning -> CircularProgressIndicator()
                needsLogin && url != null -> Button(
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                ) { Text("تسجيل الدخول") }
                else -> OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                ) { Text(if (connected) "تحديث الحالة" else "فحص الراوتر") }
            }
        }

        if (connected && url != null) {
            HaiActionGrid(
                listOf(
                    HaiAction("Wi-Fi", Icons.Outlined.Wifi) {
                        context.startActivity(Intent(context, WifiToolsActivity::class.java).putExtra(WifiToolsActivity.EXTRA_URL, url))
                    },
                    HaiAction("SIM", Icons.Outlined.SimCard) {
                        context.startActivity(Intent(context, SimToolsActivity::class.java).putExtra(SimToolsActivity.EXTRA_URL, url))
                    },
                    HaiAction("الشبكة", Icons.Outlined.Router, onClick = onRouter),
                    HaiAction("قفل المشغل", Icons.Outlined.Lock, onClick = onRouter)
                )
            )
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RouterScreen(
    inspection: RouterInspection?,
    router: RouterSnapshot?,
    scanning: Boolean,
    actionBusy: Boolean,
    actionMessage: String?,
    modifier: Modifier,
    onScan: () -> Unit,
    onLogin: () -> Unit,
    onAction: (suspend () -> RouterActionResult) -> Unit,
    actions: RouterActionService,
    onReboot: () -> Unit
) {
    var nrBands by remember { mutableStateOf("78") }
    val current = inspection

    HaiPage(
        modifier = modifier,
        title = current?.device?.model ?: router?.model ?: "الراوتر",
        subtitle = router?.brand?.displayName?.takeIf { it != RouterBrand.UNKNOWN.displayName }
    ) {
        if (scanning) {
            HaiCard { CircularProgressIndicator() }
            return@HaiPage
        }

        if (router?.connected != true) {
            HaiCard {
                Text("لا يوجد راوتر متصل")
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("فحص الراوتر") }
            }
            return@HaiPage
        }

        if (current?.accessStatus == RouterAccessStatus.AUTH_REQUIRED) {
            HaiCard {
                Text("تسجيل الدخول مطلوب")
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("تسجيل الدخول") }
            }
            return@HaiPage
        }

        if (current == null || current.accessStatus != RouterAccessStatus.AVAILABLE) {
            HaiCard {
                Text("تعذر قراءة بيانات الراوتر")
                OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("إعادة الفحص") }
            }
            return@HaiPage
        }

        HaiTwoPane(
            first = {
                HaiCard {
                    HaiSectionTitle("الشبكة")
                    val signal = current.signal
                    HaiValueRow("المشغل", signal?.operatorName)
                    HaiValueRow("النوع", signal?.networkType)
                    HaiValueRow("RSRP", signal?.rsrp)
                    HaiValueRow("RSRQ", signal?.rsrq)
                    HaiValueRow("SINR", signal?.sinr)
                    HaiValueRow("النطاق", signal?.primaryBand ?: signal?.bands?.joinToString(" + "))
                    if (signal?.secondaryBands?.isNotEmpty() == true) {
                        HaiValueRow("CA", signal.secondaryBands.joinToString(" + "))
                    }
                }
            },
            second = {
                HaiCard {
                    HaiSectionTitle("الجهاز")
                    HaiValueRow("Firmware", current.device?.firmwareVersion)
                    HaiValueRow("IMEI", current.device?.imei)
                    HaiValueRow("Serial", current.device?.serialNumber)
                    HaiValueRow("Hardware", current.device?.hardwareVersion)
                }
            }
        )

        RouterCarrierLockCard(current)

        val caps = current.capabilities
        if (RouterCapability.NETWORK_MODE in caps && current.supportedNetworkModes.isNotEmpty()) {
            HaiCard {
                HaiSectionTitle("وضع الشبكة")
                HaiActionGrid(
                    current.supportedNetworkModes.sortedBy(NetworkMode::ordinal).map { mode ->
                        HaiAction(mode.displayName, Icons.Outlined.Router, enabled = !actionBusy) {
                            onAction { actions.setNetworkMode(current, mode) }
                        }
                    }
                )
            }
        }

        val bandWritable = current.snapshot.brand == RouterBrand.ZTE && current.firmwareProfileInfo.bandLock.canWrite
        if (bandWritable) {
            HaiCard {
                HaiSectionTitle("قفل 5G")
                OutlinedTextField(
                    value = nrBands,
                    onValueChange = { nrBands = it.filter { ch -> ch.isDigit() || ch == ',' || ch == ' ' || ch == '+' } },
                    label = { Text("n78 أو 41,78") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val bands = nrBands.split(',', '+', ' ').mapNotNull { it.trim().toIntOrNull() }
                        onAction { actions.setZteNrBands(current, bands) }
                    },
                    enabled = !actionBusy && nrBands.any(Char::isDigit),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("تطبيق") }
            }
        }

        if (RouterCapability.REBOOT in caps) {
            OutlinedButton(
                onClick = onReboot,
                enabled = !actionBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                Text("  إعادة تشغيل الراوتر")
            }
        }

        if (actionBusy) CircularProgressIndicator()
        actionMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Text("  تحديث")
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

    HaiPage(modifier = modifier, title = "التحديثات") {
        HaiCard {
            HaiValueRow("الإصدار", BuildConfig.VERSION_NAME)
            when {
                checking -> CircularProgressIndicator()
                result is UpdateCheckResult.Failure -> Text("تعذر التحقق من التحديث")
                result is UpdateCheckResult.Success -> {
                    val update = (result as UpdateCheckResult.Success).update
                    if (update.available) UpdateAvailableCard(context, update)
                    else HaiStatusChip("أحدث إصدار")
                }
            }
            OutlinedButton(onClick = ::checkNow, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                Text("فحص التحديث")
            }
        }
    }
}

@Composable
private fun UpdateAvailableCard(context: Context, update: AppUpdate) {
    Text("الإصدار ${update.versionName} متوفر", style = MaterialTheme.typography.titleMedium)
    if (update.downloadable) {
        Button(
            onClick = { ApkUpdateInstaller.downloadAndInstall(context, update) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("تنزيل وتثبيت") }
    }
}
