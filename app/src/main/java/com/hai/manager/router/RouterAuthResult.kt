package com.hai.manager.router

/**
 * Sealed class representing authentication result states.
 */
sealed class RouterAuthResult {

    abstract val brand: NativeRouterAuthBrand
    abstract val message: String

    /**
     * Successful authentication result.
     * @param brand Detected or confirmed router brand
     * @param sessionToken Session token for authenticated requests
     */
    data class Success(
        override val brand: NativeRouterAuthBrand,
        val sessionToken: String,
        override val message: String = "تم تسجيل الدخول بنجاح"
    ) : RouterAuthResult()

    /**
     * Failed authentication result.
     * @param brand Detected or attempted router brand
     * @param message Error message describing the failure
     */
    data class Failure(
        override val brand: NativeRouterAuthBrand,
        override val message: String,
        val errorCode: String? = null
    ) : RouterAuthResult()

    /**
     * Convenience property to check if authentication succeeded.
     */
    val success: Boolean
        get() = this is Success

    companion object {
        fun success(brand: NativeRouterAuthBrand, sessionToken: String, message: String = "تم تسجيل الدخول بنجاح"): Success {
            return Success(brand, sessionToken, message)
        }

        fun failure(brand: NativeRouterAuthBrand, message: String, errorCode: String? = null): Failure {
            return Failure(brand, message, errorCode)
        }
    }
}
