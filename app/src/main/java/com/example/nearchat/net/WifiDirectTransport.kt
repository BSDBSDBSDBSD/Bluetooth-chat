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
 *
 * Every WifiP2pManager call is guarded: on many phones they throw SecurityException /
 * IllegalArgumentException (permission revoked, channel lost) and an uncaught throw
 * on the main thread closes the app.
 *
 * The group owner's server may take a few seconds to come up (its app has to get the
 * "connected" broadcast first), so clients keep retrying for as long as the group
 * exists instead of giving up.
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
    @Volatile private var clientThread: Thread? = null
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

    private fun setStatus(s: String) { status = s; onChange() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        p2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!p2pEnabled) status = "ה‑Wi‑Fi כבוי. צריך להדליק אותו (לא צריך אינטרנט)"
                        onChange()
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        if (info != null) onConnectionInfo(info) else requestConnectionInfo()
                    }
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        discovering = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        onChange()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "receiver failed", e)
            }
        }
    }

    fun start() {
        val m = manager
        if (m == null) { setStatus("המכשיר לא תומך ב‑Wi‑Fi Direct"); return }
        if (!hasPermissions()) { setStatus("חסרה הרשאה: מכשירים בקרבת מקום"); return }
        try {
            if (channel == null) {
                channel = m.initialize(context, Looper.getMainLooper()) {
                    channel = null; setStatus("החיבור לשירות Wi‑Fi Direct נסגר. לוחצים שוב על \"חיפוש\"")
                }
            }
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
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            setStatus("לא ניתן להפעיל Wi‑Fi Direct")
        }
    }

    fun stop() {
        if (receiverRegistered) { try { context.unregisterReceiver(receiver) } catch (_: Exception) {}; receiverRegistered = false }
        closeAll()
    }

    /** Returns the channel, (re)initialising it if needed; null if not possible. */
    private fun ready(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m = manager ?: run { setStatus("המכשיר לא תומך ב‑Wi‑Fi Direct"); return null }
        if (!hasPermissions()) { setStatus("חסרה הרשאה: מכשירים בקרבת מקום"); return null }
        if (channel == null) start()
        val c = channel ?: return null
        return m to c
    }

    private fun guarded(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Log.e(TAG, "$what failed", e)
            setStatus("$what נכשל: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun listener(ok: String, fail: String, after: (() -> Unit)? = null) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (ok.isNotEmpty()) status = ok; onChange(); after?.invoke() }
        override fun onFailure(reason: Int) { setStatus("$fail (${reasonText(reason)})") }
    }

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "לא נתמך במכשיר"
        WifiP2pManager.BUSY -> "המערכת עסוקה, נסו שוב בעוד כמה שניות"
        WifiP2pManager.ERROR -> "שגיאה פנימית. כדאי לכבות ולהדליק את ה‑Wi‑Fi"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "אין בקשות שירות"
        else -> "קוד $r"
    }

    fun discover() {
        val (m, c) = ready() ?: return
        guarded("החיפוש") { m.discoverPeers(c, listener("מחפש מכשירים ב‑Wi‑Fi Direct…", "החיפוש נכשל")) }
    }

    fun stopDiscovery() {
        val (m, c) = ready() ?: return
        guarded("עצירת החיפוש") { m.stopPeerDiscovery(c, null) }
    }

    /** Become group owner so several phones can join this one. */
    fun createGroup() {
        val (m, c) = ready() ?: return
        guarded("יצירת הרשת") {
            m.createGroup(c, listener("נוצרה רשת. עכשיו אפשר להתחבר אליה מהמכשירים האחרים", "יצירת הרשת נכשלה"))
        }
    }

    fun connect(address: String) {
        val (m, c) = ready() ?: return
        val cfg = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
        }
        guarded("החיבור") { m.connect(c, cfg, listener("נשלחה בקשת חיבור. אם מופיעה הודעה במכשיר השני, צריך לאשר אותה", "החיבור נכשל")) }
    }

    fun disconnect() {
        closeAll()
        val m = manager ?: return
        val c = channel ?: return
        guarded("הניתוק") { m.removeGroup(c, listener("נותק", "הניתוק נכשל")) }
    }

    private fun requestPeers() {
        val m = manager ?: return; val c = channel ?: return
        if (!hasPermissions()) return
        guarded("קבלת רשימת המכשירים") {
            m.requestPeers(c) { list ->
                peers = list?.deviceList.orEmpty().mapNotNull { d ->
                    val addr = d?.deviceAddress ?: return@mapNotNull null
                    FoundDevice(d.deviceName?.takeIf { it.isNotBlank() } ?: addr, addr, statusText(d.status))
                }
                onChange()
            }
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
        guarded("קבלת מצב החיבור") { m.requestConnectionInfo(c) { info -> onConnectionInfo(info) } }
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            if (groupFormed) status = "רשת ה‑Wi‑Fi Direct התנתקה"
            groupFormed = false; isGroupOwner = false
            closeAll()
            onChange()
            return
        }
        groupFormed = true
        isGroupOwner = info.isGroupOwner
        // Both roles listen, so a connection can be made in either direction.
        startServer()
        if (info.isGroupOwner) {
            status = "אתה המארח של הרשת. מחכה שהמכשירים האחרים יתחברו…"
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
            Log.e(TAG, "server bind failed", e); status = "לא ניתן לפתוח את השרת המקומי (פורט $PORT תפוס)"; return
        }
        server = ss
        thread(name = "wfd-server", isDaemon = true) {
            while (server === ss) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                attachSocket(s)
            }
        }
    }

    private fun hasLiveLinkTo(host: String) = myLinks.any { !it.closed && it.address == host }

    /**
     * Connects to the group owner and keeps trying while the group exists. The owner's
     * app may still be starting its server, or the DHCP address on this side may not be
     * ready yet, so early failures are expected.
     */
    private fun connectToOwner(owner: InetAddress) {
        val host = owner.hostAddress ?: return
        if (hasLiveLinkTo(host)) return
        if (clientThread?.isAlive == true) return
        clientThread = thread(name = "wfd-client", isDaemon = true) {
            var attempt = 0
            while (groupFormed && !isGroupOwner && !hasLiveLinkTo(host)) {
                attempt++
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(owner, PORT), 4000)
                    attachSocket(s)
                    setStatus("מחובר ב‑Wi‑Fi Direct")
                    return@thread
                } catch (e: Exception) {
                    Log.i(TAG, "connect to owner $host attempt $attempt: ${e.message}")
                    if (attempt == 4) setStatus("מחכה למכשיר המארח… צריך לוודא שהאפליקציה פתוחה בו")
                    try { Thread.sleep(if (attempt < 10) 1500L else 4000L) } catch (_: InterruptedException) { return@thread }
                }
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
        clientThread?.interrupt()
        clientThread = null
        myLinks.forEach { it.close() }
        myLinks.clear()
    }

    companion object {
        private const val TAG = "WifiDirect"
        const val PORT = 38988
    }
}
