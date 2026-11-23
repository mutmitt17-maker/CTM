package cd.ctm.agent

import android.app.Application
import cd.ctm.agent.sync.SyncWorker

class CtmApplication : Application() {
    val container by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(applicationContext)
    }
}
