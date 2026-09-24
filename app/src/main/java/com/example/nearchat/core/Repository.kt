package com.example.nearchat.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-memory state backed by encrypted files (see [SecureStore]). All access is
 * synchronised; writes are batched and flushed on a background thread.
 */
class Repository(private val store: SecureStore) {
    private val lock = Any()
    private val contacts = LinkedHashMap<String, Contact>()
    private val groups = LinkedHashMap<String, Group>()
    private val messages = HashMap<String, MutableList<ChatMessage>>()
    private val unread = HashMap<String, Int>()
    private var name: String = "משתמש"
    private var connMode: ConnectionMode = ConnectionMode.AUTO

    private val dirty = HashSet<String>()
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "repo-saver").apply { isDaemon = true } }
    @Volatile private var flushScheduled = false

    init { load() }

    // ---------- profile ----------
    var myName: String
        get() = synchronized(lock) { name }
        set(v) { synchronized(lock) { name = v; markDirty(PROFILE) } }

    var mode: ConnectionMode
        get() = synchronized(lock) { connMode }
        set(v) { synchronized(lock) { connMode = v; markDirty(PROFILE) } }

    // ---------- contacts ----------
    fun contacts(): List<Contact> = synchronized(lock) { contacts.values.toList() }
    fun contact(id: String): Contact? = synchronized(lock) { contacts[id] }
    fun upsertContact(c: Contact, persist: Boolean = true) = synchronized(lock) {
        contacts[c.id] = c
        if (persist) markDirty(CONTACTS)
    }
    fun markAllOffline() = synchronized(lock) {
        contacts.replaceAll { _, c -> c.copy(lastSeen = minOf(c.lastSeen, System.currentTimeMillis() - ONLINE_WINDOW_MS)) }
    }

    // ---------- groups ----------
    fun groups(): List<Group> = synchronized(lock) { groups.values.toList() }
    fun group(id: String): Group? = synchronized(lock) { groups[id] }
    fun upsertGroup(g: Group) = synchronized(lock) { groups[g.id] = g; markDirty(GROUPS) }
    fun updateGroup(id: String, f: (Group) -> Group): Group? = synchronized(lock) {
        val g = groups[id] ?: return null
        val n = f(g); groups[id] = n; markDirty(GROUPS); n
    }
    fun removeGroup(id: String) = synchronized(lock) {
        groups.remove(id); markDirty(GROUPS)
        deleteConversationLocked(groupConversationId(id))
    }

    // ---------- messages ----------
    fun conversationIds(): Set<String> = synchronized(lock) { messages.keys.toSet() }
    fun messages(conv: String): List<ChatMessage> = synchronized(lock) { messages[conv]?.toList() ?: emptyList() }
    fun lastMessage(conv: String): ChatMessage? = synchronized(lock) { messages[conv]?.lastOrNull() }
    fun message(conv: String, id: String): ChatMessage? = synchronized(lock) { messages[conv]?.firstOrNull { it.id == id } }

    /** Returns false if a message with this id already exists (duplicate delivery). */
    fun addMessage(m: ChatMessage): Boolean = synchronized(lock) {
        val list = messages.getOrPut(m.conversationId) { mutableListOf() }
        if (list.any { it.id == m.id }) return false
        val idx = list.indexOfLast { it.timestamp <= m.timestamp }
        list.add(idx + 1, m)
        markDirty(convFile(m.conversationId))
        true
    }

    fun updateMessage(conv: String, id: String, f: (ChatMessage) -> ChatMessage): ChatMessage? = synchronized(lock) {
        val list = messages[conv] ?: return null
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val n = f(list[i])
        if (n != list[i]) { list[i] = n; markDirty(convFile(conv)) }
        n
    }

    fun findMessages(conv: String, pred: (ChatMessage) -> Boolean): List<ChatMessage> =
        synchronized(lock) { messages[conv]?.filter(pred) ?: emptyList() }

    fun deleteConversation(conv: String) = synchronized(lock) { deleteConversationLocked(conv) }

    private fun deleteConversationLocked(conv: String) {
        messages.remove(conv)?.forEach { if (it.hasMedia) store.deleteMedia(it.id) }
        unread.remove(conv)
        markDirty(convFile(conv))
        markDirty(PROFILE)
    }

    fun unread(conv: String): Int = synchronized(lock) { unread[conv] ?: 0 }
    fun incUnread(conv: String) = synchronized(lock) { unread[conv] = (unread[conv] ?: 0) + 1; markDirty(PROFILE) }
    fun clearUnread(conv: String) = synchronized(lock) { if (unread.remove(conv) != null) markDirty(PROFILE) }

    // ---------- persistence ----------
    private fun convFile(conv: String) = "conv_" + conv.replace(":", "_") + ".json"

    private fun markDirty(file: String) {
        dirty.add(file)
        if (!flushScheduled) {
            flushScheduled = true
            saver.schedule({ flush() }, 400, TimeUnit.MILLISECONDS)
        }
    }

    fun flush() {
        val jobs = ArrayList<Pair<String, String?>>()
        synchronized(lock) {
            flushScheduled = false
            for (f in dirty) jobs.add(f to serializeLocked(f))
            dirty.clear()
        }
        for ((file, content) in jobs) {
            try {
                if (content == null) store.delete(file) else store.writeText(file, content)
            } catch (e: Exception) { Log.e(TAG, "save $file failed", e) }
        }
    }

    private fun serializeLocked(file: String): String? = when {
        file == PROFILE -> JSONObject().put("name", name).put("mode", connMode.name)
            .put("unread", JSONObject().also { o -> unread.forEach { (k, v) -> o.put(k, v) } }).toString()
        file == CONTACTS -> JSONArray().also { a -> contacts.values.forEach { a.put(it.toJson()) } }.toString()
        file == GROUPS -> JSONArray().also { a -> groups.values.forEach { a.put(it.toJson()) } }.toString()
        file.startsWith("conv_") -> {
            val conv = messages.keys.firstOrNull { convFile(it) == file }
            val list = conv?.let { messages[it] }
            if (conv == null || list == null || list.isEmpty()) null
            else JSONObject().put("conv", conv).put("msgs", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }).toString()
        }
        else -> null
    }

    private fun load() {
        try {
            store.readText(PROFILE)?.let { s ->
                val o = JSONObject(s)
                name = o.optString("name", name)
                connMode = runCatching { ConnectionMode.valueOf(o.optString("mode")) }.getOrDefault(ConnectionMode.AUTO)
                o.optJSONObject("unread")?.let { u -> u.keys().forEach { k -> unread[k] = u.optInt(k) } }
            }
            store.readText(CONTACTS)?.let { s ->
                val a = JSONArray(s)
                for (i in 0 until a.length()) Contact.fromJson(a.getJSONObject(i)).let { contacts[it.id] = it.copy(lastSeen = 0) }
            }
            store.readText(GROUPS)?.let { s ->
                val a = JSONArray(s)
                for (i in 0 until a.length()) Group.fromJson(a.getJSONObject(i)).let { groups[it.id] = it }
            }
            for (f in store.list("conv_")) {
                val o = JSONObject(store.readText(f) ?: continue)
                val conv = o.getString("conv")
                val a = o.getJSONArray("msgs")
                messages[conv] = (0 until a.length()).map { ChatMessage.fromJson(a.getJSONObject(it)) }.toMutableList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }

    companion object {
        private const val TAG = "Repository"
        private const val PROFILE = "profile.json"
        private const val CONTACTS = "contacts.json"
        private const val GROUPS = "groups.json"
    }
}
