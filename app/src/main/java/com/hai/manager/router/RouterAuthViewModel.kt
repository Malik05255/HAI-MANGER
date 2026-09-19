package com.hai.manager.router

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sealed class representing all possible authentication states.
 * Can be observed from any component including WorkManager.
 */
sealed class AuthState {
    data object Idle : AuthState()
    data object Loading : AuthState()
    data class Success(
        val brand: NativeRouterAuthBrand,
        val sessionToken: String? = null
    ) : AuthState()
    data class Error(val message: String, val code: Int = 0) : AuthState()
}

/**
 * Result wrapper for authentication operations.
 */
data class AuthResult(
    val success: Boolean,
    val brand: NativeRouterAuthBrand,
    val sessionToken: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel for router authentication that can be used without UI.
 * Exposes authentication state as StateFlow for observation from any component.
 * Suitable for use with WorkManager, services, or background operations.
 */
class RouterAuthViewModel(
    private val baseUrl: String
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val repository = RouterAuthRepository(baseUrl)

    /**
     * Detects the router brand without authentication.
     * Updates authState with Loading -> Success/Error.
     */
    fun detectBrand() {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val brand = withContext(Dispatchers.IO) {
                    repository.detectBrand()
                }
                _authState.value = AuthState.Success(brand)
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    message = e.message ?: "فشل في اكتشاف نوع الراوتر",
                    code = -1
                )
            }
        }
    }

    /**
     * Authenticates with the router using username and password.
     * Updates authState with Loading -> Success/Error.
     */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.login(username, password)
                }
                if (result.success) {
                    _authState.value = AuthState.Success(
                        brand = result.brand,
                        sessionToken = result.sessionToken
                    )
                } else {
                    _authState.value = AuthState.Error(
                        message = result.errorMessage ?: "فشل تسجيل الدخول",
                        code = 401
                    )
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    message = e.message ?: "خطأ في الاتصال",
                    code = -2
                )
            }
        }
    }

    /**
     * Performs authentication and returns result synchronously.
     * Use this from coroutine contexts that need immediate result.
     */
    suspend fun authenticate(username: String, password: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                val result = repository.login(username, password)
                AuthResult(
                    success = result.success,
                    brand = result.brand,
                    sessionToken = null,
                    errorMessage = if (result.success) null else "فشل المصادقة"
                )
            } catch (e: Exception) {
                AuthResult(
                    success = false,
                    brand = NativeRouterAuthBrand.UNKNOWN,
                    errorMessage = e.message
                )
            }
        }
    }

    /**
     * Resets the authentication state to Idle.
     */
    fun reset() {
        _authState.value = AuthState.Idle
    }

    /**
     * Factory for creating RouterAuthViewModel with baseUrl parameter.
     */
    class Factory(private val baseUrl: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(RouterAuthViewModel::class.java)) {
                return RouterAuthViewModel(baseUrl) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
