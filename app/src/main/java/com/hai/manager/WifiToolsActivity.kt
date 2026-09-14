package com.hai.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
        setContent {
            HaiTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    WifiToolsScreen(url = url, onClose = { finish() })
                }
            }
        }
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

    HaiPage(title = "Wi-Fi") {
        when {
            loading -> HaiCard { CircularProgressIndicator() }
            probe == null -> HaiCard {
                Text("تعذر قراءة Wi-Fi")
                Button(onClick = { scope.launch { reload() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("إعادة المحاولة")
                }
            }
            else -> {
                val current = probe!!
                val info = current.info

                HaiCard {
                    HaiSectionTitle(info.ssid ?: "Wi-Fi")
                    HaiStatusChip(if (info.enabled == false) "متوقف" else "مفعّل", active = info.enabled != false)
                    HaiValueRow("الحماية", info.securityMode)
                    HaiValueRow("القناة", info.channel)
                }

                if (info.canToggle) {
                    Button(
                        onClick = { runAction { service.setEnabled(url, current, info.enabled != true) } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (info.enabled == true) "إيقاف Wi-Fi" else "تشغيل Wi-Fi") }
                }

                if (info.canRename) {
                    HaiCard {
                        HaiSectionTitle("اسم الشبكة")
                        OutlinedTextField(
                            value = ssid,
                            onValueChange = { ssid = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { runAction { service.rename(url, current, ssid) } },
                            enabled = !busy && ssid.isNotBlank() && ssid != info.ssid,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("حفظ") }
                    }
                }

                if (busy) CircularProgressIndicator()
                message?.let { Text(it) }
            }
        }

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("رجوع") }
    }
}
