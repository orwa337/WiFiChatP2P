package com.example.wifichatp2p

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.net.SocketTimeoutException
import java.net.NetworkInterface
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PORT = 8888 // Port number for socket communication
    }

    // ========== UI ELEMENTS ==========
    lateinit var textViewConnectionStatus: TextView // Shows connection status
    lateinit var listViewMessage: ListView          // Shows sent/received messages
    lateinit var buttonSwitchWifi: Button           // Opens WiFi settings
    lateinit var buttonDiscover: Button             // Starts peer discovery
    lateinit var buttonDisconnect: Button           // Disconnects from peer
    lateinit var listViewPeers: ListView            // Lists discovered peers
    lateinit var editTextMessage: EditText          // Input field for messages
    lateinit var imageButtonSend: ImageButton       // Send message button

    // ========== MESSAGE ADAPTER ==========
    lateinit var messageAdapter: MessageAdapter     // Adapter for message ListView
    private val messages = ArrayList<String>()       // List to store all chat messages

    // ========== WIFI P2P VARIABLES ==========
    lateinit var manager: WifiP2pManager            // Main WiFi P2P manager
    lateinit var channel: WifiP2pManager.Channel    // Communication channel
    lateinit var receiver: BroadcastReceiver        // Receives WiFi P2P broadcasts
    lateinit var intentFilter: IntentFilter         // Filters for specific broadcasts

    // ========== DEVICE LISTS ==========
    val peers = ArrayList<WifiP2pDevice>()          // List of discovered peers
    lateinit var devicesNames: Array<String>        // Array of device names for ListView
    lateinit var devices: Array<WifiP2pDevice>      // Array of actual device objects

    // ========== CONNECTION VARIABLES ==========
    var isHost = false                                      // True if this device is the group owner
    private var socket: Socket? = null                      // Socket for communication
    private var serverSocket: ServerSocket? = null          // Server socket (for host only)
    private var outputWriter: PrintWriter? = null           // Writer for sending data
    private var inputReader: BufferedReader? = null         // Reader for receiving data (legacy - will be replaced)
    private var inputStream: InputStream? = null            // Direct input stream for immediate reading
    private var isConnected = false                         // True if connected to a peer

    // ========== UI HANDLER ==========
    val handler = Handler(Looper.getMainLooper())           // Handler for updating UI from background threads

    // ========== POWER MANAGEMENT ==========
    private lateinit var wakeLock: PowerManager.WakeLock    // Keeps device awake during connection

    // ========== NETWORK INTERFACE MANAGEMENT ==========
    private var wifiDirectInterface: NetworkInterface? = null
    private var wifiDirectAddress: InetAddress? = null

    /**
     * Called when the activity is first created
     * Sets up the UI, WiFi P2P, and checks system requirements
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        initializeViews()                       // Set up UI elements
        setupWifiP2p()                          // Initialize WiFi P2P components
        setupButtonListeners()                  // Set up button click handlers
        checkWifiAndLocation()                  // Check if WiFi and location are enabled
        requestBatteryOptimizationExemption()   // Request to ignore battery optimization
    }

    /**
     * Initialize all UI elements by finding them in the layout
     */
    private fun initializeViews() {
        textViewConnectionStatus = findViewById(R.id.textViewConnectionStatus)
        listViewMessage = findViewById(R.id.listViewMessage)
        buttonSwitchWifi = findViewById(R.id.buttonSwitchWifi)
        buttonDiscover = findViewById(R.id.buttonDiscover)
        buttonDisconnect = findViewById(R.id.buttonDisconnect)
        listViewPeers = findViewById(R.id.listViewPeers)
        editTextMessage = findViewById(R.id.editTextMessage)
        imageButtonSend = findViewById(R.id.imageButtonSend)

        // Initialize message adapter and set it to the ListView
        messageAdapter = MessageAdapter(this, messages)
        listViewMessage.adapter = messageAdapter
    }

    /**
     * Set up WiFi P2P manager, channel, and broadcast receiver
     */
    private fun setupWifiP2p() {
        // Get the WiFi P2P system service
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager

        // Initialize communication channel
        channel = manager.initialize(this, mainLooper, null)

        // Create broadcast receiver to handle WiFi P2P events
        receiver = WifiDirectBroadcastReceiver(manager, channel, this)

        // Set up intent filter to listen for specific WiFi P2P broadcasts
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)         // WiFi P2P enabled/disabled
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)         // Peer list changed
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)    // Connection status changed
        }
    }

    /**
     * Set up click listeners for all buttons and UI elements
     */
    private fun setupButtonListeners() {
        // WiFi settings button - opens system WiFi settings
        buttonSwitchWifi.setOnClickListener {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        }

        // Discover button - starts scanning for nearby peers
        buttonDiscover.setOnClickListener {
            listViewPeers.adapter = null
            discoverPeers()
        }

        // Disconnect button - disconnects from the current peer
        buttonDisconnect.setOnClickListener {
            // Clear peers and chat
            messages.clear()
            disconnect()
        }

        // Peer list item click - connects to selected peer
        listViewPeers.setOnItemClickListener { _, _, position, _ ->
            connectToDevice(position)
        }

        // Send button - sends the typed message
        imageButtonSend.setOnClickListener {
            val message = editTextMessage.text.toString()
            // Validate message is not empty
            if (message.isEmpty()) {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Send message in background thread to avoid blocking UI
            thread(start = true) {
                try {
                    sendMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Send message error: ${e.message}")
                    e.printStackTrace()
                    // Update UI on main thread
                    handler.post {
                        Toast.makeText(this@MainActivity,
                            "Error sending message: ${e.message}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Check if WiFi and Location services are enabled
     * Show dialogs to enable them if they're disabled
     */
    private fun checkWifiAndLocation() {
        // Check WiFi status
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            AlertDialog.Builder(this)
                .setTitle("WiFi is disabled")
                .setMessage("You need to enable WiFI to connect with other devices.")
                .setPositiveButton("Enable it") { _, _ ->
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                    startActivity(intent)
                }
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .show()
        }

        // Check Location service status (required for WiFi P2P discovery)
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isLocationEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Location service is disabled")
                .setMessage("You need to enable Location service to find nearby devices.")
                .setNeutralButton("ENABLE IT") { _, _ ->
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(intent)
                }
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .show()
        }
    }

    /**
     * Request exemption from battery optimization to maintain connection
     */
    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)

    }

    /**
     * Start discovering nearby WiFi P2P peers
     */
    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateStatus("Discovery started")
            }

            override fun onFailure(reason: Int) {
                updateStatus("Discovery failed. Reason: $reason")
            }
        })
    }

    /**
     * Connect to a selected peer device
     * @param position Index of the device in the peers list
     */
    @SuppressLint("MissingPermission")
    private fun connectToDevice(position: Int) {
        val device = devices[position]
        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateStatus("Connecting to: ${device.deviceAddress}")
            }

            override fun onFailure(reason: Int) {
                updateStatus("Connection failed. Reason: $reason")
            }
        })
    }

    /**
     * Update the connection status display
     * Called from both main thread and broadcast receiver
     */
    fun updateStatus(status: String) {
        handler.post {
            textViewConnectionStatus.text = status
            Log.d(TAG, "Status: $status")
        }
    }

    /**
     * Find and configure the WiFi Direct network interface
     * This ensures proper socket binding for P2P communication
     */
    private fun configureWifiDirectInterface(info: WifiP2pInfo) {
        try {
            // Reset previous interface info
            wifiDirectInterface = null
            wifiDirectAddress = null

            // Get all network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Look for WiFi Direct interface (typically p2p-wlan0-X or similar)
                if (networkInterface.name.contains("p2p") ||
                    networkInterface.displayName.contains("p2p") ||
                    networkInterface.name.contains("wlan") && networkInterface.isUp) {

                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()

                        // Look for IPv4 address in WiFi Direct range (192.168.49.x)
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            val hostAddr = address.hostAddress

                            // WiFi Direct typically uses 192.168.49.x subnet
                            if (hostAddr?.startsWith("192.168.49.") == true) {
                                wifiDirectInterface = networkInterface
                                wifiDirectAddress = address
                                Log.d(TAG, "Found WiFi Direct interface: ${networkInterface.name} with IP: $hostAddr")
                                return
                            }
                        }
                    }
                }
            }

            // Fallback: if we're the group owner, use the group owner address
            if (info.isGroupOwner && info.groupOwnerAddress != null) {
                wifiDirectAddress = info.groupOwnerAddress
                Log.d(TAG, "Using group owner address as fallback: ${info.groupOwnerAddress.hostAddress}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring WiFi Direct interface: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Handle successful WiFi P2P connection
     * Determines if this device is host or client and starts appropriate role
     */
    fun onWifiP2pConnected(info: WifiP2pInfo) {
        updateStatus("Connected to peer")

        // Clean up any existing connections before starting new one
        closeConnection()

        // Configure WiFi Direct network interface for proper socket binding
        configureWifiDirectInterface(info)

        Log.d(TAG, "WiFi P2P Connection Info:")
        Log.d(TAG, "  Group Formed: ${info.groupFormed}")
        Log.d(TAG, "  Is Group Owner: ${info.isGroupOwner}")
        Log.d(TAG, "  Group Owner Address: ${info.groupOwnerAddress?.hostAddress}")

        if (info.groupFormed && info.isGroupOwner) {
            // This device is the group owner (host)
            isHost = true
            updateStatus("Host")

            // Start server in background thread
            thread(start = true) {
                startServer()
            }
        } else if (info.groupFormed) {
            // This device is a client
            isHost = false
            updateStatus("Client")

            // Connect to the group owner (host)
            val hostAddress = info.groupOwnerAddress
            thread(start = true) {
                connectToServer(hostAddress)
            }
        }
    }

    /**
     * Handle WiFi P2P disconnection
     * Clean up all connection resources
     */
    fun onWifiP2pDisconnected() {
        updateStatus("Disconnected from peer")

        // Clean up resources
        closeConnection()
    }

    /**
     * Listener for when the peer list is updated
     * Updates the UI with discovered devices
     */
    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val deviceList = peerList.deviceList
        if (deviceList.isNotEmpty()) {
            // Clear old peers and add new ones
            peers.clear()
            peers.addAll(deviceList)

            // Create arrays for ListView adapter
            devicesNames = Array(deviceList.size) { i ->
                deviceList.elementAt(i).deviceName ?: "Unknown Device"
            }

            devices = deviceList.toTypedArray()

            // Update ListView on main thread
            handler.post {
                val adapter = ArrayAdapter(applicationContext,
                    android.R.layout.simple_list_item_1, devicesNames)
                listViewPeers.adapter = adapter
            }
        } else {
            updateStatus("No devices found")
        }
    }

    /**
     * Start server socket and wait for client connection
     * Called when this device is the group owner (host)
     * Now properly binds to WiFi Direct interface for reliable P2P communication
     */
    private fun startServer() {
        try {
            // Create server socket with explicit binding to WiFi Direct interface
            serverSocket = if (wifiDirectAddress != null) {
                // Bind to specific WiFi Direct interface address
                Log.d(TAG, "Binding server to WiFi Direct address: ${wifiDirectAddress?.hostAddress}")
                ServerSocket(PORT, 50, wifiDirectAddress)
            } else {
                // Fallback to default binding (listen on all interfaces)
                Log.d(TAG, "Using default server socket binding")
                ServerSocket(PORT)
            }

            // Set socket options for better P2P performance
            serverSocket?.reuseAddress = true
            serverSocket?.soTimeout = 0  // No timeout for accept() - wait indefinitely

            val bindAddress = serverSocket?.inetAddress?.hostAddress ?: "unknown"
            updateStatus("Server listening on: $bindAddress:$PORT")
            Log.d(TAG, "Server socket bound to: $bindAddress:$PORT")

            // Wait for client to connect (blocking call)
            val client = serverSocket?.accept()

            if (client != null) {
                socket = client

                // Configure client socket for optimal P2P communication with minimal buffering
                socket?.tcpNoDelay = true  // Disable Nagle's algorithm for immediate sending
                socket?.keepAlive = true   // Enable keep-alive packets
                socket?.soTimeout = 0      // No read timeout for continuous listening

                // Reduce socket buffer sizes to minimize buffering delays
                socket?.receiveBufferSize = 4096  // 4KB receive buffer
                socket?.sendBufferSize = 4096     // 4KB send buffer

                setupStreams()  // Initialize input/output streams

                val clientAddress = client.inetAddress.hostAddress
                updateStatus("Client connected: $clientAddress")
                Log.d(TAG, "Client connected from: $clientAddress")

                // Start listening for incoming messages
                listenForMessages()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Server error: ${e.message}")
            e.printStackTrace()
            updateStatus("Server error: ${e.message}")
        }
    }

    /**
     * Connect to the host server
     * Called when this device is a client
     * Now includes proper socket configuration for reliable P2P communication
     */
    private fun connectToServer(hostAddress: InetAddress) {
        try {
            Log.d(TAG, "Attempting to connect to server: ${hostAddress.hostAddress}:$PORT")

            // Create client socket with proper configuration
            socket = Socket()

            // Configure socket options before connecting
            socket?.reuseAddress = true
            socket?.tcpNoDelay = true  // Disable Nagle's algorithm for immediate sending
            socket?.keepAlive = true   // Enable keep-alive packets

            // If we have a WiFi Direct interface, bind the client socket to it
            if (wifiDirectAddress != null && !isHost) {
                try {
                    // Bind client socket to WiFi Direct interface for proper routing
                    socket?.bind(InetSocketAddress(wifiDirectAddress, 0))
                    Log.d(TAG, "Client socket bound to WiFi Direct interface: ${wifiDirectAddress?.hostAddress}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not bind client socket to WiFi Direct interface: ${e.message}")
                    // Continue without binding - fallback to default routing
                }
            }

            // Connect to the host with timeout
            socket?.connect(InetSocketAddress(hostAddress, PORT), 10000) // 10 second timeout

            if (socket?.isConnected == true) {
                // Configure socket after successful connection for immediate message delivery
                socket?.soTimeout = 0      // No read timeout for continuous listening
                socket?.tcpNoDelay = true  // Ensure TCP_NODELAY is set after connection

                // Reduce socket buffer sizes to minimize buffering delays
                socket?.receiveBufferSize = 4096  // 4KB receive buffer
                socket?.sendBufferSize = 4096     // 4KB send buffer

                setupStreams()  // Initialize input/output streams
                updateStatus("Connected to server: ${hostAddress.hostAddress}")
                Log.d(TAG, "Successfully connected to server: ${hostAddress.hostAddress}:$PORT")

                // Start listening for incoming messages
                listenForMessages()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Client connection error: ${e.message}")
            e.printStackTrace()
            updateStatus("Connection error: ${e.message}")
        }
    }

    /**
     * Initialize input and output streams for socket communication
     * Using direct InputStream reading to eliminate BufferedReader delays
     * This eliminates all buffering delays for immediate message delivery
     */
    private fun setupStreams() {
        try {
            Log.d(TAG, "Setting up direct streams for immediate socket communication")

            // Create PrintWriter for sending messages (auto-flush enabled)
            outputWriter = PrintWriter(socket?.getOutputStream(), true)

            // Use direct InputStream instead of BufferedReader to avoid buffering delays
            inputStream = socket?.getInputStream()

            // Keep BufferedReader as fallback (but we'll use direct InputStream in listener)
            inputReader = BufferedReader(InputStreamReader(socket?.getInputStream()))
            isConnected = true

            Log.d(TAG, "Direct streams initialized successfully - ready for immediate communication")
        } catch (e: IOException) {
            Log.e(TAG, "Stream setup error: ${e.message}")
            e.printStackTrace()
            closeConnection()
        }
    }

    /**
     * Listen for incoming messages using direct InputStream reading
     * Eliminates BufferedReader delays for immediate message delivery
     * Uses high-priority thread for minimal latency
     */
    @SuppressLint("SetTextI18n")
    private fun listenForMessages() {
        // Start a high-priority dedicated thread for immediate message listening
        thread(start = true) {
            // Set high thread priority for immediate message processing
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.d(TAG, "Starting high-priority message listener thread with direct InputStream")

            // Buffer for reading bytes directly from InputStream
            val buffer = ByteArray(1024)
            val messageBuilder = StringBuilder()

            while (isConnected && socket?.isConnected == true) {
                try {
                    // Add timestamp for latency measurement
                    val receiveStartTime = System.currentTimeMillis()

                    // Read bytes directly from InputStream (no buffering delays)
                    val bytesRead = inputStream?.read(buffer)

                    if (bytesRead != null && bytesRead > 0) {
                        // Convert bytes to string immediately
                        val chunk = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                        messageBuilder.append(chunk)

                        // Process complete messages (ending with newline)
                        var newlineIndex = messageBuilder.indexOf('\n')
                        while (newlineIndex != -1) {
                            val message = messageBuilder.substring(0, newlineIndex).trim()
                            messageBuilder.delete(0, newlineIndex + 1)

                            if (message.isNotEmpty()) {
                                val receiveEndTime = System.currentTimeMillis()
                                val latency = receiveEndTime - receiveStartTime
                                Log.d(TAG, "Received message (${latency}ms latency): $message")

                                // Update UI immediately on main thread
                                handler.post {
                                    messages.add("them: $message")
                                    messageAdapter.notifyDataSetChanged()
                                    listViewMessage.setSelection(messageAdapter.count - 1)
                                }
                            }

                            newlineIndex = messageBuilder.indexOf('\n')
                        }
                    } else if (bytesRead == -1) {
                        // End of stream - connection closed by peer
                        Log.d(TAG, "End of stream - connection closed by peer")
                        break
                    }

                } catch (e: IOException) {
                    // IOException usually means connection was closed
                    Log.d(TAG, "Connection closed: ${e.message}")
                    break

                } catch (e: Exception) {
                    // Handle any other unexpected errors
                    Log.e(TAG, "Unexpected error in message listener: ${e.message}")
                    e.printStackTrace()
                    break
                }
            }

            // Listening loop has ended
            Log.d(TAG, "High-priority message listener thread ending")

            // If we're still supposed to be connected, this is an unexpected disconnection
            if (isConnected) {
                handler.post {
                    Toast.makeText(this@MainActivity, "Connection lost", Toast.LENGTH_SHORT).show()
                }
                closeConnection()
            }
        }
    }

    /**
     * Send a text message to the connected peer with immediate delivery
     * Enhanced with latency measurement and forced flushing to eliminate delays
     * @param message The text message to send
     */
    @SuppressLint("SetTextI18n")
    private fun sendMessage(message: String) {
        if (!isConnected || outputWriter == null) {
            Log.w(TAG, "Cannot send message - not connected or no output stream")
            handler.post {
                Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val sendStartTime = System.currentTimeMillis()
            Log.d(TAG, "Sending message with timestamp: $message")

            // Send the message using PrintWriter with immediate flushing
            outputWriter?.println(message)
            outputWriter?.flush()  // Force immediate flush to eliminate send buffering

            // Also flush the underlying OutputStream to ensure immediate network delivery
            socket?.getOutputStream()?.flush()

            val sendEndTime = System.currentTimeMillis()
            val sendLatency = sendEndTime - sendStartTime
            Log.d(TAG, "Message sent successfully (${sendLatency}ms): $message")

            // Update UI on main thread
            handler.post {
                // Add sent message to the ListView
                messages.add("you: $message")
                messageAdapter.notifyDataSetChanged()
                // Auto-scroll to the newest message
                listViewMessage.setSelection(messageAdapter.count - 1)
                editTextMessage.text.clear()  // Clear input field
            }

        } catch (e: IOException) {
            Log.e(TAG, "Send error: ${e.message}")
            e.printStackTrace()

            // Show error to user
            handler.post {
                Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            // Close connection on send failure
            closeConnection()
        }
    }

    /**
     * Close all connection resources and reset connection state
     * Safe to call multiple times
     * Enhanced to clean up WiFi Direct interface references
     */
    private fun closeConnection() {
        Log.d(TAG, "Closing connection and cleaning up resources")
        isConnected = false

        try {
            // Close all streams and sockets
            socket?.close()
            serverSocket?.close()
            outputWriter?.close()
            inputReader?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Close error: ${e.message}")
        } finally {
            // Reset all connection variables
            isHost = false
            socket = null
            serverSocket = null
            outputWriter = null
            inputReader = null
            isConnected = false

            // Clean up WiFi Direct interface references
            wifiDirectInterface = null
            wifiDirectAddress = null

            Log.d(TAG, "All connection resources cleaned up")
        }

        // Update status display
        updateStatus(if (isHost) "Host (disconnected)" else "Client (disconnected)")

    }

    private fun disconnect() {

        manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Disconnect successful
                Log.d(TAG, "cancelConnect")
            }
            override fun onFailure(reason: Int) {
                // Handle failure
                Log.d(TAG, "Failure cancelConnect")
            }
        })
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroup")
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "Failure removeGroup")
            }
        })
        closeConnection()
    }

    /**
     * Disable WiFi power saving to maintain stable connection
     * Helps prevent connection drops due to power management
     */
    private fun disableWifiPowerSaving() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MyApp:WifiLock")
        wifiLock.acquire()
    }

    // ========== ACTIVITY LIFECYCLE METHODS ==========

    /**
     * Register broadcast receiver when activity becomes visible
     */
    @SuppressLint("RegisterReceiverForAllUsers", "UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    /**
     * Unregister broadcast receiver when activity is no longer visible
     */
    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    /**
     * Clean up resources when activity is destroyed
     */
    override fun onDestroy() {
        super.onDestroy()
        disableWifiPowerSaving()
        closeConnection()
    }
}