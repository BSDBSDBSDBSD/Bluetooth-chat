package com.example.nearchat.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.example.nearchat.core.FoundDevice
import com.example.nearchat.core.TransportKind
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Classic Bluetooth (RFCOMM) transport. Every phone runs a listening server socket;
 * the user picks a nearby device to connect to. Insecure RFCOMM is used so no
 * system pairing dialog is required - message confidentiality comes from
 * NearChat's own end-to-end encryption.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val context: Context,
    private val onLink: (Link) -> Unit,
    private val onChange: () -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var running = false
    private var receiverRegistered = false
    private val found = Collections.synchronizedMap(LinkedHashMap<String, FoundDevice>())
    private val connecting = Collections.synchronizedSet(HashSet<String>())

    @Volatile var discovering = false; private set
    @Volatile var status: String = ""; private set

    val supported: Boolean get() = adapter != null
    val enabled: Boolean get() = try { adapter?.isEnabled == true } catch (e: SecurityException) { false }

    fun hasPermissions(): Boolean = listOf(
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
    ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun foundDevices(): List<FoundDevice> = synchronized(found) { found.values.toList() }
    fun isConnecting(address: String) = connecting.contains(address)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    // Only phones / computers - skip headphones, watches etc.
                    val major = try { d.bluetoothClass?.majorDeviceClass } catch (e: Exception) { null }
                    if (major != null && major != android.bluetooth.BluetoothClass.Device.Major.PHONE &&
                        major != android.bluetooth.BluetoothClass.Device.Major.COMPUTER &&
                        major != android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED) return
                    val name = safeName(d)
                    found[d.address] = FoundDevice(name, d.address, if (d.bondState == BluetoothDevice.BOND_BONDED) "מותאם" else "")
                    onChange()
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> { discovering = true; onChange() }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { discovering = false; status = "החיפוש הסתיים"; onChange() }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (st == BluetoothAdapter.STATE_ON && running) startServer()
                    if (st == BluetoothAdapter.STATE_OFF) { closeServer(); discovering = false }
                    onChange()
                }
            }
        }
    }

    private fun safeName(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    fun start() {
        if (adapter == null || !hasPermissions()) { status = if (adapter == null) "אין Bluetooth במכשיר" else "חסרות הרשאות Bluetooth"; onChange(); return }
        running = true
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        loadBonded()
        if (enabled) startServer()
        onChange()
    }

    fun stop() {
        running = false
        closeServer()
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        if (receiverRegistered) { try { context.unregisterReceiver(receiver) } catch (_: Exception) {}; receiverRegistered = false }
        onChange()
    }

    private fun loadBonded() {
        try {
            adapter?.bondedDevices?.forEach { d ->
                val major = d.bluetoothClass?.majorDeviceClass
                if (major == null || major == android.bluetooth.BluetoothClass.Device.Major.PHONE ||
                    major == android.bluetooth.BluetoothClass.Device.Major.COMPUTER) {
                    if (!found.containsKey(d.address)) found[d.address] = FoundDevice(safeName(d), d.address, "מותאם")
                }
            }
        } catch (_: SecurityException) {}
    }

    @Synchronized
    private fun startServer() {
        if (serverSocket != null || adapter == null) return
        val ss = try {
            adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        } catch (e: Exception) {
            Log.e(TAG, "listen failed", e); status = "לא ניתן להאזין ב-Bluetooth"; onChange(); return
        }
        serverSocket = ss
        thread(name = "bt-server", isDaemon = true) {
            while (running && serverSocket === ss) {
                val s: BluetoothSocket = try { ss.accept() } catch (e: Exception) { break }
                handleSocket(s, incoming = true)
            }
            Log.i(TAG, "bt server stopped")
        }
    }

    @Synchronized
    private fun closeServer() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleSocket(s: BluetoothSocket, incoming: Boolean) {
        val address = s.remoteDevice.address
        try {
            val link = Link(TransportKind.BLUETOOTH, address, s.inputStream, s.outputStream, s)
            status = if (incoming) "התקבל חיבור Bluetooth" else "מחובר ב-Bluetooth"
            onLink(link)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
        }
        onChange()
    }

    fun startDiscovery() {
        val a = adapter ?: return
        if (!hasPermissions() || !enabled) { status = "Bluetooth כבוי או חסרות הרשאות"; onChange(); return }
        loadBonded()
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            status = if (a.startDiscovery()) "מחפש מכשירים…" else "החיפוש נכשל"
        } catch (e: SecurityException) { status = "חסרה הרשאת סריקה" }
        onChange()
    }

    /** Connects to a phone that runs NearChat. [quiet] = background reconnect, no status spam. */
    fun connect(address: String, quiet: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        val a = adapter ?: return
        if (!enabled || !hasPermissions()) return
        if (!connecting.add(address)) return
        if (!quiet) { status = "מתחבר…"; onChange() }
        thread(name = "bt-connect", isDaemon = true) {
            var ok = false
            try {
                try { a.cancelDiscovery() } catch (_: Exception) {}
                val device = a.getRemoteDevice(address)
                val s = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                try {
                    s.connect()
                    ok = true
                    handleSocket(s, incoming = false)
                } catch (e: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    if (!quiet) status = "החיבור נכשל – ודא ש-NearChat פתוח במכשיר השני"
                }
            } catch (e: Exception) {
                if (!quiet) status = "החיבור נכשל"
            } finally {
                connecting.remove(address)
                onChange()
                onResult?.invoke(ok)
            }
        }
    }

    companion object {
        private const val TAG = "BtTransport"
        const val SERVICE_NAME = "NearChat"
        val SERVICE_UUID: UUID = UUID.fromString("6f1c2b1e-4b7a-4d3c-9a51-2e8b8c0f5a11")
    }
}
