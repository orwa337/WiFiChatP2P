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
import java.io.Serializable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.net.SocketTimeoutException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PORT = 8888
    }

    // UI Elements
    lateinit var textViewConnectionStatus: TextView
    lateinit var textViewMessage: TextView
    lateinit var textViewMessageInfo: TextView
    lateinit var buttonSwitchWifi: Button
    lateinit var buttonDiscover: Button
    lateinit var listViewPeers: ListView
    lateinit var editTextMessage: EditText
    lateinit var imageButtonSend: ImageButton

    // WiFi P2P Variables
    lateinit var manager: WifiP2pManager
    lateinit var channel: WifiP2pManager.Channel
    lateinit var receiver: BroadcastReceiver
    lateinit var intentFilter: IntentFilter

    // Device Lists
    val peers = ArrayList<WifiP2pDevice>()
    lateinit var devicesNames: Array<String>
    lateinit var devices: Array<WifiP2pDevice>

    // Connection Variables
    var isHost = false
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var outputStream: ObjectOutputStream? = null
    private var inputStream: ObjectInputStream? = null
    private var isConnected = false

    // For tracking message round-trip times
    private val pendingAcks = ConcurrentHashMap<String, Long>()

    // Handler for UI updates
    val handler = Handler(Looper.getMainLooper())

    // WakeLock to stay connected by Screen time out
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupWifiP2p()
        setupButtonListeners()
        checkWifiAndLocation()
        requestBatteryOptimizationExemption()
    }

    private fun initializeViews() {
        textViewConnectionStatus = findViewById(R.id.textViewConnectionStatus)
        textViewMessage = findViewById(R.id.textViewMessage)
        textViewMessageInfo = findViewById(R.id.textViewMessageInfo)
        buttonSwitchWifi = findViewById(R.id.buttonSwitchWifi)
        buttonDiscover = findViewById(R.id.buttonDiscover)
        listViewPeers = findViewById(R.id.listViewPeers)
        editTextMessage = findViewById(R.id.editTextMessage)
        imageButtonSend = findViewById(R.id.imageButtonSend)
    }

    private fun setupWifiP2p() {
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = WifiDirectBroadcastReceiver(manager, channel, this)

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
    }

    private fun setupButtonListeners() {
        buttonSwitchWifi.setOnClickListener {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        }

        buttonDiscover.setOnClickListener {
            discoverPeers()
        }

        listViewPeers.setOnItemClickListener { _, _, position, _ ->
            connectToDevice(position)
        }

        imageButtonSend.setOnClickListener {
            val message = editTextMessage.text.toString()
            if (message.isEmpty()) {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Execute in background thread to avoid network on main thread
            thread(start = true) {
                try {
                    sendMessage(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Send message error: ${e.message}")
                    e.printStackTrace()
                    handler.post {
                        Toast.makeText(this@MainActivity,
                            "Error sending message: ${e.message}",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun checkWifiAndLocation() {
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

        // Use LocationManager instead of deprecated Settings.Secure.LOCATION_MODE
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

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

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

    // Public method for updating status from WifiDirectBroadcastReceiver
    fun updateStatus(status: String) {
        handler.post {
            textViewConnectionStatus.text = status
            Log.d(TAG, "Status: $status")
        }
    }

    // Handler for when a Wifi P2P connection is established
    fun onWifiP2pConnected(info: WifiP2pInfo) {
        updateStatus("Connected to peer")

        // Clean up any existing connections
        closeConnection()

        if (info.groupFormed && info.isGroupOwner) {
            isHost = true
            updateStatus("Host")

            // Start server in background thread
            thread(start = true) {
                startServer()
            }
        } else if (info.groupFormed) {
            isHost = false
            updateStatus("Client")

            // Start client in background thread
            val hostAddress = info.groupOwnerAddress
            thread(start = true) {
                connectToServer(hostAddress)
            }
        }
    }

    // Handler for when a Wifi P2P connection is lost
    fun onWifiP2pDisconnected() {
        updateStatus("Disconnected from peer")

        // Clean up resources
        closeConnection()
    }

    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val deviceList = peerList.deviceList
        if (deviceList.isNotEmpty()) {
            peers.clear()
            peers.addAll(deviceList)

            devicesNames = Array(deviceList.size) { i ->
                deviceList.elementAt(i).deviceName ?: "Unknown Device"
            }

            devices = deviceList.toTypedArray()

            handler.post {
                val adapter = ArrayAdapter(applicationContext,
                    android.R.layout.simple_list_item_1, devicesNames)
                listViewPeers.adapter = adapter
            }
        } else {
            updateStatus("No devices found")
        }
    }

    private fun startServer() {
        try {
            serverSocket = ServerSocket(PORT)
            updateStatus("Waiting for client...")

            // Accept client connection (blocking call)
            val client = serverSocket?.accept()

            if (client != null) {
                socket = client
                setupStreams()

                val clientAddress = client.inetAddress.hostAddress
                updateStatus("Client connected: $clientAddress")

                // Start listening for messages
                listenForMessages()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Server error: ${e.message}")
            e.printStackTrace()
            updateStatus("Server error: ${e.message}")
        }
    }

    private fun connectToServer(hostAddress: InetAddress) {
        try {
            socket = Socket()
            socket?.connect(InetSocketAddress(hostAddress, PORT), 5000)

            if (socket?.isConnected == true) {
                setupStreams()
                updateStatus("Connected to server")

                // Start listening for messages
                listenForMessages()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Client error: ${e.message}")
            e.printStackTrace()
            updateStatus("Connection error: ${e.message}")
        }
    }

    private fun setupStreams() {
        try {
            // Important: create output stream first to avoid deadlock
            outputStream = ObjectOutputStream(socket?.getOutputStream())
            outputStream?.flush()

            inputStream = ObjectInputStream(socket?.getInputStream())
            isConnected = true

            Log.d(TAG, "Streams initialized successfully")
        } catch (e: IOException) {
            Log.e(TAG, "Stream setup error: ${e.message}")
            e.printStackTrace()
            closeConnection()
        }
    }

    private fun listenForMessages() {
        try {
            while (isConnected && socket?.isConnected == true) {

                // Add socket timeout to prevent indefinite blocking
                socket?.soTimeout = 30000 // 30 second timeout
                val messageObject = inputStream?.readObject() as? NetworkMessage

                if (messageObject != null) {
                    when (messageObject.type) {
                        MessageType.CHAT -> {
                            val chatMessage = messageObject.payload as ChatMessage
                            handleChatMessage(chatMessage)

                            // Auto-send acknowledgment
                            sendAcknowledgment(messageObject.id)
                        }

                        MessageType.ACK -> {
                            val ackId = messageObject.payload as String
                            handleAcknowledgment(ackId)
                        }
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            // Don't treat timeout as connection loss - just continue
            Log.d(TAG, "Socket timeout: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Message listening error: ${e.message}")
            e.printStackTrace()

            if (isConnected) {
                handler.post {
                    Toast.makeText(this, "Connection lost", Toast.LENGTH_SHORT).show()
                }
                closeConnection()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleChatMessage(chatMessage: ChatMessage) {
        val currentTime = System.currentTimeMillis()
        val formattedTime = formatTime(currentTime)

        handler.post {
            textViewMessage.text = "Them: ${chatMessage.content}"
            textViewMessageInfo.text = "Size: ${chatMessage.size} bytes | Received at $formattedTime"
        }

        Log.d(TAG, "Received: ${chatMessage.content}")
    }

    @SuppressLint("SetTextI18n")
    private fun handleAcknowledgment(ackId: String) {
        val sentTime = pendingAcks.remove(ackId) ?: return
        val roundTripTime = System.currentTimeMillis() - sentTime

        Log.d(TAG, "Acknowledgment received for message $ackId. Round-trip time: $roundTripTime ms")

        handler.post {
            // Update the UI to show the round-trip time
            val currentText = textViewMessageInfo.text.toString()
            if (currentText.contains("waiting for delivery")) {
                textViewMessageInfo.text = "Size: ${currentText.split(" | ")[0].removePrefix("Size: ")} bytes | Round-trip time: $roundTripTime ms"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun sendMessage(message: String) {
        if (!isConnected || outputStream == null) {
            handler.post {
                Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            // Generate a unique ID for this message
            val messageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()
            val messageSize = message.toByteArray().size

            // Create chat message
            val chatMessage = ChatMessage(message, messageSize)

            // Create network message wrapper
            val networkMessage = NetworkMessage(
                id = messageId,
                type = MessageType.CHAT,
                payload = chatMessage
            )

            // Save start time for round-trip calculation
            pendingAcks[messageId] = startTime

            // Send the message
            outputStream?.writeObject(networkMessage)
            outputStream?.flush()

            handler.post {
                textViewMessage.text = "You: $message"
                textViewMessageInfo.text = "Size: $messageSize bytes | waiting for delivery confirmation..."
                editTextMessage.text.clear()
            }

            Log.d(TAG, "Sent: $message (ID: $messageId)")
        } catch (e: IOException) {
            Log.e(TAG, "Send error: ${e.message}")
            e.printStackTrace()

            handler.post {
                Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            closeConnection()
        }
    }

    private fun sendAcknowledgment(messageId: String) {
        if (!isConnected || outputStream == null) {
            return
        }

        try {
            // Create network message wrapper for acknowledgment
            val networkMessage = NetworkMessage(
                id = UUID.randomUUID().toString(),
                type = MessageType.ACK,
                payload = messageId
            )

            // Send the acknowledgment
            outputStream?.writeObject(networkMessage)
            outputStream?.flush()

            Log.d(TAG, "Sent acknowledgment for message: $messageId")
        } catch (e: IOException) {
            Log.e(TAG, "Send acknowledgment error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun formatTime(timeMillis: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timeMillis))
    }

    private fun closeConnection() {
        isConnected = false

        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Close error: ${e.message}")
        } finally {
            inputStream = null
            outputStream = null
            socket = null
            serverSocket = null
            pendingAcks.clear()
        }

        updateStatus(if (isHost) "Host (disconnected)" else "Client (disconnected)")
    }

    private fun disableWifiPowerSaving() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MyApp:WifiLock")
        wifiLock.acquire()
    }

    @SuppressLint("RegisterReceiverForAllUsers", "UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()

        disableWifiPowerSaving()
        closeConnection()
    }
}

// Message type enum
enum class MessageType {
    CHAT,
    ACK
}

// Network message class that wraps all messages
data class NetworkMessage(
    val id: String,
    val type: MessageType,
    val payload: Serializable
) : Serializable

// Chat message class
data class ChatMessage(
    val content: String,
    val size: Int
) : Serializable