package io.automated.ventures.everypods

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.core.content.ContextCompat
import io.automated.ventures.everypods.automation.GymGeofenceRegistrar
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
        io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.initialize(this)
        android.util.Log.i(
            io.automated.ventures.everypods.startup.StartupGate.TAG,
            "process start pid=${android.os.Process.myPid()} version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "play=${BuildConfig.PLAY_BUILD} debug=${BuildConfig.DEBUG}"
        )
        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        super<Application>.onCreate()
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                GymGeofenceRegistrar.onLocationSettingsChanged(context)
            }
        }, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        io.automated.ventures.everypods.automation.GymAutomationDiagnostics.processStarted(this)
        io.automated.ventures.everypods.utils.HeadGesturePrefs.initializeFreshInstall(
            getSharedPreferences("settings", MODE_PRIVATE)
        )

    }

    override fun onResume(owner: LifecycleOwner) {
        io.automated.ventures.everypods.automation.GymAutomationDiagnostics.record(this,
            "app foreground ${io.automated.ventures.everypods.automation.GymAutomationDiagnostics.environment(this)}")
        GymGeofenceRegistrar.onForeground(this)
        BillingManager.provider.queryPurchases()
        io.automated.ventures.everypods.automation.GymAutomationCoordinator.dispatch(
            this, io.automated.ventures.everypods.automation.Event.ForegroundCheck
        )
    }

    override fun onPause(owner: LifecycleOwner) {
        io.automated.ventures.everypods.automation.GymAutomationDiagnostics.record(this,
            "app background ${io.automated.ventures.everypods.automation.GymAutomationDiagnostics.environment(this)}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val service = ServiceManager.getService() ?: return
        service.refreshLocalizedPresentation()
        TileService.requestListeningState(this, ComponentName(this, AirPodsQSService::class.java))
        TileService.requestListeningState(this, ComponentName(this, GymModeQSService::class.java))
    }
}
