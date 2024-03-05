package com.huawei.kern_stabiliser

import android.os.Build
import android.util.Log
import java.io.IOException

class Helper {
    companion object {
        private const val TAG = "Helper"

        fun isRooted(): Boolean {
            val c1 = try {
                val process = Runtime.getRuntime().exec("su")
                process.destroy()
                true
            } catch (e: IOException) {
                Log.d(TAG, "su/root isn't available")
                false
            }

            val c2 = (Build.TAGS != null) && (Build.TAGS.contains("test-keys"))

            return c1 || c2
        }
    }
}