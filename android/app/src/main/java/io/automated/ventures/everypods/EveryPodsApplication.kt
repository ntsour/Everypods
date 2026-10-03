package io.automated.ventures.everypods

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.automated.ventures.everypods.billing.BillingManager
import io.automated.ventures.everypods.billing.BillingProviderFactory
import io.automated.ventures.everypods.utils.StemPressDefaultMigration

class EveryPodsApplication: Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        // Before the service / UI read any stem-press config.
        try {
            StemPressDefaultMigration.run(getSharedPreferences("settings", MODE_PRIVATE))
        } catch (e: Exception) {
            Log.w("StemDefaults", "stem default migration failed: ${e.message}")
        }

        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()

    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
    }
}
