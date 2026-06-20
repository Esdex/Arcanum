package zip.arcanum.core.premium

import zip.arcanum.billing.BillingManagerInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumManager @Inject constructor(
    private val billingManager: BillingManagerInterface
) {
    fun isFeatureAvailable(feature: Feature): Boolean = billingManager.isPro.value
}
