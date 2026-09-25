package com.example.nearchat.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.nearchat.net.BluetoothTransport
import com.example.nearchat.net.Link
import com.example.nearchat.net.Packet
import com.example.nearchat.net.Payload
import com.example.nearchat.net.Router
import com.example.nearchat.net.WifiDirectTransport
import org.json.JSONArray
import org.json.JSONObject
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The heart of the app: owns identity, storage, transports and the router, and
 * implements the chat protocol (messages, receipts, edit, delete, groups, media).
 *
 * Threading: network packets and outgoing sends are processed on a single worker
 * thread; UI observers are notified on the main thread.
 */
class ChatCore(private val context: Context) : Router.Listener {
    val store = SecureStore(context)
    val repo = Repository(store)

    val myId: String
    val myPublicKey: String
    private val myPrivateKey: PrivateKey

    private val router: Router
    val bluetooth: BluetoothTransport
    val wifi: WifiDirectTransport
    val voicePlayer: VoicePlayer

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var notifyPosted = false
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-worker").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "chat-timer").apply { isDaemon = true } }
    private val pairKeys = ConcurrentHashMap<String, ByteArray>()
    private val lastAnnounces = ConcurrentHashMap<String, Packet>()
    private val btAttempts = ConcurrentHashMap<String, Long>()
    private var started = false

    /** Conversation currently on screen (no notification / unread for it). */
    @Volatile var activeConversation: String? = null
    @Volatile var uiVisible: Boolean = false
    /** Hook for system notifications: (message, conversation title). */
    @Volatile var onIncomingMessage: ((ChatMessage, String) -> Unit)? = null

    init {
        val identity = loadOrCreateIdentity()
        myId = identity.first
        myPrivateKey = identity.second
        myPublicKey = identity.third
        router = Router(myId, this)
        bluetooth = BluetoothTransport(context, { router.attach(it) }, ::notifyChanged)
        wifi = WifiDirectTransport(context, { router.attach(it) }, ::notifyChanged)
        voicePlayer = VoicePlayer(context, { store.readMedia(it) }, ::notifyChanged)
    }

    private fun loadOrCreateIdentity(): Triple<String, PrivateKey, String> {
        store.readText(IDENTITY)?.let { s ->
            try {
                val o = JSONObject(s)
                return Triple(o.getString("id"), Crypto.privateKeyFromBytes(Crypto.unb64(o.getString("priv"))), o.getString("pub"))
            } catch (e: Exception) { Log.e(TAG, "identity corrupt, regenerating", e) }
        }
        val kp = Crypto.newIdentityKeyPair()
        val id = UUID.randomUUID().toString()
        val pub = Crypto.b64(kp.public.encoded)
        store.writeText(IDENTITY, JSONObject().put("id", id).put("priv", Crypto.b64(kp.private.encoded)).put("pub", pub).toString())
        return Triple(id, kp.private, pub)
    }

    // ======================================================================
    // Observers
    // ======================================================================
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun notifyChanged() {
        if (notifyPosted) return
        notifyPosted = true
        main.postDelayed({ notifyPosted = false; listeners.forEach { it() } }, 60)
    }

    // ======================================================================
    // Lifecycle / transports
    // ======================================================================
    @Synchronized
    fun start() {
        if (started) { applyTransports(); return }
        started = true
        applyTransports()
        scheduler.scheduleWithFixedDelay({ worker.execute { broadcastAnnounce() } }, 2, 20, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ worker.execute { maintenance() } }, 15, 30, TimeUnit.SECONDS)
    }

    /** Call after runtime permissions change or the connection mode changes. */
    fun applyTransports() {
        val mode = repo.mode
        if (mode != ConnectionMode.WIFI_DIRECT) bluetooth.start() else { bluetooth.stop(); closeLinks(TransportKind.BLUETOOTH) }
        if (mode != ConnectionMode.BLUETOOTH) wifi.start() else { wifi.stop(); closeLinks(TransportKind.WIFI_DIRECT) }
        notifyChanged()
    }

    private fun closeLinks(kind: TransportKind) = router.links().filter { it.transport == kind }.forEach { it.close() }

    var mode: ConnectionMode
        get() = repo.mode
        set(v) { repo.mode = v; applyTransports() }

    fun links(): List<LinkInfo> = router.links().map { l ->
        LinkInfo(l.transport, l.address, l.remoteId?.let { repo.contact(it)?.name } ?: l.remoteName)
    }

    fun disconnectAll() {
        router.closeAll()
        wifi.disconnect()
        repo.markAllOffline()
        notifyChanged()
    }

    // ======================================================================
    // Router callbacks
    // ======================================================================
    override fun onLinkUp(link: Link) {
        router.sendOn(link, announcePacket())
        // Tell the newcomer about everyone else we can currently reach.
        lastAnnounces.values.filter { System.currentTimeMillis() - it.ts < ONLINE_WINDOW_MS }
            .forEach { router.sendOn(link, it.withTtl(minOf(it.ttl, Packet.ANNOUNCE_TTL - 1))) }
        notifyChanged()
        worker.execute { flushGroups() }
    }

    override fun onLinkDown(link: Link) {
        if (!router.hasLinks()) repo.markAllOffline()
        notifyChanged()
    }

    override fun onPacket(link: Link, packet: Packet) {
        worker.execute {
            try {
                when (packet.kind) {
                    Packet.KIND_ANNOUNCE -> handleAnnounce(link, packet)
                    Packet.KIND_SECURE -> handleSecure(packet)
                    Packet.KIND_GROUP -> handleGroup(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "packet ${packet.kind} from ${packet.from} failed", e)
            }
        }
    }

    // ======================================================================
    // Presence
    // ======================================================================
    private fun announcePacket() = Packet.create(
        Packet.KIND_ANNOUNCE, myId, Packet.BROADCAST,
        meta = JSONObject().put("name", repo.myName).put("pub", myPublicKey).put("v", PROTOCOL_VERSION),
        ttl = Packet.ANNOUNCE_TTL,
    )

    private fun broadcastAnnounce() { if (router.hasLinks()) router.send(announcePacket()); notifyChanged() }

    private fun handleAnnounce(link: Link, p: Packet) {
        val name = p.meta.optString("name").take(40).ifBlank { "משתמש" }
        val pub = p.meta.optString("pub")
        try { Crypto.publicKeyFromB64(pub) } catch (e: Exception) { return }
        val old = repo.contact(p.from)
        val wasOnline = old != null && isOnline(old)
        val keyChanged = old != null && old.publicKey != pub
        val direct = link.remoteId == p.from
        val bt = if (direct && link.transport == TransportKind.BLUETOOTH) link.address else old?.btAddress
        val c = Contact(p.from, name, pub, System.currentTimeMillis(), bt, keyChanged || (old?.keyChanged == true))
        val persist = old == null || old.name != name || keyChanged || old.btAddress != bt
        repo.upsertContact(c, persist)
        if (keyChanged) pairKeys.keys.removeAll { it.startsWith(p.from) }
        lastAnnounces[p.from] = p
        if (!wasOnline) flushPendingFor(p.from)
        notifyChanged()
    }

    fun isOnline(c: Contact) = c.online && router.hasLinks()

    // ======================================================================
    // Keys & packets
    // ======================================================================
    private fun pairKey(c: Contact): ByteArray =
        pairKeys.getOrPut(c.id + "|" + c.publicKey) { Crypto.pairwiseKey(myPrivateKey, myId, c.publicKey, c.id) }

    private fun sealedPacket(kind: String, to: String, key: ByteArray, payload: ByteArray): Packet {
        val shell = Packet.create(kind, myId, to)
        return Packet(shell.id, kind, myId, to, shell.ttl, shell.ts, shell.meta, Crypto.seal(key, payload, shell.aad()))
    }

    /** Sends a private control/content payload. Returns true if it left this phone. */
    private fun sendPrivate(contactId: String, json: JSONObject, binary: ByteArray? = null, requireOnline: Boolean = true): Boolean {
        val c = repo.contact(contactId) ?: return false
        if (requireOnline && !isOnline(c)) return false
        val p = sealedPacket(Packet.KIND_SECURE, contactId, pairKey(c), Payload.pack(json, binary))
        return router.send(p) > 0
    }

    private fun sendToGroup(g: Group, json: JSONObject, binary: ByteArray? = null): Boolean {
        if (!router.hasLinks()) return false
        val p = sealedPacket(Packet.KIND_GROUP, g.conversationId, Crypto.unb64(g.key), Payload.pack(json, binary))
        return router.send(p) > 0
    }

    // ======================================================================
    // Outgoing
    // ======================================================================
    fun sendText(conv: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        newOutgoing(conv, MessageType.TEXT, t.take(4000), null, 0)
    }

    fun sendImage(conv: String, jpeg: ByteArray) = newOutgoing(conv, MessageType.IMAGE, "", jpeg, 0)
    fun sendVoice(conv: String, audio: ByteArray, durationMs: Long) = newOutgoing(conv, MessageType.VOICE, "", audio, durationMs)

    private fun newOutgoing(conv: String, type: MessageType, text: String, media: ByteArray?, dur: Long) {
        val m = ChatMessage(
            id = UUID.randomUUID().toString(), conversationId = conv, senderId = myId, senderName = repo.myName,
            type = type, text = text, hasMedia = media != null, durationMs = dur, status = MessageStatus.PENDING,
        )
        worker.execute {
            if (media != null) store.writeMedia(m.id, media)
            repo.addMessage(m)
            notifyChanged()
            dispatch(m)
        }
        notifyChanged()
    }

    private fun contentJson(m: ChatMessage) = JSONObject()
        .put("t", "msg").put("id", m.id).put("type", m.type.name).put("text", m.text)
        .put("dur", m.durationMs).put("ts", m.timestamp).put("name", repo.myName).put("ed", m.edited)

    /** (Re)sends one of my messages. */
    private fun dispatch(m: ChatMessage) {
        if (m.deleted) { repo.updateMessage(m.conversationId, m.id) { it.copy(status = MessageStatus.SENT, syncPending = false) }; return }
        val media = if (m.hasMedia) store.readMedia(m.id) else null
        val ok = if (isGroupConversation(m.conversationId)) {
            val g = repo.group(groupIdOf(m.conversationId)) ?: return
            sendToGroup(g, contentJson(m), media)
        } else {
            sendPrivate(m.conversationId, contentJson(m), media)
        }
        if (ok) {
            repo.updateMessage(m.conversationId, m.id) {
                it.copy(status = if (it.status == MessageStatus.DELIVERED) it.status else MessageStatus.SENT, syncPending = false)
            }
            notifyChanged()
        }
    }

    fun editMessage(conv: String, id: String, newText: String) {
        val t = newText.trim()
        if (t.isEmpty()) return
        val m = repo.updateMessage(conv, id) {
            if (it.senderId != myId || it.deleted || it.type != MessageType.TEXT) it else it.copy(text = t, edited = true, syncPending = true)
        } ?: return
        notifyChanged()
        worker.execute { syncChange(m) }
    }

    fun deleteMessage(conv: String, id: String) {
        val m = repo.updateMessage(conv, id) {
            if (it.senderId != myId) it else it.copy(deleted = true, text = "", hasMedia = false, syncPending = true)
        } ?: return
        store.deleteMedia(id)
        if (voicePlayer.playingId == id) voicePlayer.stop()
        notifyChanged()
        worker.execute { syncChange(m) }
    }

    /** Deletes a message (anyone's) only on this phone. */
    fun deleteLocally(conv: String, id: String) {
        repo.updateMessage(conv, id) { it.copy(deleted = true, text = "", hasMedia = false, syncPending = false) }
        store.deleteMedia(id)
        notifyChanged()
    }

    private fun syncChange(m: ChatMessage) {
        if (!m.syncPending || m.senderId != myId) return
        if (m.status == MessageStatus.PENDING) {
            // Never left the phone: the next resend carries the current state.
            if (m.deleted) repo.updateMessage(m.conversationId, m.id) { it.copy(syncPending = false, status = MessageStatus.SENT) }
            return
        }
        val json = if (m.deleted) JSONObject().put("t", "del").put("id", m.id)
        else JSONObject().put("t", "edit").put("id", m.id).put("text", m.text)
        val ok = if (isGroupConversation(m.conversationId)) {
            repo.group(groupIdOf(m.conversationId))?.let { sendToGroup(it, json) } ?: false
        } else sendPrivate(m.conversationId, json)
        if (ok) repo.updateMessage(m.conversationId, m.id) { it.copy(syncPending = false) }
        notifyChanged()
    }

    // ======================================================================
    // Groups
    // ======================================================================
    fun createGroup(name: String, memberIds: List<String>): String {
        val members = listOf(GroupMember(myId, repo.myName)) +
            memberIds.mapNotNull { id -> repo.contact(id)?.let { GroupMember(it.id, it.name) } }
        val g = Group(
            id = UUID.randomUUID().toString(), name = name.trim().take(50), creatorId = myId,
            members = members, key = Crypto.b64(Crypto.randomBytes(32)), pendingInvites = memberIds.toSet(),
        )
        repo.upsertGroup(g)
        worker.execute { memberIds.forEach { sendInvite(g, it) } }
        notifyChanged()
        return g.conversationId
    }

    private fun sendInvite(g: Group, contactId: String) {
        val json = JSONObject().put("t", "inv").put("gid", g.id).put("name", g.name).put("creator", g.creatorId)
            .put("key", g.key)
            .put("members", JSONArray().also { a -> g.members.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } })
        sendPrivate(contactId, json)
    }

    fun leaveGroup(groupId: String) {
        repo.removeGroup(groupId)
        notifyChanged()
    }

    private fun handleInvite(from: String, j: JSONObject) {
        val gid = j.getString("gid")
        val ma = j.getJSONArray("members")
        val members = (0 until ma.length()).map { ma.getJSONObject(it) }.map { GroupMember(it.getString("id"), it.optString("name")) }
        if (members.none { it.id == myId } || members.none { it.id == from }) return
        if (repo.group(gid) == null) {
            repo.upsertGroup(Group(gid, j.optString("name", "קבוצה"), j.optString("creator", from), members, j.getString("key")))
            val sys = ChatMessage(
                id = "join-$gid", conversationId = groupConversationId(gid), senderId = from,
                senderName = repo.contact(from)?.name ?: "", text = "צירף/ה אותך לקבוצה", status = MessageStatus.RECEIVED,
            )
            repo.addMessage(sys)
            onIncomingMessage?.invoke(sys, j.optString("name", "קבוצה"))
        }
        sendPrivate(from, JSONObject().put("t", "invack").put("gid", gid))
        notifyChanged()
    }

    // ======================================================================
    // Incoming
    // ======================================================================
    private fun handleSecure(p: Packet) {
        val c = repo.contact(p.from) ?: return // no key yet; sender will retry after announce
        val plain = try { Crypto.open(pairKey(c), p.body, p.aad()) } catch (e: Exception) {
            Log.w(TAG, "cannot decrypt packet from ${c.name}"); return
        }
        val (j, bin) = Payload.unpack(plain)
        when (j.optString("t")) {
            "msg" -> {
                receiveContent(p.from, p.from, j, bin)
                sendPrivate(p.from, JSONObject().put("t", "ack").put("ids", JSONArray().put(j.getString("id"))), requireOnline = false)
            }
            "ack" -> {
                val ids = j.optJSONArray("ids") ?: JSONArray()
                for (i in 0 until ids.length()) repo.updateMessage(p.from, ids.getString(i)) {
                    if (it.senderId == myId) it.copy(status = MessageStatus.DELIVERED) else it
                }
            }
            "edit" -> applyEdit(p.from, p.from, j)
            "del" -> applyDelete(p.from, p.from, j)
            "inv" -> handleInvite(p.from, j)
            "invack" -> repo.updateGroup(j.getString("gid")) { it.copy(pendingInvites = it.pendingInvites - p.from) }
        }
        notifyChanged()
    }

    private fun handleGroup(p: Packet) {
        val g = repo.group(groupIdOf(p.to)) ?: return
        if (g.members.none { it.id == p.from }) return
        val plain = try { Crypto.open(Crypto.unb64(g.key), p.body, p.aad()) } catch (e: Exception) { return }
        val (j, bin) = Payload.unpack(plain)
        when (j.optString("t")) {
            "msg" -> receiveContent(g.conversationId, p.from, j, bin)
            "edit" -> applyEdit(g.conversationId, p.from, j)
            "del" -> applyDelete(g.conversationId, p.from, j)
        }
        notifyChanged()
    }

    private fun receiveContent(conv: String, senderId: String, j: JSONObject, bin: ByteArray?) {
        val id = j.getString("id")
        val existing = repo.message(conv, id)
        if (existing != null) {
            // Resend of a message we already have: pick up a later edit, if any.
            val text = j.optString("text")
            if (!existing.deleted && existing.text != text && existing.senderId == senderId) {
                repo.updateMessage(conv, id) { it.copy(text = text, edited = true) }
            }
            return
        }
        val type = runCatching { MessageType.valueOf(j.optString("type")) }.getOrDefault(MessageType.TEXT)
        if (bin != null) store.writeMedia(id, bin)
        val now = System.currentTimeMillis()
        val m = ChatMessage(
            id = id, conversationId = conv, senderId = senderId,
            senderName = j.optString("name").ifBlank { repo.contact(senderId)?.name ?: "?" },
            type = type, text = j.optString("text"), hasMedia = bin != null, durationMs = j.optLong("dur"),
            timestamp = j.optLong("ts", now).coerceAtMost(now), edited = j.optBoolean("ed"),
            status = MessageStatus.RECEIVED,
        )
        if (!repo.addMessage(m)) return
        if (!(uiVisible && activeConversation == conv)) {
            repo.incUnread(conv)
            val title = if (isGroupConversation(conv)) repo.group(groupIdOf(conv))?.name ?: "קבוצה" else m.senderName
            onIncomingMessage?.invoke(m, title)
        }
    }

    private fun applyEdit(conv: String, senderId: String, j: JSONObject) {
        repo.updateMessage(conv, j.getString("id")) {
            if (it.senderId == senderId && !it.deleted) it.copy(text = j.optString("text"), edited = true) else it
        }
    }

    private fun applyDelete(conv: String, senderId: String, j: JSONObject) {
        val id = j.getString("id")
        val m = repo.updateMessage(conv, id) {
            if (it.senderId == senderId) it.copy(deleted = true, text = "", hasMedia = false) else it
        }
        if (m?.deleted == true) {
            store.deleteMedia(id)
            if (voicePlayer.playingId == id) main.post { voicePlayer.stop() }
        }
    }

    // ======================================================================
    // Retry / maintenance
    // ======================================================================
    private fun flushPendingFor(contactId: String) {
        val now = System.currentTimeMillis()
        repo.findMessages(contactId) { it.senderId == myId }.forEach { m ->
            when {
                m.status == MessageStatus.PENDING -> dispatch(m)
                m.status == MessageStatus.SENT && !m.deleted && now - m.timestamp > 10_000 -> dispatch(m)
            }
            if (m.syncPending) syncChange(repo.message(contactId, m.id) ?: m)
        }
        repo.groups().filter { contactId in it.pendingInvites }.forEach { sendInvite(it, contactId) }
    }

    private fun flushGroups() {
        if (!router.hasLinks()) return
        repo.groups().forEach { g ->
            repo.findMessages(g.conversationId) { it.senderId == myId && (it.status == MessageStatus.PENDING || it.syncPending) }
                .forEach { m -> if (m.status == MessageStatus.PENDING) dispatch(m) else syncChange(m) }
        }
    }

    private fun maintenance() {
        val now = System.currentTimeMillis()
        // Resend undelivered private messages to reachable contacts.
        repo.contacts().filter { isOnline(it) }.forEach { c ->
            repo.findMessages(c.id) { it.senderId == myId && !it.deleted && it.status != MessageStatus.DELIVERED && now - it.timestamp > 30_000 }
                .takeLast(20).forEach { dispatch(it) }
            repo.findMessages(c.id) { it.senderId == myId && it.syncPending }.forEach { syncChange(it) }
            repo.groups().filter { c.id in it.pendingInvites }.forEach { sendInvite(it, c.id) }
        }
        flushGroups()
        reconnectBluetooth()
        notifyChanged()
    }

    /** Quietly reconnects to known NearChat phones over Bluetooth, one per cycle. */
    private fun reconnectBluetooth() {
        if (repo.mode == ConnectionMode.WIFI_DIRECT || !bluetooth.enabled || !bluetooth.hasPermissions()) return
        val now = System.currentTimeMillis()
        val candidate = repo.contacts()
            .filter { it.btAddress != null && !isOnline(it) && !bluetooth.isConnecting(it.btAddress) }
            .filter { now - (btAttempts[it.id] ?: 0L) > 90_000 }
            .minByOrNull { btAttempts[it.id] ?: 0L } ?: return
        btAttempts[candidate.id] = now
        bluetooth.connect(candidate.btAddress!!, quiet = true)
    }

    // ======================================================================
    // Queries for the UI
    // ======================================================================
    fun contacts(): List<Contact> = repo.contacts().sortedWith(compareByDescending<Contact> { isOnline(it) }.thenBy { it.name })
    fun contact(id: String) = repo.contact(id)
    fun groups() = repo.groups()
    fun group(id: String) = repo.group(id)
    fun messages(conv: String) = repo.messages(conv)
    fun loadMedia(id: String): ByteArray? = store.readMedia(id)

    fun conversations(): List<ConversationSummary> {
        val out = ArrayList<ConversationSummary>()
        val convIds = repo.conversationIds()
        repo.contacts().filter { it.id in convIds }.forEach { c ->
            out.add(ConversationSummary(c.id, c.name, false, isOnline(c), repo.lastMessage(c.id), repo.unread(c.id)))
        }
        repo.groups().forEach { g ->
            out.add(ConversationSummary(g.conversationId, g.name, true, false, repo.lastMessage(g.conversationId), repo.unread(g.conversationId)))
        }
        return out.sortedByDescending { s -> s.lastMessage?.timestamp ?: repo.group(groupIdOf(s.conversationId))?.createdAt ?: 0L }
    }

    fun conversationTitle(conv: String): String =
        if (isGroupConversation(conv)) repo.group(groupIdOf(conv))?.name ?: "קבוצה" else repo.contact(conv)?.name ?: "שיחה"

    fun openConversation(conv: String?) {
        activeConversation = conv
        if (conv != null) repo.clearUnread(conv)
        notifyChanged()
    }

    fun deleteConversation(conv: String) {
        if (isGroupConversation(conv)) repo.removeGroup(groupIdOf(conv)) else repo.deleteConversation(conv)
        notifyChanged()
    }

    var myName: String
        get() = repo.myName
        set(v) {
            val n = v.trim().take(40)
            if (n.isEmpty() || n == repo.myName) return
            repo.myName = n
            worker.execute { broadcastAnnounce() }
        }

    fun safetyNumber(contactId: String): String? = repo.contact(contactId)?.let { Crypto.safetyNumber(myPublicKey, it.publicKey) }
    fun myFingerprint(): String = Crypto.shortFingerprint(myPublicKey)

    fun acknowledgeKeyChange(contactId: String) {
        repo.contact(contactId)?.let { repo.upsertContact(it.copy(keyChanged = false)) }
        notifyChanged()
    }

    fun flush() = repo.flush()

    companion object {
        private const val TAG = "ChatCore"
        private const val IDENTITY = "identity.json"
        const val PROTOCOL_VERSION = 1
    }
}
