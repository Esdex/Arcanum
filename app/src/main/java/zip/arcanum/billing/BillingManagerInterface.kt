package zip.arcanum.billing

import kotlinx.coroutines.flow.StateFlow

interface BillingManagerInterface {
    val isPro: StateFlow<Boolean>
    suspend fun purchasePro()
    suspend fun queryPurchases()
    fun isBillingAvailable(): Boolean
}
