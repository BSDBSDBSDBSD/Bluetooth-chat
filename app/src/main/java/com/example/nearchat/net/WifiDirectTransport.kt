package com.example.nearchat.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.example.nearchat.core.FoundDevice
import com.example.nearchat.core.TransportKind
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Wi-Fi Direct (Wi-Fi P2P) transport: a direct phone-to-phone Wi-Fi link, no router
 * and no internet. One phone becomes the "group owner" (acts like a tiny hotspot) and
 * runs a TCP server; the others connect to it. The mesh router relays between clients.
 */
@SuppressLint("MissingPermission")
class WifiDirectTransport(
    private val context: Context,
    private val onLink: (Link) -> Unit,
    private val onChange: () -> Unit,
) {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    @Volatile private var server: ServerSocket? = null
    @Volatile private var clientConnecting = false
    private val myLinks = CopyOnWriteArrayList<Link>()
    @Volatile private var peers: List<FoundDevice> = emptyList()

    @Volatile var p2pEnabled = false; private set
    @Volatile var groupFormed = false; private set
    @Volatile var isGroupOwner = false; private set
    @Volatile var discovering = false; private set
    @Volatile var status: String = ""; private set

    val supported: Boolean get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun hasPermissions() = context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    fun peers(): List<FoundDevice> = peers

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    p2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!p2pEnabled) status = "Wi‑Fi כבוי – הפעל Wi‑Fi (אין צורך באינטרנט)"
                    onChange()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    discovering = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    onChange()
                }
            }
        }
    }

    fun start() {
        val m = manager
        if (m == null) { status = "Wi‑Fi Direct לא נתמך"; onChange(); return }
        if (!hasPermissions()) { status = "חסרה הרשאת מכשירי Wi‑Fi בקרבת מקום"; onChange(); return }
        if (channel == null) channel = m.initialize(context, Looper.getMainLooper()) { channel = null; status = "ערוץ Wi‑Fi Direct נסגר"; onChange() }
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            }
            context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        requestConnectionInfo()
    }

    fun stop() {
        if (receiverRegistered) { try { context.unregisterReceiver(receiver) } catch (_: Exception) {}; receiverRegistered = false }
        closeAll()
    }

    private fun listener(ok: String, fail: String, after: (() -> Unit)? = null) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (ok.isNotEmpty()) status = ok; onChange(); after?.invoke() }
        override fun onFailure(reason: Int) {
            status = "$fail (${reasonText(reason)})"; onChange()
        }
    }

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "לא נתמך"
        WifiP2pManager.BUSY -> "המערכת עסוקה, נסה שוב"
        WifiP2pManager.ERROR -> "שגיאה"
        else -> "קוד $r"
    }

    fun discover() {
        val m = manager ?: return; val c = channel ?: run { start(); channel } ?: return
        if (!hasPermissions()) { status = "חסרה הרשאה"; onChange(); return }
        m.discoverPeers(c, listener("מחפש מכשירים ב‑Wi‑Fi Direct…", "החיפוש נכשל"))
    }

    fun stopDiscovery() {
        val m = manager ?: return; val c = channel ?: return
        m.stopPeerDiscovery(c, null)
    }

    /** Become group owner so several phones can join this one. */
    fun createGroup() {
        val m = manager ?: return; val c = channel ?: return
        m.createGroup(c, listener("נוצרה רשת – מכשירים אחרים יכולים להתחבר", "יצירת הרשת נכשלה"))
    }

    fun connect(address: String) {
        val m = manager ?: return; val c = channel ?: return
        val cfg = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
        }
        m.connect(c, cfg, listener("שולח בקשת חיבור… אשר במכשיר השני אם תתבקש", "החיבור נכשל"))
    }

    fun disconnect() {
        val m = manager ?: return; val c = channel ?: return
        closeAll()
        m.removeGroup(c, listener("נותק", "הניתוק נכשל"))
    }

    private fun requestPeers() {
        val m = manager ?: return; val c = channel ?: return
        if (!hasPermissions()) return
        m.requestPeers(c) { list ->
            peers = list.deviceList.map { d ->
                FoundDevice(d.deviceName.ifBlank { d.deviceAddress }, d.deviceAddress, statusText(d.status))
            }
            onChange()
        }
    }

    private fun statusText(s: Int) = when (s) {
        WifiP2pDevice.CONNECTED -> "מחובר"
        WifiP2pDevice.INVITED -> "הוזמן"
        WifiP2pDevice.FAILED -> "נכשל"
        WifiP2pDevice.AVAILABLE -> "זמין"
        WifiP2pDevice.UNAVAILABLE -> "לא זמין"
        else -> ""
    }

    private fun requestConnectionInfo() {
        val m = manager ?: return; val c = channel ?: return
        m.requestConnectionInfo(c) { info -> onConnectionInfo(info) }
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            if (groupFormed) status = "רשת Wi‑Fi Direct נותקה"
            groupFormed = false; isGroupOwner = false
            closeAll()
            onChange()
            return
        }
        groupFormed = true
        isGroupOwner = info.isGroupOwner
        if (info.isGroupOwner) {
            startServer()
            status = "מארח רשת Wi‑Fi Direct – ממתין למכשירים"
        } else {
            val owner = info.groupOwnerAddress
            if (owner != null) connectToOwner(owner)
        }
        requestPeers()
        onChange()
    }

    @Synchronized
    private fun startServer() {
        if (server != null) return
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: Exception) {
            Log.e(TAG, "server bind failed", e); status = "פתיחת שרת נכשלה"; return
        }
        server = ss
        thread(name = "wfd-server", isDaemon = true) {
            while (server === ss) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                attachSocket(s)
            }
        }
    }

    private fun connectToOwner(owner: InetAddress) {
        if (clientConnecting) return
        if (myLinks.any { !it.closed && it.address == owner.hostAddress }) return
        clientConnecting = true
        thread(name = "wfd-client", isDaemon = true) {
            try {
                for (attempt in 1..10) {
                    try {
                        val s = Socket()
                        s.connect(InetSocketAddress(owner, PORT), 5000)
                        attachSocket(s)
                        status = "מחובר ב‑Wi‑Fi Direct"
                        onChange()
                        return@thread
                    } catch (e: Exception) {
                        Thread.sleep(1500)
                    }
                }
                status = "לא ניתן להתחבר למארח – ודא ש‑NearChat פתוח בו"
                onChange()
            } finally {
                clientConnecting = false
            }
        }
    }

    private fun attachSocket(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 120_000 // announces arrive every ~20 s; silence means the peer is gone
            val link = Link(TransportKind.WIFI_DIRECT, s.inetAddress?.hostAddress ?: "?", s.getInputStream(), s.getOutputStream(), s)
            myLinks.add(link)
            myLinks.removeAll { it.closed }
            onLink(link)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun closeAll() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        myLinks.forEach { it.close() }
        myLinks.clear()
    }

    companion object {
        private const val TAG = "WifiDirect"
        const val PORT = 38988
    }
}
