package com.huawei.kern_stabiliser

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

//1
object LocationHelper {
    const val TAG = "LocationHelper"
    const val TIMEOUT_IN_SECONDS: Long = 5

    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        "fused",
    )

    fun getLocation(ctx: Context, forceLive: Boolean): Location? {
        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (forceLive) {
            if (Helper.isRooted()) {
                turnOnGPS()
                Thread.sleep(100)
                val liveLocation = getLiveLocation(ctx, locationManager)
                turnOffGPS()
                if (liveLocation != null) {
                    return liveLocation
                }
            } else {
                val liveLocation = getLiveLocation(ctx, locationManager)
                if (liveLocation != null) {
                    return liveLocation
                }
            }
        } else {
            // Try to obtain cached coordinates first
            val cachedLocation = getCachedLocation(ctx, locationManager)
            if (cachedLocation != null) {
                return cachedLocation
            }

            // If cached coordinates are not available, attempt to get live coordinates
            val liveLocation = getLiveLocation(ctx, locationManager)
            if (liveLocation != null) {
                return liveLocation
            }
        }

        return null
    }

    private fun getCachedLocation(ctx: Context, locationManager: LocationManager): Location? {
        for (provider in providers) {
            when (provider) {
                "fused" -> {
                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
                    val fusedLocationTask = fusedLocationClient.lastLocation
                    val fusedLocation = getTaskResult(fusedLocationTask)
                    if (fusedLocation != null) {
                        return fusedLocation
                    }
                }
                else -> {
                    if (locationManager.isProviderEnabled(provider)) {
                        val lastKnownLocation = locationManager.getLastKnownLocation(provider)
                        Log.d(
                            TAG,
                            "Provider: $provider, Last Known Location: $lastKnownLocation"
                        )

                        if (lastKnownLocation != null) {
                            return lastKnownLocation
                        }
                    }
                }
            }
        }

        return null
    }

    val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "onLocationChanged: ${location.latitude}, ${location.longitude}")

        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            TODO("Not yet implemented")
        }

        override fun onProviderEnabled(provider: String?) {
            TODO("Not yet implemented")
        }

        override fun onProviderDisabled(provider: String?) {
            TODO("Not yet implemented")
        }

//        private fun stopLocationUpdates(ctx: Context) {
//            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
//
//            try {
//                // Stop location updates
//                fusedLocationClient.removeLocationUpdates(locationCallback)
//                Log.d(TAG, "Location updates stopped")
//            } catch (e: SecurityException) {
//                Log.e(TAG, "SecurityException while stopping location updates: ${e.message}")
//            }
//        }
//
//        private val locationCallback = object : LocationCallback() {
//            override fun onLocationResult(locationResult: LocationResult) {
//                super.onLocationResult(locationResult)
//                val location = locationResult.lastLocation
//                if (location != null) {
//                    onLocationChanged(location)
//                } else {
//                    Log.e(TAG, "Received null location in onLocationResult")
//                }
//            }
//        }
    }


    private fun getLiveLocation(ctx: Context, locationManager: LocationManager): Location? {
        for (provider in providers) {
            when (provider) {
                LocationManager.GPS_PROVIDER -> {
                    val _locationRequest = LocationRequest.create()
                        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                        .setInterval(0)
                        .setFastestInterval(0)

                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
                    val locationResult: Task<LocationAvailability> = fusedLocationClient.getLocationAvailability()

                    if (!Tasks.await(locationResult).isLocationAvailable) {
                        return null
                    }

                    val locationTask: Task<Location> = fusedLocationClient.getCurrentLocation(
                        LocationRequest.PRIORITY_HIGH_ACCURACY,
                        null
                    )

                    return Tasks.await(locationTask)
                }

                "fused" -> {
                    val apiAvailability = GoogleApiAvailability.getInstance()
                    val resultCode = apiAvailability.isGooglePlayServicesAvailable(ctx)

                    if (resultCode == ConnectionResult.SUCCESS) {
                        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
                        val fusedLocationTask = fusedLocationClient.lastLocation
                        val fusedLocation = getTaskResult(fusedLocationTask)
                        if (fusedLocation != null) {
                            return fusedLocation
                        }
                    } else {
                        Log.w(TAG, " Google Play Services aren't available, can't use fused")
                    }

                }

                else -> {
                    if (locationManager.isProviderEnabled(provider)) {
//                        locationManager.requestLocationUpdates(
//                            provider,
//                            locationListener,
//                            Looper.getMainLooper()
//                        )

                        val lastKnownLocation = locationManager.getLastKnownLocation(provider)
                        if (lastKnownLocation != null) {
                            return lastKnownLocation
                        }
                    }
                }
            }
        }

        return null
    }

    private fun getTaskResult(t: Task<Location>): Location? {
        return try {
            Tasks.await(t, TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)
            if (t.isSuccessful && t.isComplete) {
                t.result
            } else {
                // Task is not complete, handle accordingly
                Log.w(TAG, "Task is not yet complete")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location task result: ${e.message}")
            null
        }
    }

    fun turnOnGPS() {
        executeRootCommand("settings put secure location_providers_allowed +gps")
    }

    fun turnOffGPS() {
        executeRootCommand("settings put secure location_providers_allowed -gps")
    }

    fun executeRootCommand(command: String) {
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
