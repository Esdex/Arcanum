package zip.arcanum.core.premium

import zip.arcanum.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumManager @Inject constructor() {

    fun isFeatureAvailable(feature: Feature): Boolean {
        if (BuildConfig.PREMIUM_ENABLED) return true
        return isPurchased() && when (feature) {
            Feature.MULTIPLE_CONTAINERS -> true
            Feature.USB_SUPPORT         -> true
            Feature.HIDDEN_VOLUMES      -> true
            Feature.KEYFILE_SUPPORT     -> true
        }
    }

    fun isPurchased(): Boolean {
        // TODO: implement Play Billing check for playstore flavor
        return false
    }
}
