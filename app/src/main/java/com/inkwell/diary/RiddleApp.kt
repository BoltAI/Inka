package com.inkwell.diary

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class RiddleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
    }
}
