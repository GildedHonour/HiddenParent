package com.huawei.kern_stabiliser

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import java.io.DataOutputStream
import java.io.IOException

class LocationHelper4(private val context: Context, private val onLocationChangedHandler: (Location?) -> Unit) {
    companion object {
        const val GPS_MODULE_DELAY_IN_MS: Long = 3000
        const val TAG = "LocationHelper4"

        private fun turnOnGPS() {
            //TODO presumably this no longer works on Android 10
            //executeRootCommand("settings put secure location_providers_allowed +gps,network,wifi")
            //this should be used instead
            executeRootCommand("settings put secure location_mode 3")
        }

        private fun turnOffGPS() {
            executeRootCommand("settings put secure location_mode 0")
        }

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
    }

    //TODO: fUsedClient may needed to be deleted because other clients have already been utilised

    val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var lastCachedLocation: Location? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationRequest: LocationRequest = LocationRequest.create()
        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        .setInterval(20000)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            onLocationChangedHandler(locationResult.lastLocation)
        }
    }

    private var isLocationUpdatesActive = false

    fun startLocationUpdates(context: Context) {
        try {
            if (Helper.isRooted()) {
                turnOnGPS()
                Thread.sleep(GPS_MODULE_DELAY_IN_MS)
                turnOffGPS()
            }
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "GPS+ with root: failed")
            }
        }

        if (!isLocationUpdatesActive) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            isLocationUpdatesActive = true

            // TODO
            // locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, LocationListenerObj, Looper.getMainLooper())
            // locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0f, LocationListenerObj, Looper.getMainLooper())
            // locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000 * 60 * 1, 10f, LocationListenerObj, Looper.getMainLooper())

            if (BuildConfig.DEBUG) {
                Log.d("LocationHelper4", "startLocationUpdates > ON")
            }
        }
    }

    fun stopLocationUpdates() {
        if (isLocationUpdatesActive) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isLocationUpdatesActive = false

            if (BuildConfig.DEBUG) {
                Log.d("LocationHelper4", "startLocationUpdates > OFF")
            }
        }
    }

    fun getCurrentLocation(context: Context, callback: (Location?) -> Unit) {
        if (isLocationUpdatesActive) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    callback(location)
                }
                .addOnFailureListener { exception ->
                    callback(null)
                }
        } else {
            startLocationUpdates(context)
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    callback(location)
                }
                .addOnFailureListener { exception ->
                    callback(null)
                }
            //stopLocationUpdates()
        }
    }

    fun forceLocationUpdate(context: Context) {
        fusedLocationClient.requestLocationUpdates(
            locationRequest, { locationResult ->
                if (locationResult != null) {
                    onLocationChangedHandler(locationResult)
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
            },
            null
        )
    }

    val LocationListenerObj = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (BuildConfig.DEBUG) {
                Log.d(SysGuardService.TAG, "onLocationChanged > new one: ${location}")
            }

            lastCachedLocation = location
            locationManager.removeUpdates(this)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {
        }

        override fun onProviderDisabled(provider: String) {
        }
    }

    fun getLastKnownLocationWithLocationManager4(forceUpdate: Boolean = false): Location? {
        if (forceUpdate) {
            try {
                if (Helper.isRooted()) {
                    turnOnGPS()
                    Thread.sleep(GPS_MODULE_DELAY_IN_MS)
                }
            } catch (e: IOException) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "GPS+ with root: failed")
                }
            }

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 10f, LocationListenerObj, Looper.getMainLooper())
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 10f, LocationListenerObj, Looper.getMainLooper())

            try {
                if (Helper.isRooted()) {
                    turnOffGPS()
                }
            } catch (e: IOException) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "GPS+ with root: failed")
                }
            }
        }

        val gpsProvider = LocationManager.GPS_PROVIDER

        val gpsLocation = locationManager.getLastKnownLocation(gpsProvider)
        if (gpsLocation != null) {
            if (BuildConfig.DEBUG) {
                Log.d(SysGuardService.TAG, "gpsLocation: ${gpsLocation}")
            }

            lastCachedLocation = gpsLocation
            return gpsLocation
        }

        val networkProvider = LocationManager.NETWORK_PROVIDER
        val networkLocation = locationManager.getLastKnownLocation(networkProvider)
        if (networkLocation != null) {
            if (BuildConfig.DEBUG) {
                Log.d(SysGuardService.TAG, "networkLocation: ${networkLocation}")
            }

            lastCachedLocation = networkLocation
            return networkLocation
        }

        return lastCachedLocation
    }
}
