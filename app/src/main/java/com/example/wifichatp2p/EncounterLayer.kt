package com.example.wifichatp2p

import android.util.Log
import net.sharksystem.asap.ASAPConnectionHandler
import net.sharksystem.asap.ASAPEncounterManager
import net.sharksystem.asap.ASAPEncounterManagerImpl
import net.sharksystem.asap.ASAPPeerFS
import net.sharksystem.asap.ASAPEncounterConnectionType
import net.sharksystem.utils.streams.StreamPair
import net.sharksystem.utils.streams.StreamPairImpl
import net.sharksystem.utils.streams.IdleStreamPairCloser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * EncounterLayer wraps the ASAP EncounterManager functionality
 * and provides a clean interface for the WiFi Direct P2P chat app.
 *
 * This class handles:
 * - ASAP peer initialization
 * - Encounter management decisions
 * - Connection tracking and cooldown periods
 * - Background operation coordination
 * - Comprehensive logging for debugging
 */
class EncounterLayer(
    private val peerId: String,
    private val storageFolder: File,
    private val cooldownPeriodMs: Long = 5000L // 5 seconds cooldown by default
) {

    companion object {
        private const val TAG = "EncounterMgr"
        private val SUPPORTED_FORMATS = setOf("chat", "p2p-message") // ASAP message formats
    }

    // ASAP components
    private var asapConnectionHandler: ASAPConnectionHandler? = null
    private var encounterManager: ASAPEncounterManager? = null

    // Connection tracking
    private val activeConnections = ConcurrentHashMap<String, Long>()
    private var isInitialized = false

    /**
     * Initialize the ASAP peer and encounter manager
     * This should be called once during app startup
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Initializing ASAP EncounterLayer for peer: $peerId")
            Log.d(TAG, "Storage folder: ${storageFolder.absolutePath}")
            Log.d(TAG, "Cooldown period: ${cooldownPeriodMs}ms")

            // Ensure storage folder exists
            if (!storageFolder.exists()) {
                storageFolder.mkdirs()
                Log.d(TAG, "Created storage folder: ${storageFolder.absolutePath}")
            }

            // Create ASAP connection handler (peer)
            asapConnectionHandler = ASAPPeerFS(peerId, storageFolder.absolutePath, SUPPORTED_FORMATS)
            Log.d(TAG, "ASAP peer created successfully")

            // Create encounter manager with cooldown period
            encounterManager = ASAPEncounterManagerImpl(asapConnectionHandler, peerId, cooldownPeriodMs)
            Log.d(TAG, "ASAP EncounterManager created with cooldown: ${cooldownPeriodMs}ms")

            isInitialized = true
            Log.i(TAG, "EncounterLayer initialization completed successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncounterLayer: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Check if a connection should be established to the given peer
     * This is the main decision point that prevents redundant connections
     *
     * @param peerDeviceAddress The MAC address or identifier of the target peer
     * @param connectionType The type of connection (WiFi Direct in our case)
     * @return true if connection should be established, false otherwise
     */
    fun shouldCreateConnection(peerDeviceAddress: String, connectionType: ASAPEncounterConnectionType = ASAPEncounterConnectionType.AD_HOC_LAYER_2_NETWORK): Boolean {
        if (!isInitialized) {
            Log.w(TAG, "EncounterLayer not initialized, allowing connection by default")
            return true
        }

        return try {
            Log.d(TAG, "Checking if connection should be created to peer: $peerDeviceAddress")
            Log.d(TAG, "Connection type: $connectionType")

            // Check with ASAP EncounterManager
            val shouldConnect = encounterManager?.shouldCreateConnectionToPeer(peerDeviceAddress, connectionType) ?: true

            Log.i(TAG, "EncounterManager decision for $peerDeviceAddress: $shouldConnect")

            if (shouldConnect) {
                Log.d(TAG, "Connection approved - no recent encounter with $peerDeviceAddress")
            } else {
                Log.d(TAG, "Connection rejected - recent encounter exists with $peerDeviceAddress (cooldown active)")
            }

            shouldConnect

        } catch (e: Exception) {
            Log.e(TAG, "Error checking connection decision: ${e.message}")
            e.printStackTrace()
            // Default to allowing connection on error
            true
        }
    }

    /**
     * Handle a new encounter (established connection)
     * This notifies the EncounterManager about the active connection
     *
     * @param inputStream The input stream of the established connection
     * @param outputStream The output stream of the established connection
     * @param peerDeviceAddress The identifier of the connected peer
     * @param connectionType The type of connection
     */
    fun handleEncounter(
        inputStream: InputStream,
        outputStream: OutputStream,
        peerDeviceAddress: String,
        connectionType: ASAPEncounterConnectionType = ASAPEncounterConnectionType.AD_HOC_LAYER_2_NETWORK
    ) {
        if (!isInitialized) {
            Log.w(TAG, "EncounterLayer not initialized, skipping encounter handling")
            return
        }

        try {
            Log.d(TAG, "Handling new encounter with peer: $peerDeviceAddress")
            Log.d(TAG, "Connection type: $connectionType")

            // Create StreamPair from the input/output streams
            val streamPair = StreamPairImpl.getStreamPair(inputStream, outputStream)
            Log.d(TAG, "StreamPair created successfully")

            // Optional: Set up idle connection closer (closes connection if idle for 30 seconds)
            val idleCloser = IdleStreamPairCloser.getIdleStreamsCloser(streamPair, 30000)
            idleCloser.start()
            Log.d(TAG, "Idle stream closer started (30s timeout)")

            // Notify encounter manager about the new encounter
            encounterManager?.handleEncounter(streamPair, connectionType)

            // Track the connection locally
            activeConnections[peerDeviceAddress] = System.currentTimeMillis()

            Log.i(TAG, "Encounter handling completed for peer: $peerDeviceAddress")
            Log.d(TAG, "Active connections count: ${activeConnections.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Error handling encounter with $peerDeviceAddress: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Clean up resources when a connection is closed
     *
     * @param peerDeviceAddress The identifier of the disconnected peer
     */
    fun onConnectionClosed(peerDeviceAddress: String) {
        Log.d(TAG, "Connection closed with peer: $peerDeviceAddress")

        // Remove from active connections
        activeConnections.remove(peerDeviceAddress)
        Log.d(TAG, "Removed $peerDeviceAddress from active connections")
        Log.d(TAG, "Remaining active connections: ${activeConnections.size}")
    }

    /**
     * Get information about active connections for debugging
     */
    fun getActiveConnectionsInfo(): String {
        val currentTime = System.currentTimeMillis()
        val info = StringBuilder()

        info.append("Active Connections (${activeConnections.size}):\n")
        activeConnections.forEach { (peer, timestamp) ->
            val duration = currentTime - timestamp
            info.append("  - $peer: ${duration}ms ago\n")
        }

        return info.toString()
    }

    /**
     * Force cleanup of all resources
     * Should be called when the app is shutting down
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up EncounterLayer resources")

        try {
            activeConnections.clear()
            // Note: ASAPEncounterManager doesn't have explicit cleanup method
            // The underlying resources will be garbage collected

            Log.i(TAG, "EncounterLayer cleanup completed")

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Get current status for debugging and monitoring
     */
    fun getStatus(): String {
        return if (isInitialized) {
            "Initialized - Peer: $peerId, Cooldown: ${cooldownPeriodMs}ms, Active: ${activeConnections.size}"
        } else {
            "Not initialized"
        }
    }
}