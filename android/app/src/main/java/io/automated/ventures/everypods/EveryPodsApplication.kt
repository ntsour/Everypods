package io.automated.ventures.everypods

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.automated.ventures.everypods.billing.BillingManager
import io.automated.ventures.everypods.billing.BillingProviderFactory

class EveryPodsApplication: Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()

    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
    }
}
