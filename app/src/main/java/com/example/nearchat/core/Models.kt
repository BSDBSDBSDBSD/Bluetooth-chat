package com.example.nearchat.core

import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionMode { AUTO, WIFI_DIRECT, BLUETOOTH }
enum class MessageType { TEXT, IMAGE, VOICE }
enum class MessageStatus { PENDING, SENT, DELIVERED, RECEIVED }
enum class TransportKind { BLUETOOTH, WIFI_DIRECT }

/** How long after the last announce a contact is still considered reachable. */
const val ONLINE_WINDOW_MS = 65_000L

data class Contact(
    val id: String,
    val name: String,
    val publicKey: String,
    val lastSeen: Long = 0,
    val btAddress: String? = null,
    val keyChanged: Boolean = false,
    /** Hash of the profile picture we have stored for this contact ("" = none). */
    val avatar: String = "",
) {
    val online: Boolean get() = System.currentTimeMillis() - lastSeen < ONLINE_WINDOW_MS

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("pub", publicKey)
        .put("seen", lastSeen).put("bt", btAddress ?: "").put("kc", keyChanged).put("av", avatar)

    companion object {
        fun fromJson(o: JSONObject) = Contact(
            id = o.getString("id"),
            name = o.optString("name", "?"),
            publicKey = o.getString("pub"),
            lastSeen = o.optLong("seen", 0),
            btAddress = o.optString("bt", "").ifEmpty { null },
            keyChanged = o.optBoolean("kc", false),
            avatar = o.optString("av", ""),
        )
    }
}

data class GroupMember(val id: String, val name: String)

data class Group(
    val id: String,
    val name: String,
    val creatorId: String,
    val members: List<GroupMember>,
    /** Base64 AES-256 group key, shared with members over pairwise encrypted invites. */
    val key: String,
    val pendingInvites: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val conversationId: String get() = groupConversationId(id)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("creator", creatorId)
        .put("members", JSONArray().also { a -> members.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } })
        .put("key", key)
        .put("pending", JSONArray(pendingInvites.toList()))
        .put("created", createdAt)

    companion object {
        fun fromJson(o: JSONObject): Group {
            val ma = o.optJSONArray("members") ?: JSONArray()
            val members = (0 until ma.length()).map { ma.getJSONObject(it) }.map { GroupMember(it.getString("id"), it.optString("name", "?")) }
            val pa = o.optJSONArray("pending") ?: JSONArray()
            return Group(
                id = o.getString("id"),
                name = o.optString("name", "?"),
                creatorId = o.optString("creator", ""),
                members = members,
                key = o.getString("key"),
                pendingInvites = (0 until pa.length()).map { pa.getString(it) }.toSet(),
                createdAt = o.optLong("created", 0),
            )
        }
    }
}

const val GROUP_PREFIX = "g:"
fun groupConversationId(groupId: String) = GROUP_PREFIX + groupId
fun isGroupConversation(convId: String) = convId.startsWith(GROUP_PREFIX)
fun groupIdOf(convId: String) = convId.removePrefix(GROUP_PREFIX)

data class ChatMessage(
    val id: String,
    /** Contact id for private chats, "g:<groupId>" for groups. */
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val type: MessageType = MessageType.TEXT,
    val text: String = "",
    val hasMedia: Boolean = false,
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val status: MessageStatus = MessageStatus.PENDING,
    /** An edit/delete that has not reached the other side yet. */
    val syncPending: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("conv", conversationId).put("sid", senderId).put("sname", senderName)
        .put("type", type.name).put("text", text).put("media", hasMedia).put("dur", durationMs)
        .put("ts", timestamp).put("ed", edited).put("del", deleted).put("st", status.name).put("sync", syncPending)

    companion object {
        fun fromJson(o: JSONObject) = ChatMessage(
            id = o.getString("id"),
            conversationId = o.getString("conv"),
            senderId = o.getString("sid"),
            senderName = o.optString("sname", "?"),
            type = runCatching { MessageType.valueOf(o.optString("type")) }.getOrDefault(MessageType.TEXT),
            text = o.optString("text", ""),
            hasMedia = o.optBoolean("media", false),
            durationMs = o.optLong("dur", 0),
            timestamp = o.optLong("ts", 0),
            edited = o.optBoolean("ed", false),
            deleted = o.optBoolean("del", false),
            status = runCatching { MessageStatus.valueOf(o.optString("st")) }.getOrDefault(MessageStatus.SENT),
            syncPending = o.optBoolean("sync", false),
        )
    }
}

data class ConversationSummary(
    val conversationId: String,
    val title: String,
    val isGroup: Boolean,
    val online: Boolean,
    val lastMessage: ChatMessage?,
    val unread: Int,
    /** Profile picture hash of the other person (private chats only). */
    val avatar: String = "",
)

/** Storage id of a user's profile picture (mine is stored under my own id). */
fun avatarMediaId(userId: String) = "avatar-$userId"

data class LinkInfo(val transport: TransportKind, val address: String, val remoteName: String?)

data class FoundDevice(val name: String, val address: String, val detail: String = "")

fun ChatMessage.previewText(): String = when {
    deleted -> "הודעה נמחקה"
    type == MessageType.IMAGE -> "📷 תמונה"
    type == MessageType.VOICE -> "🎤 הודעה קולית"
    else -> text
}
