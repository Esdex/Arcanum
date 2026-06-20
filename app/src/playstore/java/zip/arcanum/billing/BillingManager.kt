package zip.arcanum.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) : BillingManagerInterface {

    companion object {
        const val PRODUCT_ID_PRO = "arcanum_pro"
    }

    private val _isPro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> get() = _isPro

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener { _, purchases -> processPurchases(purchases) }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchasesInternal()
                }
            }
            override fun onBillingServiceDisconnected() { }
        })
    }

    private fun queryPurchasesInternal() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>?) {
        _isPro.value = purchases?.any { purchase ->
            purchase.products.contains(PRODUCT_ID_PRO) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        } == true
    }

    // Launching the billing flow requires an Activity context; implement once the
    // Play Console product "arcanum_pro" is set up via BillingFlowParams.
    override suspend fun purchasePro() { }

    override suspend fun queryPurchases() {
        if (billingClient.isReady) queryPurchasesInternal()
    }

    override fun isBillingAvailable(): Boolean = billingClient.isReady
}
