package com.example.wifichatp2p

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Broadcast receiver that listens for WiFi P2P system events
 * This class handles all WiFi Direct related broadcasts from the Android system
 * and notifies the MainActivity about important state changes
 *
 * @param manager The WiFi P2P manager instance
 * @param channel The communication channel for WiFi P2P operations
 * @param activity Reference to MainActivity for updating UI and handling events
 */
class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    // ========== LOGGING ==========
    private val TAG = "WiFiDirectBR"    // Tag for logging purposes

    /**
     * Called when a WiFi P2P broadcast is received
     * This method handles different types of WiFi P2P system events
     *
     * @param context The context in which the receiver is running
     * @param intent The intent containing the broadcast action and data
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun onReceive(context: Context, intent: Intent) {

        // Handle different WiFi P2P broadcast actions
        when (intent.action) {
            // ========== WiFi P2P STATE CHANGED ==========
            /**
             * Broadcast sent when WiFi P2P is enabled or disabled
             * This tells us if the WiFi P2P feature is available for use
             */
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                // Extract the WiFi P2P state from the intent
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)

                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    // WiFi P2P is now enabled and ready to use
                    activity.updateStatus("WiFi P2P is enabled")
                } else {
                    // WiFi P2P is disabled - cannot discover or connect to peers
                    activity.updateStatus("WiFi P2P is disabled")
                }
            }

            // ========== PEERS LIST CHANGED ==========
            /**
             * Broadcast sent when the list of available peers changes
             * This happens when:
             * - New devices are discovered during peer discovery
             * - Previously discovered devices are no longer available
             * - Peer discovery process starts or stops
             */
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "P2P peers changed")

                // Request updated peer list from the system
                // The result will be delivered to activity.peerListListener
                manager.requestPeers(channel, activity.peerListListener)
            }

            // ========== CONNECTION STATE CHANGED ==========
            /**
             * Broadcast sent when WiFi P2P connection status changes
             * This happens when:
             * - A connection to a peer is established
             * - A connection to a peer is lost
             * - Connection attempt fails
             * - Group formation completes
             */
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "P2P connection changed")

                // Request current connection information from the system
                // Using modern approach instead of deprecated NetworkInfo
                manager.requestConnectionInfo(channel) { info ->
                    if (info.groupFormed) {
                        // Successfully connected to a peer and group is formed
                        // The 'info' object contains details about:
                        // - Whether this device is the group owner (host)
                        // - The IP address of the group owner
                        // - Group formation status
                        activity.onWifiP2pConnected(info)
                    } else {
                        // No active connection or group was disbanded
                        // This can happen when:
                        // - Connection is lost
                        // - User manually disconnects
                        // - Connection attempt fails
                        activity.onWifiP2pDisconnected()
                    }
                }
            }
        }
    }
}