Package com.hai.manager.router

Data class RouterAuthResult(
    Val success: Boolean,
    Val brand: NativeRouterAuthBrand,
    Val token: String? = null,
    Val sessionId: String? = null,
    Val message: String? = null
)