package io.automated.ventures.everypods

import android.app.Application
import android.content.ComponentName
import android.content.res.Configuration
import android.service.quicksettings.TileService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.automated.ventures.everypods.billing.BillingManager
import io.automated.ventures.everypods.billing.BillingProviderFactory
import io.automated.ventures.everypods.services.AirPodsQSService
import io.automated.ventures.everypods.services.AirPodsService
import io.automated.ventures.everypods.services.GymModeQSService
import io.automated.ventures.everypods.services.ServiceManager

class EveryPodsApplication: Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        android.util.Log.i(
            io.automated.ventures.everypods.startup.StartupGate.TAG,
            "process start pid=${android.os.Process.myPid()} version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "play=${BuildConfig.PLAY_BUILD} debug=${BuildConfig.DEBUG}"
        )
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()

    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val service = ServiceManager.getService() ?: return
        service.refreshLocalizedPresentation()
        TileService.requestListeningState(this, ComponentName(this, AirPodsQSService::class.java))
        TileService.requestListeningState(this, ComponentName(this, GymModeQSService::class.java))
    }
}
