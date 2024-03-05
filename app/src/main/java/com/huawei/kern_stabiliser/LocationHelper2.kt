package com.huawei.kern_stabiliser

import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.io.DataOutputStream
import java.io.IOException


//2
class LocationHelper2(private val ctx: Context) {
    companion object {
        const val TAG = "LocationHelper2"

        private fun executeRootCommand(command: String) {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes(command + "\n")
                os.writeBytes("exit\n")
                os.flush()
                os.close()
                process.waitFor()
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        private fun turnOnGPS() {
            executeRootCommand("settings put secure location_providers_allowed +gps")
        }

        private fun turnOffGPS() {
            executeRootCommand("settings put secure location_providers_allowed -gps")
        }
    }

    private val locationManager: LocationManager by lazy {
        ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(ctx)
    }

    private fun isGpsProviderEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun getCachedLocationFromGps(): Location? {
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }

    private fun getCachedLocationFromFused(): Location? {
        return try {
            val fusedTask = fusedLocationClient.lastLocation
            Tasks.await(fusedTask)
        } catch (e: Exception) {
            null
        }
    }

    fun getCachedLocation(): Location? {
        return if (isGpsProviderEnabled()) {
            getCachedLocationFromGps()
        } else {
            getCachedLocationFromFused()
        }
    }

    fun getLiveLocation(): Location? {
        val result = try {
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gpsLocation != null) {
                gpsLocation
            } else {
                val fusedLocation = fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, null)
                Tasks.await(fusedLocation)
            }
        } catch (e: Exception) {
            null
        }

        return result
    }

    fun getLocation(forceLive: Boolean): Location? {
        if (forceLive) {
            if (!isGpsProviderEnabled() && Helper.isRooted()) {
                turnOnGPS()
                Thread.sleep(300)
                val liveLocation = getLiveLocation()
                turnOffGPS()
                if (liveLocation != null) {
                    return liveLocation
                }
            } else {
                val liveLocation = getLiveLocation()
                if (liveLocation != null) {
                    return liveLocation
                }
            }
        } else {
            // Try to obtain cached coordinates first
            val cachedLocation = getCachedLocation()
            if (cachedLocation != null) {
                return cachedLocation
            }

            // If cached coordinates are not available, attempt to get live coordinates
            val liveLocation = getLiveLocation()
            if (liveLocation != null) {
                return liveLocation
            }
        }

        return null
    }
}