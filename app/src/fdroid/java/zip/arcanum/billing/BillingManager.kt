package zip.arcanum.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor() : BillingManagerInterface {
    override val isPro: StateFlow<Boolean> = MutableStateFlow(true)
    override suspend fun purchasePro() { }
    override suspend fun queryPurchases() { }
    override fun isBillingAvailable() = false
}
