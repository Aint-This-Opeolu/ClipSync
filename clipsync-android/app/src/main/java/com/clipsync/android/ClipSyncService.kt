package com.clipsync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android counterpart to clipsync-desktop's main.rs: same pairing-code key
 * derivation, same mDNS-discovery-then-TCP-handshake flow, same length-prefixed
 * AES-256-GCM frames on the wire. See README.md for the known limitations
 * around background clipboard access on Android 10+.
 */
class ClipSyncService : Service() {

    companion object {
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val PORT = 53211
        private const val CHANNEL_ID = "clipsync_channel"
        private const val NOTIFICATION_ID = 1
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private lateinit var nsdManager: NsdManager
    private lateinit var discovery: Discovery
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var clipboardManager: ClipboardManager

    private val activeConnections = CopyOnWriteArraySet<Connection>()
    @Volatile private var lastClipText: String? = null
    private val applyingRemoteUpdate = AtomicBoolean(false)

    private lateinit var key: ByteArray
    private lateinit var fingerprint: String
    private lateinit var deviceName: String

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onLocalClipChanged() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra(EXTRA_PAIRING_CODE)
        if (code.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)?.ifBlank { "this-phone" } ?: "this-phone"

        key = Crypto.deriveKey(code)
        fingerprint = Crypto.keyFingerprint(key)

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        ClipSyncStatus.set("Starting...")
        ClipSyncLog.log("Key fingerprint: $fingerprint  (should match on both devices)")

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discovery = Discovery(nsdManager)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("clipsync-mdns").apply {
            setReferenceCounted(true)
            acquire()
        }

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)

        serviceScope.launch { startServer() }
        serviceScope.launch { startClientLoop() }

        discovery.advertise(deviceName, PORT, fingerprint) { registeredName ->
            ClipSyncLog.log("Advertising on the LAN as '$registeredName', listening on port $PORT.")
        }

        ClipSyncStatus.set("Waiting for a paired connection...")
        ClipSyncLog.log("Waiting for a paired connection... (open the desktop app and enter the same pairing code)")

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        discovery.stopAdvertising()
        discovery.stopDiscovery()
        try { serverSocket?.close() } catch (_: Exception) {}
        activeConnections.forEach { it.close() }
        activeConnections.clear()
        try { clipboardManager.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        ClipSyncStatus.set("Stopped")
    }

    // ---- Server side: accept incoming connections (peer dialed us first) ----

    private suspend fun startServer() {
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            ClipSyncLog.log(
                "Could not bind port $PORT: ${e.message}. Likely another instance of this app " +
                    "is already running on this device."
            )
            stopSelf()
            return
        }

        while (currentCoroutineContext().isActive) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    ClipSyncLog.log("Accept error: ${e.message}")
                }
                break
            }
            ClipSyncLog.log("Incoming connection from ${socket.inetAddress.hostAddress}")
            serviceScope.launch { handleConnection(socket) }
        }
    }

    // ---- Client side: browse for a matching peer and dial it ----

    private suspend fun startClientLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val peer = withTimeoutOrNull(5_000) { discovery.findMatchingPeer(fingerprint) }
                discovery.stopDiscovery()
                if (peer != null) {
                    ClipSyncLog.log("Found matching peer at ${peer.address.hostAddress}:${peer.port}")
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(peer.address, peer.port), 5_000)
                        handleConnection(socket)
                    } catch (e: Exception) {
                        ClipSyncLog.log("Connect failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                ClipSyncLog.log("Discovery error: ${e.message}")
            }
            delay(5_000)
        }
    }

    // ---- Shared connection handling: handshake, then read/write loop ----

    private suspend fun handleConnection(socket: Socket) = withContext(Dispatchers.IO) {
        val connection = Connection(socket)
        try {
            Protocol.sendHello(socket.getOutputStream(), Hello(deviceName, fingerprint))
            val peerHello = Protocol.readHello(socket.getInputStream())
            if (peerHello == null) {
                socket.close()
                return@withContext
            }
            if (peerHello.keyFingerprint != fingerprint) {
                ClipSyncLog.log("Rejected connection from '${peerHello.deviceName}': pairing code mismatch.")
                socket.close()
                return@withContext
            }
            ClipSyncLog.log("Paired with '${peerHello.deviceName}'.")
            ClipSyncStatus.set("Paired with ${peerHello.deviceName}")
            activeConnections.add(connection)

            while (isActive) {
                val blob = Protocol.readFrame(socket.getInputStream()) ?: break
                val plaintext = Crypto.decrypt(key, blob) ?: continue
                val update = Protocol.decodeClipUpdate(plaintext) ?: continue
                applyRemoteUpdate(update.text)
                ClipSyncLog.log("Synced clipboard from '${update.fromDevice}'.")
            }
        } catch (e: Exception) {
            // Connection dropped; normal when a peer goes away on a LAN.
        } finally {
            activeConnections.remove(connection)
            connection.close()
            if (activeConnections.isEmpty()) {
                ClipSyncStatus.set("Waiting for a paired connection...")
            }
        }
    }

    // ---- Clipboard watching ----

    private fun onLocalClipChanged() {
        if (applyingRemoteUpdate.get()) return

        val clip: ClipData? = try {
            clipboardManager.primaryClip
        } catch (e: Exception) {
            null
        }
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?: return

        if (text == lastClipText) return
        lastClipText = text

        ClipSyncLog.log("Local clipboard changed (${text.length} chars); pushing to peer(s).")
        val update = ClipUpdate(text, deviceName)
        serviceScope.launch(Dispatchers.IO) {
            activeConnections.forEach { conn ->
                try {
                    conn.sendClipUpdate(key, update)
                } catch (e: Exception) {
                    ClipSyncLog.log("Send failed: ${e.message}")
                }
            }
        }
    }

    private fun applyRemoteUpdate(text: String) {
        if (text == lastClipText) return
        lastClipText = text
        applyingRemoteUpdate.set(true)
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("clipsync", text))
        } finally {
            // Give our own setPrimaryClip's listener callback a moment to fire (and be
            // ignored) before re-arming, so it doesn't bounce straight back to the peer.
            Handler(Looper.getMainLooper()).postDelayed({ applyingRemoteUpdate.set(false) }, 300)
        }
    }

    // ---- Notification ----

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ClipSync", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipSync running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    /** A live, paired socket plus a lock so concurrent local clip changes don't interleave writes. */
    private class Connection(private val socket: Socket) {
        private val writeLock = Any()

        fun sendClipUpdate(key: ByteArray, update: ClipUpdate) {
            synchronized(writeLock) {
                val json = Protocol.encodeClipUpdate(update)
                val encrypted = Crypto.encrypt(key, json)
                Protocol.writeFrame(socket.getOutputStream(), encrypted)
            }
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
