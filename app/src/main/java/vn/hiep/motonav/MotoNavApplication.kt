package vn.hiep.motonav

import android.app.Application

class MotoNavApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGuard.enforce(this)
    }
}
