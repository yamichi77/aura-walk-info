package io.github.yamichi77.aurawalkinfo

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.yamichi77.aurawalkinfo.session.DefaultSessionStartMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AuraWalkApplication : Application() {
    val container by lazy { AppContainer(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container.walkSessionRepository.start(scope, DefaultSessionStartMode)
    }
}

internal fun Context.isDebugBuild(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
