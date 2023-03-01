// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray

class GeofencingPlugin : ActivityAware, FlutterPlugin, MethodChannel.MethodCallHandler {
    private var mContext: Context? = null
    private var mActivity: Activity? = null
    private var mGeofencingClient: GeofencingClient? = null

    companion object {
        @JvmStatic
        private val TAG = "GeofencingPlugin"

        @JvmStatic
        val SHARED_PREFERENCES_KEY = "geofencing_plugin_cache"

        @JvmStatic
        val CALLBACK_HANDLE_KEY = "callback_handle"

        @JvmStatic
        val CALLBACK_DISPATCHER_HANDLE_KEY = "callback_dispatch_handler"

        @JvmStatic
        val PERSISTENT_GEOFENCES_KEY = "persistent_geofences"

        @JvmStatic
        val PERSISTENT_GEOFENCES_IDS = "persistent_geofences_ids"

        @JvmStatic
        private val sGeofenceCacheLock = Object()

        @JvmStatic
        private fun getPersistentGeofenceKey(id: String): String {
            return "persistent_geofence/$id"
        }
    }

    /**
     * Registers a Dart callback function to call when a geofence is discovered.
     */
    private fun initializeService(callbackHandle: Long) {
        Log.d(TAG, "Initializing GeofencingService")
        mContext?.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(CALLBACK_DISPATCHER_HANDLE_KEY, callbackHandle)
            ?.apply()
    }

    private fun registerGeofenceWithArgs(
        args: ArrayList<*>,
        cache: Boolean,
        result: MethodChannel.Result?
    ) {
        // parse arguments
        val callbackHandle = args[0] as Long
        val id = args[1] as String
        val latitude = args[2] as Double
        val longitude = args[3] as Double
        val radius = (args[4] as Number).toFloat()
        val fenceTriggers = args[5] as Int
        val initialTriggers = args[6] as Int
        val expirationDuration = (args[7] as Int).toLong()
        val loiteringDelay = args[8] as Int
        val notificationResponsiveness = args[9] as Int

        // register geofence
        registerGeofence(
            callbackHandle,
            id,
            latitude,
            longitude,
            radius,
            fenceTriggers,
            initialTriggers,
            expirationDuration,
            loiteringDelay,
            notificationResponsiveness,
        ) { error ->
            run {
                // check if there was an error
                if (error != null) {
                    result?.error("geofence-register-error", error.message, null)
                    return@run
                }

                // cache the created geofence
                if (!cache) {
                    result?.success(null)
                    return@run
                }
                addGeofenceToCache(
                    id,
                    args,
                )
                result?.success(null)
            }
        }
    }

    /**
     * Registers a new geofence.
     */
    private fun registerGeofence(
        callbackHandle: Long,
        id: String,
        latitude: Double,
        longitude: Double,
        radius: Float,
        fenceTriggers: Int,
        initialTriggers: Int,
        expirationDuration: Long,
        loiteringDelay: Int,
        notificationResponsiveness: Int,
        callback: (Throwable?) -> Unit,
    ) {
        // get geofence descriptor
        val geofence = getGeofence(
            id,
            latitude,
            longitude,
            radius,
            expirationDuration,
            fenceTriggers,
            loiteringDelay,
            notificationResponsiveness
        )
        val geofenceRequest = getGeofencingRequest(geofence, initialTriggers)
        val geofencePendingIntent = getGeofencePendingIndent(mContext!!, callbackHandle)

        // add geofence
        mGeofencingClient!!.addGeofences(
            geofenceRequest,
            geofencePendingIntent
        ).run {
            addOnSuccessListener {
                Log.i(TAG, "Successfully added geofence")
                callback(null)
            }
            addOnFailureListener {
                Log.e(TAG, "Failed to add geofence: $it")
                callback(it)
            }
        }
    }

    /**
     * Adds a geofence to a local cache of geofences.
     */
    private fun addGeofenceToCache(
        id: String, args: ArrayList<*>
    ) {
        // prevent concurrent cache reads
        synchronized(sGeofenceCacheLock) {
            // cache geofence with the args used to create it
            val sharedPreferences =
                mContext!!.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            val argsSerialized = JSONArray(args)
            var cachedGeofences = sharedPreferences.getStringSet(PERSISTENT_GEOFENCES_IDS, null)
            cachedGeofences = if (cachedGeofences == null) {
                HashSet<String>()
            } else {
                HashSet<String>(cachedGeofences)
            }
            cachedGeofences.add(id)
            mContext!!.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PERSISTENT_GEOFENCES_IDS, cachedGeofences)
                .putString(getPersistentGeofenceKey(id), argsSerialized.toString())
                .apply()
        }
    }

    /**
     * Removes a geofence from a local geofences cache.
     */
    private fun removeGeofenceFromCache(id: String) {
        // prevent concurrent cache reads
        synchronized(sGeofenceCacheLock) {
            val sharedPreferences =
                mContext!!.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            var persistentGeofences =
                sharedPreferences.getStringSet(PERSISTENT_GEOFENCES_IDS, null) ?: return
            persistentGeofences = HashSet<String>(persistentGeofences)
            persistentGeofences.remove(id)
            sharedPreferences.edit()
                .remove(getPersistentGeofenceKey(id))
                .putStringSet(PERSISTENT_GEOFENCES_IDS, persistentGeofences)
                .apply()
        }
    }

    /**
     * Re-registers geofences after a reboot.
     * Uses cached geofences.
     */
    fun reRegisterAfterReboot() {
        // prevent concurrent cache reads
        synchronized(sGeofenceCacheLock) {
            // read each cached geofence
            val sharedPreferences =
                mContext!!.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            val persistentGeofences =
                sharedPreferences.getStringSet(PERSISTENT_GEOFENCES_IDS, null) ?: return

            // skip if no geofence0s
            if (persistentGeofences.size == 0) return

            // get geo client
            mGeofencingClient = LocationServices.getGeofencingClient(mContext!!)

            // deserialize all cached geofences
            for (id in persistentGeofences) {
                // deserialize geofence args
                val geofenceJson =
                    sharedPreferences.getString(getPersistentGeofenceKey(id), null) ?: continue
                val geofenceArgs = JSONArray(geofenceJson)
                val args = ArrayList<Object>()
                for (i in 0 until geofenceArgs.length()) {
                    args.add(geofenceArgs.get(i) as Object)
                }

                // register fence
                registerGeofenceWithArgs(
                    args,
                    false,
                    null
                )
            }
        }
    }

    /**
     * Removes geofences with the given ids.
     */
    fun removeGeofence(id: String, result: MethodChannel.Result?) {
        mGeofencingClient!!.removeGeofences(listOf(id)).run {
            addOnSuccessListener {
                removeGeofenceFromCache(id)
                result?.success(it)
            }
            addOnFailureListener {
                result?.error("remove-geofences-error", it.message, null)
            }
        }
    }

    /**
     * Gets IDs of registered geofences.
     */
    private fun getRegisteredGeofenceIds(result: MethodChannel.Result?) {
        synchronized(sGeofenceCacheLock) {
            val list = ArrayList<String>()
            val p = mContext!!.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
            val persistentGeofences = p.getStringSet(PERSISTENT_GEOFENCES_IDS, null)
            if (persistentGeofences != null && persistentGeofences.size > 0) {
                for (id in persistentGeofences) {
                    list.add(id)
                }
            }
            result?.success(list)
        }
    }

    // Object construct methods
    /**
     * Constructs a new geofence object.
     */
    private fun getGeofence(
        id: String,
        latitude: Double,
        longitude: Double,
        radius: Float,
        expirationDuration: Long,
        fenceTriggers: Int,
        loiteringDelay: Int,
        notificationResponsiveness: Int
    ): Geofence {
        return Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radius)
            .setTransitionTypes(fenceTriggers)
            .setLoiteringDelay(loiteringDelay)
            .setNotificationResponsiveness(notificationResponsiveness)
            .setExpirationDuration(expirationDuration)
            .build()
    }

    /**
     * Constructs a new geofencing request.
     */
    private fun getGeofencingRequest(geofence: Geofence, initialTriggers: Int): GeofencingRequest {
        return GeofencingRequest.Builder().apply {
            addGeofence(geofence)
            setInitialTrigger(initialTriggers)
        }.build()
    }

    /**
     * Constructs a new geofence pending intent.
     */
    private fun getGeofencePendingIndent(
        context: Context,
        callbackHandle: Long
    ): PendingIntent {
        val intent = Intent(context, GeofencingBroadcastReceiver::class.java)
            .putExtra(CALLBACK_HANDLE_KEY, callbackHandle)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    // Flutter engine callbacks
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedContext(binding.applicationContext)
        val channel = MethodChannel(binding.binaryMessenger, "plugins.flutter.io/geofencing_plugin")
        channel.setMethodCallHandler(this)
    }

    fun onAttachedContext(context: Context) {
        mContext = context
        mGeofencingClient = LocationServices.getGeofencingClient(context)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onDetachedContext()
    }

    private fun onDetachedContext() {
        mContext = null
        mGeofencingClient = null
    }


    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mActivity = binding.activity
    }

    override fun onDetachedFromActivity() {
        mActivity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        mActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        mActivity = binding.activity
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments<ArrayList<*>>()
        when (call.method) {
            "GeofencingPlugin.initializeService" -> {
                if (args == null || args.size < 1) {
                    result.error("args-invalid", "Callback handle not provided", null)
                    return
                }
                val callbackHandle = args[0] as Long
                initializeService(callbackHandle)
                result.success(null)
            }
            "GeofencingPlugin.registerGeofence" -> {
                if (args == null) {
                    result.error("args-invalid", "Args not provided", null)
                    return
                }
                registerGeofenceWithArgs(
                    args,
                    true,
                    result
                )
            }
            "GeofencingPlugin.removeGeofence" -> {
                if (args == null || args.size < 1) {
                    result.error("args-invalid", "Args not provided", null)
                    return
                }
                val id = args[0] as String
                removeGeofence(
                    id,
                    result
                )
            }
            "GeofencingPlugin.getRegisteredGeofenceIds" -> getRegisteredGeofenceIds(
                result
            )
            else -> result.notImplemented()
        }
    }
}