Package com.hai.manager

Import androidx.activity.compose.BackHandler
Import androidx.compose.foundation.layout.fillMaxWidth
Import androidx.compose.foundation.layout.heightIn
Import androidx.compose.material3.Button
Import androidx.compose.material3.CircularProgressIndicator
Import androidx.compose.material3.OutlinedButton
Import androidx.compose.material3.OutlinedTextField
Import androidx.compose.material3.Text
Import androidx.compose.runtime.Composable
Import androidx.compose.runtime.LaunchedEffect
Import androidx.compose.runtime.getValue
Import androidx.compose.runtime.mutableStateOf
Import androidx.compose.runtime.remember
Import androidx.compose.runtime.rememberCoroutineScope
Import androidx.compose.runtime.setValue
Import androidx.compose.ui.Modifier
Import androidx.compose.ui.text.input.PasswordVisualTransformation
Import androidx.compose.ui.unit.dp
Import com.hai.manager.router.NativeRouterAuthBrand
Import com.hai.manager.router.RouterAuthRepository
Import kotlinx.coroutines.delay
Import kotlinx.coroutines.launch

@Composable
Fun InAppRouterPanel(
    Url: String,
    OnClose: () -> Unit
) {
    Val scope = rememberCoroutineScope()
    Val auth = remember(url) { RouterAuthRepository(url) }
    Var brand by remember(url) { mutableStateOf(NativeRouterAuthBrand.UNKNOWN) }
    Var detecting by remember(url) { mutableStateOf(true) }
    Var username by remember(url) { mutableStateOf("admin") }
    Var password by remember(url) { mutableStateOf("") }
    Var busy by remember(url) { mutableStateOf(false) }
    Var message by remember(url) { mutableStateOf<String?>(null) }

    Suspend fun detect() {
        Detecting = true
        Brand = runCatching { auth.detectBrand() }.getOrDefault(NativeRouterAuthBrand.UNKNOWN)
        Detecting = false
    }

    LaunchedEffect(url) { detect() }
    BackHandler(enabled =!busy) { onClose() }

    HaiPage(
        Title = "تسجيل الدخول",
        Subtitle = if (detecting) null else brand.displayName
    ) {
        HaiCard {
            if (detecting) {
                CircularProgressIndicator()
            } else {
                if (brand!= NativeRouterAuthBrand.ZTE) {
                    OutlinedTextField(
                        Value = username,
                        OnValueChange = { username = it },
                        Label = { Text("اسم المستخدم") },
                        SingleLine = true,
                        Enabled =!busy,
                        Modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    Value = password,
                    OnValueChange = { password = it },
                    Label = { Text("كلمة المرور") },
                    SingleLine = true,
                    Enabled =!busy,
                    VisualTransformation = PasswordVisualTransformation(),
                    Modifier = Modifier.fillMaxWidth()
                )

                Button(
                    OnClick = {
                        Scope.launch {
                            Busy = true
                            Message = null
                            Val result = auth.login(username.trim(), password)
                            Busy = false
                            Brand = result.brand
                            if (result.success) {
                                Message = "تم تسجيل الدخول بنجاح"
                            } else {
                                Message = result.errorMessage ?: "فشل تسجيل الدخول"
                            }
                        }
                    },
                    Enabled =!busy,
                    Modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                ) {
                    Text("تسجيل الدخول")
                }

                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        OutlinedButton(
            OnClick = onClose,
            Enabled =!busy,
            Modifier = Modifier.fillMaxWidth()
        ) { Text("رجوع") }
    }
}