package salu.Kandoraq.Miauterm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

class MiautermApp : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopApiRunnable = Runnable {
        if (startedActivities.get() == 0 && !salu.Kandoraq.Miauterm.service.MiautermService.isRunning) {
            salu.Kandoraq.Miauterm.api.ApiServer.stop(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        salu.Kandoraq.Miauterm.api.ApiServer.start(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                handler.removeCallbacks(stopApiRunnable)
                startedActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities.decrementAndGet() == 0 &&
                    !salu.Kandoraq.Miauterm.service.MiautermService.isRunning) {
                    handler.removeCallbacks(stopApiRunnable)
                    handler.postDelayed(stopApiRunnable, 3000)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

    }

    companion object {
        private val startedActivities = AtomicInteger(0)

        val isInForeground: Boolean
            get() = startedActivities.get() > 0
    }
}
