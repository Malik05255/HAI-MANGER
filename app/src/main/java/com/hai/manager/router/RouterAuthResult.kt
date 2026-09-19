Package com.hai.manager.router

Data class RouterAuthResult(
    val success: Boolean,
    val brand: NativeRouterAuthBrand,
    val errorMessage: String?
)