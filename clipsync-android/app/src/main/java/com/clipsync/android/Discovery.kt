package com.clipsync.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Matches clipsync-desktop's `discovery.rs`. mdns-sd advertises as
 * "_clipsync._tcp.local."; NsdManager's service type omits the "local."
 * domain (it's implicit), so "_clipsync._tcp." is the same service on the wire.
 */
const val SERVICE_TYPE = "_clipsync._tcp."

data class DiscoveredPeer(val address: InetAddress, val port: Int)

class Discovery(private val nsdManager: NsdManager) {

    @Volatile
    private var registeredServiceName: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Advertises this device on the LAN with the key fingerprint as a TXT
     * record ("fp"), same as the desktop app. [onRegistered] receives the name
     * NSD actually registered (Android may rename it on conflict).
     */
    fun advertise(deviceName: String, port: Int, fingerprint: String, onRegistered: (String) -> Unit) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("fp", fingerprint)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                onRegistered(info.serviceName)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                ClipSyncLog.log("mDNS advertise failed (error $errorCode).")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrationListener = null
    }

    /**
     * Suspends until a peer advertising a matching fingerprint is found.
     * Cancelling the coroutine (e.g. via withTimeoutOrNull) stops discovery
     * cleanly. Filters out our own advertisement by exact service name, same
     * as the desktop app filters by exact mDNS fullname.
     */
    suspend fun findMatchingPeer(fingerprint: String): DiscoveredPeer {
        return suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceName == registeredServiceName) return
                    resolve(service, fingerprint, cont)
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (cont.isActive) cont.cancel()
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            discoveryListener = listener

            cont.invokeOnCancellation {
                try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
            }

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }

    private fun resolve(service: NsdServiceInfo, fingerprint: String, cont: CancellableContinuation<DiscoveredPeer>) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(info: NsdServiceInfo) {
                if (info.serviceName == registeredServiceName) return
                val fp = info.attributes["fp"]?.toString(Charsets.UTF_8)
                if (fp == fingerprint && cont.isActive) {
                    cont.resume(DiscoveredPeer(info.host, info.port))
                }
            }
        })
    }
}
