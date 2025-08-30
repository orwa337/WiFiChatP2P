package com.example.wifichatp2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Background service that manages ASAP EncounterManager operations
 * This service ensures that encounter management continues even when
 * the main activity is not in the foreground
 */
class EncounterManagerService : Service() {

    companion object {
        private const val TAG = "EncounterMgrService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "encounter_manager_channel"

        fun startService(context: Context) {
            val intent = Intent(context, EncounterManagerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, EncounterManagerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "EncounterManagerService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Schedule periodic encounter management tasks
        scheduleEncounterManagementWork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "EncounterManagerService started")
        return START_STICKY // Restart service if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // This is a started service, not bound
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "EncounterManagerService destroyed")

        // Cancel all work
        WorkManager.getInstance(this).cancelAllWorkByTag("encounter_management")
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ASAP Encounter Manager",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Manages WiFi Direct peer encounters in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Notification channel created")
        }
    }

    /**
     * Create foreground service notification
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi P2P Chat")
            .setContentText("ASAP Encounter Manager running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Schedule periodic work for encounter management
     */
    private fun scheduleEncounterManagementWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val encounterWork = PeriodicWorkRequestBuilder<EncounterManagementWorker>(
            15, TimeUnit.MINUTES // Check every 15 minutes
        )
            .setConstraints(constraints)
            .addTag("encounter_management")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "encounter_management",
            ExistingPeriodicWorkPolicy.KEEP,
            encounterWork
        )

        Log.d(TAG, "Scheduled periodic encounter management work")
    }
}

/**
 * WorkManager worker that performs periodic encounter management tasks
 */
class EncounterManagementWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "EncounterWorker"
    }

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "Performing periodic encounter management tasks")

            // Here we could add periodic tasks like:
            // - Cleaning up old encounter data
            // - Updating encounter statistics
            // - Checking for stale connections

            Log.d(TAG, "Encounter management tasks completed")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Error in encounter management work: ${e.message}")
            e.printStackTrace()
            Result.failure()
        }
    }
}