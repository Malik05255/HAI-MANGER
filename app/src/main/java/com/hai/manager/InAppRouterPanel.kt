package com.hai.manager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hai.manager.router.NativeRouterAuthBrand
import com.hai.manager.router.RouterAuthService
import kotlinx.coroutines.launch

@Composable
fun InAppRouterPanel(
 url: String,
 onClose: () -> Unit
) {
 val scope = rememberCoroutineScope()
 val auth = remember(url) { RouterAuthService(url) }
 var brand by remember(url) { mutableStateOf(NativeRouterAuthBrand.UNKNOWN) }
 var detecting by remember(url) { mutableStateOf(true) }
 var username by remember(url) { mutableStateOf("admin") }
 var password by remember(url) { mutableStateOf("") }
 var busy by remember(url) { mutableStateOf(false) }
 var message by remember(url) { mutableStateOf<String?>(null) }

 suspend fun detect() {
 detecting = true
 brand = runCatching { auth.detectBrand() }.getOrDefault(NativeRouterAuthBrand.UNKNOWN)
 detecting = false
 }

 LaunchedEffect(url) { detect() }
 BackHandler(enabled = !busy) { onClose() }

 HaiPage(
 title = "تسجيل الدخول",
 subtitle = if (detecting) null else brand.displayName
 ) {
 HaiCard {
 if (detecting) {
 CircularProgressIndicator()
 } else {
 if (brand != NativeRouterAuthBrand.ZTE) {
 OutlinedTextField(
 value = username,
 onValueChange = { username = it },
 label = { Text("اسم المستخدم") },
 singleLine = true,
 enabled = !busy,
 modifier = Modifier.fillMaxWidth()
 )
 }

 OutlinedTextField(
 value = password,
 onValueChange = { password = it },
 label = { Text("كلمة المرور") },
 singleLine = true,
 enabled = !busy,
 visualTransformation = PasswordVisualTransformation(),
 modifier = Modifier.fillMaxWidth()
 )

 Button(
 onClick = {
 scope.launch {
 busy = true
 message = null
 val result = runCatching {
 auth.login(username.trim(), password)
 }.getOrNull()
 busy = false
 if (result == null) {
 message = "تعذر الاتصال بالراوتر"
 return@launch
 }
 brand = result.brand
 if (result.success) {
 onClose()
 } else {
 message = "فشل تسجيل الدخول: تحقق من بيانات الاعتماد"
 }
 }
 },
 enabled = !busy,
 modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
 ) {
 Text("تسجيل الدخول")
 }
 }
 }

 message?.let { msg ->
 HaiCard {
 Text(msg)
 }
 }

 OutlinedButton(
 onClick = onClose,
 enabled = !busy,
 modifier = Modifier.fillMaxWidth()
 ) {
 Text("إلغاء")
 }
 }
}
