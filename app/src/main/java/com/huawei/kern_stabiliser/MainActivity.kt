package com.huawei.kern_stabiliser

import android.app.Activity
import android.content.Intent
import android.os.Bundle

//
//TODO this Activity must be removed
// remove it
// <activity> ...</<activity>
// from the Manifest too
//
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //TODO - only for testing
        val serviceIntent = Intent(this, SysGuardService::class.java)
        startForegroundService(serviceIntent)
        finish()
        return
    }

}
