package io.nikos.andropods

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.nikos.andropods.billing.BillingManager
import io.nikos.andropods.billing.BillingProviderFactory

class AndroPodsApplication: Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()
    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
    }
}
