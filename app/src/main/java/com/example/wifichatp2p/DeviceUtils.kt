package com.example.wifichatp2p

import android.os.Build
import android.util.Log

/**
 * Device-specific utilities and constants for handling manufacturer differences
 * in WiFi Direct P2P behavior, particularly between Samsung and Pixel devices.
 */
object DeviceUtils {
    private const val TAG = "DeviceUtils"

    enum class DeviceManufacturer {
        SAMSUNG,
        GOOGLE_PIXEL,
        OTHER
    }

    /**
     * Device-specific configuration constants
     */
    object Config {
        // Socket connection timeouts (milliseconds)
        const val SAMSUNG_SOCKET_TIMEOUT = 10000L
        const val PIXEL_SOCKET_TIMEOUT = 15000L
        const val DEFAULT_SOCKET_TIMEOUT = 12000L

        // ASAP encounter initialization delays (milliseconds)
        const val SAMSUNG_ASAP_DELAY = 100L
        const val PIXEL_ASAP_DELAY = 500L
        const val DEFAULT_ASAP_DELAY = 300L

        // WiFi Direct state management delays (milliseconds)
        const val SAMSUNG_WIFI_STATE_DELAY = 50L
        const val PIXEL_WIFI_STATE_DELAY = 200L
        const val DEFAULT_WIFI_STATE_DELAY = 100L

        // Connection retry configuration
        const val SAMSUNG_MAX_RETRIES = 3
        const val PIXEL_MAX_RETRIES = 5
        const val DEFAULT_MAX_RETRIES = 4

        // Retry backoff multiplier
        const val RETRY_BACKOFF_MULTIPLIER = 1.5
        const val MAX_RETRY_DELAY = 5000L
    }

    /**
     * Detect the device manufacturer based on Build properties
     */
    fun getDeviceManufacturer(): DeviceManufacturer {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()

        return when {
            manufacturer.contains("samsung") -> DeviceManufacturer.SAMSUNG
            manufacturer.contains("google") || model.contains("pixel") -> DeviceManufacturer.GOOGLE_PIXEL
            else -> DeviceManufacturer.OTHER
        }
    }

    /**
     * Get device-specific socket timeout
     */
    fun getSocketTimeout(): Long {
        return when (getDeviceManufacturer()) {
            DeviceManufacturer.SAMSUNG -> Config.SAMSUNG_SOCKET_TIMEOUT
            DeviceManufacturer.GOOGLE_PIXEL -> Config.PIXEL_SOCKET_TIMEOUT
            DeviceManufacturer.OTHER -> Config.DEFAULT_SOCKET_TIMEOUT
        }
    }

    /**
     * Get device-specific ASAP initialization delay
     */
    fun getAsapInitDelay(): Long {
        return when (getDeviceManufacturer()) {
            DeviceManufacturer.SAMSUNG -> Config.SAMSUNG_ASAP_DELAY
            DeviceManufacturer.GOOGLE_PIXEL -> Config.PIXEL_ASAP_DELAY
            DeviceManufacturer.OTHER -> Config.DEFAULT_ASAP_DELAY
        }
    }

    /**
     * Get device-specific WiFi state management delay
     */
    fun getWifiStateDelay(): Long {
        return when (getDeviceManufacturer()) {
            DeviceManufacturer.SAMSUNG -> Config.SAMSUNG_WIFI_STATE_DELAY
            DeviceManufacturer.GOOGLE_PIXEL -> Config.PIXEL_WIFI_STATE_DELAY
            DeviceManufacturer.OTHER -> Config.DEFAULT_WIFI_STATE_DELAY
        }
    }

    /**
     * Get device-specific maximum retry count
     */
    fun getMaxRetries(): Int {
        return when (getDeviceManufacturer()) {
            DeviceManufacturer.SAMSUNG -> Config.SAMSUNG_MAX_RETRIES
            DeviceManufacturer.GOOGLE_PIXEL -> Config.PIXEL_MAX_RETRIES
            DeviceManufacturer.OTHER -> Config.DEFAULT_MAX_RETRIES
        }
    }

    /**
     * Log device information for debugging
     */
    fun logDeviceInfo() {
        val manufacturer = getDeviceManufacturer()
        Log.i(TAG, "Device Info - Manufacturer: $manufacturer")
        Log.i(TAG, "  Build.MANUFACTURER: ${Build.MANUFACTURER}")
        Log.i(TAG, "  Build.MODEL: ${Build.MODEL}")
        Log.i(TAG, "  Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}")
        Log.i(TAG, "  Socket Timeout: ${getSocketTimeout()}ms")
        Log.i(TAG, "  ASAP Init Delay: ${getAsapInitDelay()}ms")
        Log.i(TAG, "  WiFi State Delay: ${getWifiStateDelay()}ms")
        Log.i(TAG, "  Max Retries: ${getMaxRetries()}")
    }
}