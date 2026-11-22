package cd.ctm.agent

import android.app.Application

class CtmApplication : Application() {
    val container by lazy { AppContainer(applicationContext) }
}
