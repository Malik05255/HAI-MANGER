package com.hai.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material3.Scaffold
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
import com.hai.manager.router.RouterDiscoveryService
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
        primary = Color(0xFF202124),
        onPrimary = Color.White,
        background = Color(0xFFF7F7F5),
        surface = Color.White,
        onSurface = Color(0xFF202124),
        outlineVariant = Color(0xFFE7E7E2)
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
                    AppTab.DEVICES -> DevicesScreen(Modifier.padding(padding))
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
            "إدارة الراوتر بدون تعقيد",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun HomeScreen(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val discovery = remember { RouterDiscoveryService(context.applicationContext) }
    var scanning by remember { mutableStateOf(false) }
    var router by remember { mutableStateOf<RouterSnapshot?>(null) }

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
                    Column {
                        Text(router?.brand?.displayName ?: "الراوتر", style = MaterialTheme.typography.titleLarge)
                        Text(
                            router?.model ?: router?.message ?: "اتصل بشبكة الراوتر ثم ابدأ الفحص",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        )
                    }
                    Icon(Icons.Outlined.Router, null, modifier = Modifier.size(38.dp))
                }

                if (router?.connected == true) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DetailRow("عنوان الإدارة", router?.gateway ?: "—")
                    DetailRow("درجة التعرف", if ((router?.confidence ?: 0) > 0) "${router?.confidence}%" else "قيد التعرف")
                }

                Button(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !scanning,
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        scope.launch {
                            scanning = true
                            router = discovery.discover()
                            scanning = false
                        }
                    }
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("فحص الراوتر")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("بعد التعرف على الجهاز", style = MaterialTheme.typography.titleMedium)
                Text("يعرض HAI MANAGER فقط الأدوات المتوافقة مع الموديل وإصدار النظام، لتجنب تنفيذ عملية غير مناسبة على الراوتر.")
            }
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
private fun DevicesScreen(modifier: Modifier = Modifier) {
    val brands = listOf(
        "ZTE" to "اكتشاف وبصمة أولية",
        "Huawei" to "اكتشاف وبصمة أولية",
        "Nokia" to "اكتشاف أولي",
        "Netgear" to "اكتشاف أولي",
        "TP-Link" to "اكتشاف أولي",
        "Zyxel / D-Link" to "قيد إضافة التعريفات"
    )
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppHeader()
        Text("قاعدة الأجهزة", style = MaterialTheme.typography.titleLarge)
        brands.forEach { (name, status) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(status, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
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
