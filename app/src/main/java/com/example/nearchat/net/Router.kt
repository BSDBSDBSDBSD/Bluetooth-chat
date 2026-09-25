package com.example.nearchat.net

import com.example.nearchat.core.GROUP_PREFIX
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Small mesh router. Every phone forwards packets that are not addressed only to
 * itself, with a TTL and duplicate suppression. This lets three or more phones
 * talk even when they are not all directly connected (e.g. Wi-Fi Direct clients
 * only see the group owner; a Bluetooth phone can bridge to a Wi-Fi Direct group).
 */
class Router(private val myId: String, private val listener: Listener) {
    interface Listener {
        fun onLinkUp(link: Link)
        fun onLinkDown(link: Link)
        fun onPacket(link: Link, packet: Packet)
    }

    private val links = CopyOnWriteArrayList<Link>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 5000
    }

    fun links(): List<Link> = links.filter { !it.closed }
    fun hasLinks(): Boolean = links.any { !it.closed }
    fun isDirectNeighbour(userId: String) = links.any { !it.closed && it.remoteId == userId }

    fun attach(link: Link) {
        links.add(link)
        listener.onLinkUp(link) // queues our announce first
        link.start(::receive) { l ->
            links.remove(l)
            listener.onLinkDown(l)
        }
    }

    fun closeAll() { links.forEach { it.close() } }

    private fun markSeen(id: String): Boolean = synchronized(seen) {
        if (seen.containsKey(id)) false else { seen[id] = true; true }
    }

    /** Sends a new packet originated by this phone. Returns number of links it went out on. */
    fun send(p: Packet): Int {
        markSeen(p.id)
        val live = links()
        val direct = if (p.to != Packet.BROADCAST && !p.to.startsWith(GROUP_PREFIX)) live.filter { it.remoteId == p.to } else emptyList()
        val targets = direct.ifEmpty { live }
        return targets.count { it.send(p) }
    }

    fun sendOn(link: Link, p: Packet) = link.send(p)

    private fun receive(link: Link, p: Packet) {
        if (p.kind == Packet.KIND_ANNOUNCE && p.ttl == Packet.ANNOUNCE_TTL && link.remoteId == null && p.from != myId) {
            link.remoteId = p.from
            link.remoteName = p.meta.optString("name")
        }
        if (p.from == myId) return
        if (!markSeen(p.id)) return

        if (p.ttl > 1 && p.to != myId) {
            val fwd = p.withTtl(p.ttl - 1)
            val others = links().filter { it !== link }
            val direct = others.filter { it.remoteId == p.to }
            direct.ifEmpty { others }.forEach { it.send(fwd) }
        }
        if (p.to == myId || p.to == Packet.BROADCAST || p.to.startsWith(GROUP_PREFIX)) {
            listener.onPacket(link, p)
        }
    }
}
