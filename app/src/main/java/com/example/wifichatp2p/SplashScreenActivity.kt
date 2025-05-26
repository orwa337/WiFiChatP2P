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
 * Splash screen to check and ensure the required permissions are granted.
 */
@SuppressLint("CustomSplashScreen")
class SplashScreenActivity : AppCompatActivity() {

    private lateinit var buttonGoToMainScreen: Button

    @SuppressLint("InlinedApi")
    private val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.CHANGE_NETWORK_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.INTERNET,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Second check
        if (checkGrantedPermissions()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Some permissions missing")
                .setMessage("Some of the required permissions are not being granted. If you were not prompted to allow permissions, check the app's Android settings to manually enable them.")
                .setNeutralButton("CLOSE APP") { _, _ -> finish() }
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setOnCancelListener { finish() }
                .setOnDismissListener { finish() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_splash_screen)

        buttonGoToMainScreen = findViewById(R.id.buttonGoToMainScreen)
        buttonGoToMainScreen.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        val allPermissionsGranted = checkGrantedPermissions()

        if (allPermissionsGranted) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Some permissions required")
                .setMessage("This application uses WiFi Direct technology to function. You will now be prompted to enable the necessary permissions.")
                .setPositiveButton(android.R.string.ok) { _, _ -> callRequestPermissions() }
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setCancelable(false) // Cannot press outside to close/cancel
                .setOnDismissListener { callRequestPermissions() } // Just in case
                .setOnCancelListener { callRequestPermissions() } // Just in case x2
                .show()
        }
    }

    private fun callRequestPermissions() {
        requestPermissions(PERMISSIONS, 0)
    }

    private fun checkGrantedPermissions(): Boolean {
        Log.d("Permissions", "checkGrantedPermissions() called")
        for (permission in PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                // Skip NEARBY_WIFI_DEVICES check on older Android versions
                if (!(permission == Manifest.permission.NEARBY_WIFI_DEVICES && Build.VERSION.SDK_INT < 33)) {
                    Log.d("Permissions", "Not granted: $permission")
                    return false
                }
            }
        }
        buttonGoToMainScreen.isEnabled = true
        return true
    }
}