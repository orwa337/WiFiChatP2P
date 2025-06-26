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
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PORT = 8888 // Port number for socket communication
    }

    // ========== UI ELEMENTS ==========
    lateinit var textViewConnectionStatus: TextView // Shows connection status
    lateinit var textViewMessage: TextView          // Shows sent/received messages
    lateinit var buttonSwitchWifi: Button           // Opens WiFi settings
    lateinit var buttonDiscover: Button             // Starts peer discovery
    lateinit var buttonDisconnect: Button           // Disconnects from peer
    lateinit var listViewPeers: ListView            // Lists discovered peers
    lateinit var editTextMessage: EditText          // Input field for messages
    lateinit var imageButtonSend: ImageButton       // Send message button

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
    private var outputStream: ObjectOutputStream? = null    // Stream for sending data
    private var inputStream: ObjectInputStream? = null      // Stream for receiving data
    private var isConnected = false                         // True if connected to a peer

    // ========== UI HANDLER ==========
    val handler = Handler(Looper.getMainLooper())           // Handler for updating UI from background threads

    // ========== POWER MANAGEMENT ==========
    private lateinit var wakeLock: PowerManager.WakeLock    // Keeps device awake during connection

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
        textViewMessage = findViewById(R.id.textViewMessage)
        buttonSwitchWifi = findViewById(R.id.buttonSwitchWifi)
        buttonDiscover = findViewById(R.id.buttonDiscover)
        buttonDisconnect = findViewById(R.id.buttonDisconnect)
        listViewPeers = findViewById(R.id.listViewPeers)
        editTextMessage = findViewById(R.id.editTextMessage)
        imageButtonSend = findViewById(R.id.imageButtonSend)
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
            closeConnection()
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
     * Handle successful WiFi P2P connection
     * Determines if this device is host or client and starts appropriate role
     */
    fun onWifiP2pConnected(info: WifiP2pInfo) {
        updateStatus("Connected to peer")

        // Clean up any existing connections before starting new one
        closeConnection()

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
     */
    private fun startServer() {
        try {
            // Create server socket on specified port
            serverSocket = ServerSocket(PORT)
            updateStatus("Waiting for client...")

            // Wait for client to connect (blocking call)
            val client = serverSocket?.accept()

            if (client != null) {
                socket = client
                setupStreams()  // Initialize input/output streams

                val clientAddress = client.inetAddress.hostAddress
                updateStatus("Client connected: $clientAddress")

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
     */
    private fun connectToServer(hostAddress: InetAddress) {
        try {
            // Create client socket and connect to host
            socket = Socket()
            socket?.connect(InetSocketAddress(hostAddress, PORT), 5000) // 5 second timeout

            if (socket?.isConnected == true) {
                setupStreams()  // Initialize input/output streams
                updateStatus("Connected to server")

                // Start listening for incoming messages
                listenForMessages()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Client error: ${e.message}")
            e.printStackTrace()
            updateStatus("Connection error: ${e.message}")
        }
    }

    /**
     * Initialize input and output streams for socket communication
     * IMPORTANT: Output stream must be created first to avoid deadlock
     */
    private fun setupStreams() {
        try {
            // Create output stream first (important for avoiding deadlock)
            outputStream = ObjectOutputStream(socket?.getOutputStream())
            outputStream?.flush()

            // Then create input stream
            inputStream = ObjectInputStream(socket?.getInputStream())
            isConnected = true

            Log.d(TAG, "Streams initialized successfully")
        } catch (e: IOException) {
            Log.e(TAG, "Stream setup error: ${e.message}")
            e.printStackTrace()
            closeConnection()
        }
    }

    /**
     * Listen for incoming messages in a loop
     * Runs in background thread to avoid blocking UI
     */
    @SuppressLint("SetTextI18n")
    private fun listenForMessages() {
        try {
            while (isConnected && socket?.isConnected == true) {
                socket?.soTimeout = 30000 // 30 second timeout to prevent indefinite blocking

                // Read incoming message (blocking call)
                val message = inputStream?.readObject() as? String

                if (message != null) {
                    // Update UI on main thread
                    handler.post {
                        textViewMessage.text = "Them: $message"
                    }
                    Log.d(TAG, "Received: $message")
                }
            }
        } catch (e: SocketTimeoutException) {
            // Timeout is normal - just continue listening
            Log.d(TAG, "Socket timeout: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Message listening error: ${e.message}")
            e.printStackTrace()

            // If still connected, this is an unexpected error
            if (isConnected) {
                handler.post {
                    Toast.makeText(this, "Connection lost", Toast.LENGTH_SHORT).show()
                }
                closeConnection()
            }
        }
    }

    /**
     * Send a text message to the connected peer
     * @param message The text message to send
     */
    @SuppressLint("SetTextI18n")
    private fun sendMessage(message: String) {
        if (!isConnected || outputStream == null) {
            handler.post {
                Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            // Send the message through output stream
            outputStream?.writeObject(message)
            outputStream?.flush()

            // Update UI on main thread
            handler.post {
                textViewMessage.text = "You: $message"
                editTextMessage.text.clear()  // Clear input field
            }

            Log.d(TAG, "Sent: $message")
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
     */
    private fun closeConnection() {
        isConnected = false

        try {
            // Close all streams and sockets
            socket?.close()
            serverSocket?.close()
            outputStream?.close()
            inputStream?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Close error: ${e.message}")
        } finally {
            // Reset all connection variables
            //inputStream = null
            //outputStream = null
            //socket = null
            //serverSocket = null

            isHost = false
            socket = null
            serverSocket = null
            outputStream = null
            inputStream = null
            isConnected = false
        }
        // Update status display
        updateStatus(if (isHost) "Host (disconnected)" else "Client (disconnected)")
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