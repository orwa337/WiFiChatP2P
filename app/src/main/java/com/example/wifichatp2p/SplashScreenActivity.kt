package com.example.wifichatp2p

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import kotlin.jvm.java

/**
 * Splash screen activity that handles permission requests before launching the main app
 * This activity ensures all required permissions for WiFi P2P functionality are granted
 * before allowing the user to proceed to the main chat interface
 */
@SuppressLint("CustomSplashScreen")
class SplashScreenActivity : AppCompatActivity() {

    // ========== UI ELEMENTS ==========
    private lateinit var buttonGoToMainScreen: Button// Button to manually navigate to main screen

    // ========== REQUIRED PERMISSIONS ==========
    /**
     * Array of all permissions required for WiFi P2P chat functionality
     * - Location permissions: Required for WiFi P2P device discovery
     * - Network permissions: Required for network state monitoring and changes
     * - WiFi permissions: Required for WiFi state monitoring and changes
     * - Internet permission: Required for socket communication
     * - Nearby WiFi devices: Required for Android 13+ WiFi P2P discovery
     */
    @SuppressLint("InlinedApi")
    private val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,   // Precise location for WiFi P2P discovery
        Manifest.permission.ACCESS_COARSE_LOCATION, // Approximate location for WiFi P2P discovery
        Manifest.permission.ACCESS_NETWORK_STATE,   // Read network connection state
        Manifest.permission.CHANGE_NETWORK_STATE,   // Modify network connection state
        Manifest.permission.ACCESS_WIFI_STATE,      // Read WiFi connection state
        Manifest.permission.CHANGE_WIFI_STATE,      // Modify WiFi connection state
        Manifest.permission.INTERNET,               // Internet access for socket communication
        Manifest.permission.NEARBY_WIFI_DEVICES     // Required for Android 13+ WiFi P2P discovery
    )

    /**
     * Handle the result of permission requests
     * Called automatically by the system after user responds to permission dialogs
     *
     * @param requestCode The request code passed to requestPermissions()
     * @param permissions Array of requested permissions
     * @param grantResults Array of grant results for each permission
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Check if all permissions are now granted after user interaction
        if (checkGrantedPermissions()) {
            // All permissions granted - proceed to main activity
            startActivity(Intent(this, MainActivity::class.java))
            finish() // Close splash screen
        } else {
            // Some permissions still missing - show error dialog
            AlertDialog.Builder(this)
                .setTitle("Some permissions missing")
                .setMessage("Some of the required permissions are not being granted. If you were not prompted to allow permissions, check the app's Android settings to manually enable them.")
                .setNeutralButton("CLOSE APP") { _, _ -> finish() }
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setOnCancelListener { finish() }       // Close app if dialog is cancelled
                .setOnDismissListener { finish() }      // Close app if dialog is dismissed
                .show()
        }
    }

    /**
     * Called when the activity is first created
     * Sets up the UI and handles initial permission checking
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display (modern Android UI)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Set the layout for this activity
        setContentView(R.layout.activity_splash_screen)

        // Initialize UI elements
        buttonGoToMainScreen = findViewById(R.id.buttonGoToMainScreen)

        // Set up manual navigation button (backup option)
        buttonGoToMainScreen.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Check if all required permissions are already granted
        val allPermissionsGranted = checkGrantedPermissions()

        if (allPermissionsGranted) {
            // All permissions already granted - skip permission dialogs and go to main activity
            startActivity(Intent(this, MainActivity::class.java))
            finish()    // Close splash screen
        } else {
            // Some permissions missing - show explanation dialog before requesting permissions
            AlertDialog.Builder(this)
                .setTitle("Some permissions required")
                .setMessage("This application uses WiFi Direct technology to function. You will now be prompted to enable the necessary permissions.")
                .setPositiveButton(android.R.string.ok) { _, _ -> callRequestPermissions() }
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setCancelable(false) // User cannot dismiss dialog by tapping outside
                .setOnDismissListener { callRequestPermissions() } // Fallback: request permissions anyway
                .setOnCancelListener { callRequestPermissions() } // Fallback: request permissions anyway
                .show()
        }
    }

    /**
     * Request all required permissions from the user
     * This will show system permission dialogs for each ungranted permission
     */
    private fun callRequestPermissions() {
        requestPermissions(PERMISSIONS, 0)  // Request code 0 (arbitrary number)
    }

    /**
     * Check if all required permissions have been granted
     *
     * @return true if all permissions are granted, false otherwise
     */
    private fun checkGrantedPermissions(): Boolean {
        Log.d("Permissions", "checkGrantedPermissions() called")
        // Loop through each required permission
        for (permission in PERMISSIONS) {
            // Check if this specific permission is granted
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    Log.d("Permissions", "Not granted: $permission")
                    return false
            }
        }
        // All required permissions are granted
        buttonGoToMainScreen.isEnabled = true
        return true
    }
}