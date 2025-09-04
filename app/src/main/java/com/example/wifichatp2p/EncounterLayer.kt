package com.example.wifichatp2p

import android.util.Log
import android.os.Build
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    private val cooldownPeriodMs: Long = 5000L, // 5 seconds cooldown by default
    private val initDelayMs: Long = DeviceUtils.getAsapInitDelay()
) {

    companion object {
        private const val TAG = "EncounterLayer"
        private val SUPPORTED_FORMATS = setOf("chat", "p2p-message") // ASAP message formats
    }

    private val deviceTag = "${TAG}_${Build.MANUFACTURER}_${Build.MODEL}"

    // ASAP components
    private var asapConnectionHandler: ASAPConnectionHandler? = null
    private var encounterManager: ASAPEncounterManager? = null

    // Connection tracking
    private val activeConnections = ConcurrentHashMap<String, Long>()
    private val isInitialized = AtomicBoolean(false)
    private val initializationMutex = kotlinx.coroutines.sync.Mutex()
    private var initializationJob: Job? = null

    /**
     * Initialize the ASAP peer and encounter manager
     * This should be called once during app startup
     * Now includes device-specific delays and thread-safe initialization
     */
    suspend fun initialize(): Boolean {
        return initializationMutex.withLock {
            if (isInitialized.get()) {
                Log.d(deviceTag, "EncounterLayer already initialized")
                return@withLock true
            }

            try {
                Log.d(deviceTag, "Initializing ASAP EncounterLayer for peer: $peerId")
                Log.d(deviceTag, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                Log.d(deviceTag, "Storage folder: ${storageFolder.absolutePath}")
                Log.d(deviceTag, "Cooldown period: ${cooldownPeriodMs}ms")
                Log.d(deviceTag, "Init delay: ${initDelayMs}ms")

                // Device-specific initialization delay
                if (initDelayMs > 0) {
                    Log.d(deviceTag, "Applying device-specific initialization delay: ${initDelayMs}ms")
                    delay(initDelayMs)
                }

                // Ensure storage folder exists
                if (!storageFolder.exists()) {
                    val created = storageFolder.mkdirs()
                    if (created) {
                        Log.d(deviceTag, "Created storage folder: ${storageFolder.absolutePath}")
                    } else {
                        Log.e(deviceTag, "Failed to create storage folder: ${storageFolder.absolutePath}")
                        return@withLock false
                    }
                }

                // Validate storage folder permissions
                if (!storageFolder.canRead() || !storageFolder.canWrite()) {
                    Log.e(deviceTag, "Storage folder permissions insufficient: ${storageFolder.absolutePath}")
                    return@withLock false
                }

                Log.d(deviceTag, "Creating ASAP peer with formats: $SUPPORTED_FORMATS")

                // Create ASAP connection handler (peer) with error handling
                asapConnectionHandler = try {
                    ASAPPeerFS(peerId, storageFolder.absolutePath, SUPPORTED_FORMATS)
                } catch (e: Exception) {
                    Log.e(deviceTag, "Failed to create ASAP peer: ${e.message}")
                    e.printStackTrace()
                    return@withLock false
                }

                Log.d(deviceTag, "ASAP peer created successfully")

                // Create encounter manager with cooldown period
                encounterManager = try {
                    ASAPEncounterManagerImpl(asapConnectionHandler, peerId, cooldownPeriodMs)
                } catch (e: Exception) {
                    Log.e(deviceTag, "Failed to create ASAP EncounterManager: ${e.message}")
                    e.printStackTrace()
                    return@withLock false
                }

                Log.d(deviceTag, "ASAP EncounterManager created with cooldown: ${cooldownPeriodMs}ms")

                // Additional device-specific validation for Pixel devices
                if (DeviceUtils.getDeviceManufacturer() == DeviceUtils.DeviceManufacturer.GOOGLE_PIXEL) {
                    Log.d(deviceTag, "Performing Pixel-specific ASAP validation")

                    // Add small delay for Pixel devices to ensure proper initialization
                    delay(100)

                    // Validate that the encounter manager is properly initialized
                    if (encounterManager == null) {
                        Log.e(deviceTag, "Pixel validation failed: EncounterManager is null")
                        return@withLock false
                    }
                }

                isInitialized.set(true)
                Log.i(deviceTag, "EncounterLayer initialization completed successfully")
                true

            } catch (e: Exception) {
                Log.e(deviceTag, "Failed to initialize EncounterLayer: ${e.message}")
                e.printStackTrace()

                // Clean up partial initialization
                cleanup()
                false
            }
        }
    }

    /**
     * Initialize the EncounterLayer asynchronously in a background thread
     * This is the main entry point for initialization from MainActivity
     */
    fun initializeAsync(callback: (Boolean) -> Unit) {
        if (isInitialized.get()) {
            Log.d(deviceTag, "EncounterLayer already initialized")
            callback(true)
            return
        }

        initializationJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val success = initialize()
                withContext(Dispatchers.Main) {
                    callback(success)
                }
            } catch (e: Exception) {
                Log.e(deviceTag, "Async initialization failed: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    callback(false)
                }
            }
        }
    }

    /**
     * Check if the EncounterLayer is properly initialized
     */
    fun isReady(): Boolean {
        return isInitialized.get() && encounterManager != null
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
        if (!isInitialized.get()) {
            Log.w(deviceTag, "EncounterLayer not initialized, allowing connection by default")
            return true
        }

        return try {
            Log.d(deviceTag, "Checking if connection should be created to peer: $peerDeviceAddress")
            Log.d(deviceTag, "Connection type: $connectionType")

            // Check with ASAP EncounterManager
            val shouldConnect = encounterManager?.shouldCreateConnectionToPeer(peerDeviceAddress, connectionType) ?: true

            Log.i(deviceTag, "EncounterManager decision for $peerDeviceAddress: $shouldConnect")

            if (shouldConnect) {
                Log.d(deviceTag, "Connection approved - no recent encounter with $peerDeviceAddress")
            } else {
                Log.d(deviceTag, "Connection rejected - recent encounter exists with $peerDeviceAddress (cooldown active)")
            }

            shouldConnect

        } catch (e: Exception) {
            Log.e(deviceTag, "Error checking connection decision: ${e.message}")
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
        if (!isInitialized.get()) {
            Log.w(deviceTag, "EncounterLayer not initialized, skipping encounter handling")
            return
        }

        try {
            Log.d(deviceTag, "Handling new encounter with peer: $peerDeviceAddress")
            Log.d(deviceTag, "Connection type: $connectionType")
            Log.d(deviceTag, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")

            // Create StreamPair from the input/output streams
            val streamPair = try {
                StreamPairImpl.getStreamPair(inputStream, outputStream)
            } catch (e: Exception) {
                Log.e(deviceTag, "Failed to create StreamPair: ${e.message}")
                e.printStackTrace()
                return
            }

            Log.d(deviceTag, "StreamPair created successfully")

            // Optional: Set up idle connection closer (closes connection if idle for 30 seconds)
            val idleCloser = try {
                IdleStreamPairCloser.getIdleStreamsCloser(streamPair, 30000)
            } catch (e: Exception) {
                Log.w(deviceTag, "Failed to create idle stream closer: ${e.message}")
                null
            }

            idleCloser?.start()
            Log.d(deviceTag, "Idle stream closer started (30s timeout)")

            // Notify encounter manager about the new encounter
            try {
                encounterManager?.handleEncounter(streamPair, connectionType)
                Log.d(deviceTag, "EncounterManager.handleEncounter() completed successfully")
            } catch (e: Exception) {
                Log.e(deviceTag, "EncounterManager.handleEncounter() failed: ${e.message}")
                e.printStackTrace()
                // Continue with connection tracking even if ASAP handling fails
            }

            // Track the connection locally
            activeConnections[peerDeviceAddress] = System.currentTimeMillis()

            Log.i(deviceTag, "Encounter handling completed for peer: $peerDeviceAddress")
            Log.d(deviceTag, "Active connections count: ${activeConnections.size}")

        } catch (e: Exception) {
            Log.e(deviceTag, "Error handling encounter with $peerDeviceAddress: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Clean up resources when a connection is closed
     *
     * @param peerDeviceAddress The identifier of the disconnected peer
     */
    fun onConnectionClosed(peerDeviceAddress: String) {
        Log.d(deviceTag, "Connection closed with peer: $peerDeviceAddress")

        // Remove from active connections
        activeConnections.remove(peerDeviceAddress)
        Log.d(deviceTag, "Removed $peerDeviceAddress from active connections")
        Log.d(deviceTag, "Remaining active connections: ${activeConnections.size}")
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
        Log.d(deviceTag, "Cleaning up EncounterLayer resources")

        try {
            // Cancel any ongoing initialization
            initializationJob?.cancel()

            activeConnections.clear()

            // Reset initialization state
            isInitialized.set(false)

            // Clear references
            asapConnectionHandler = null
            encounterManager = null

            // Note: ASAPEncounterManager doesn't have explicit cleanup method
            // The underlying resources will be garbage collected

            Log.i(deviceTag, "EncounterLayer cleanup completed")

        } catch (e: Exception) {
            Log.e(deviceTag, "Error during cleanup: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Get current status for debugging and monitoring
     */
    fun getStatus(): String {
        return if (isInitialized.get()) {
            "Initialized - Peer: $peerId, Cooldown: ${cooldownPeriodMs}ms, Active: ${activeConnections.size}"
        } else {
            "Not initialized"
        }
    }
}