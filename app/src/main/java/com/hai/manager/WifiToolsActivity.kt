package com.hai.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hai.manager.router.RouterActionResult
import com.hai.manager.router.RouterWifiService
import kotlinx.coroutines.launch

class WifiToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        setContent { WifiToolsScreen(url = url, onClose = { finish() }) }
    }

    companion object {
        const val EXTRA_URL = "router_url"
    }
}

@Composable
private fun WifiToolsScreen(url: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val service = remember { RouterWifiService() }
    var loading by remember { mutableStateOf(true) }
    var probe by remember { mutableStateOf<RouterWifiService.WifiProbe?>(null) }
    var ssid by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        probe = service.inspect(url)
        ssid = probe?.info?.ssid.orEmpty()
        loading = false
    }

    fun runAction(block: suspend () -> RouterActionResult) {
        scope.launch {
            busy = true
            val result = block()
            message = result.message
            busy = false
            if (result.success) reload()
        }
    }

    LaunchedEffect(url) { reload() }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1F2933),
            onPrimary = Color.White,
            background = Color(0xFFF6F7F5),
            surface = Color.White,
            onSurface = Color(0xFF202428)
        )
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("إدارة Wi-Fi", style = MaterialTheme.typography.headlineMedium)
            Text("HAI MANAGER يفعّل فقط الأوامر التي تعرّف عليها بأمان في WebUI الحالي.")

            when {
                loading -> CircularProgressIndicator()
                probe == null -> {
                    Text("لم يتم التعرف على واجهة Wi-Fi المدعومة. تأكد من تسجيل الدخول ثم حاول مرة أخرى.")
                    Button(onClick = { scope.launch { reload() } }, modifier = Modifier.fillMaxWidth()) { Text("إعادة الفحص") }
                }
                else -> {
                    val current = probe!!
                    val info = current.info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(current.brand.displayName, style = MaterialTheme.typography.titleLarge)
                            info.ssid?.let { Text("اسم الشبكة: $it") }
                            info.enabled?.let { Text("الحالة: ${if (it) "مفعّل" else "متوقف"}") }
                            info.channel?.let { Text("القناة: $it") }
                            info.mode?.let { Text("الوضع: $it") }
                            info.securityMode?.let { Text("الحماية: $it") }
                        }
                    }

                    if (info.canToggle) {
                        val next = info.enabled != true
                        Button(
                            onClick = { runAction { service.setEnabled(url, current, next) } },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (next) "تشغيل Wi-Fi" else "إيقاف Wi-Fi") }
                    }

                    if (info.canRename) {
                        OutlinedTextField(
                            value = ssid,
                            onValueChange = { ssid = it },
                            label = { Text("اسم Wi-Fi الجديد") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { runAction { service.rename(url, current, ssid) } },
                            enabled = !busy && ssid.isNotBlank() && ssid != info.ssid,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("حفظ اسم Wi-Fi") }
                    }

                    if (busy) CircularProgressIndicator()
                    message?.let { Text(it) }
                    OutlinedButton(onClick = { scope.launch { reload() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("تحديث الحالة")
                    }
                }
            }

            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("رجوع") }
        }
    }
}
