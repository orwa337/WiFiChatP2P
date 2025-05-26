package com.example.wifichatp2p

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.annotation.RequiresPermission

class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    private val TAG = "WiFiDirectBR"

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    activity.updateStatus("WiFi P2P is enabled")
                } else {
                    activity.updateStatus("WiFi P2P is disabled")
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "P2P peers changed")
                manager.requestPeers(channel, activity.peerListListener)
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "P2P connection changed")

                // Instead of using the deprecated NetworkInfo, directly request connection info
                manager.requestConnectionInfo(channel) { info ->
                    if (info.groupFormed) {
                        // Connected to a peer
                        activity.onWifiP2pConnected(info)
                    } else {
                        // Disconnected from peer
                        activity.onWifiP2pDisconnected()
                    }
                }
            }
        }
    }
}