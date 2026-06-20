package zip.arcanum.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import zip.arcanum.billing.BillingManager
import zip.arcanum.billing.BillingManagerInterface
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {
    @Binds
    @Singleton
    abstract fun bindBillingManager(impl: BillingManager): BillingManagerInterface
}
