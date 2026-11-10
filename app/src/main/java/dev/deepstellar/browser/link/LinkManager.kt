package dev.deepstellar.browser.link

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.deepstellar.browser.MainActivity
import dev.deepstellar.browser.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Linking with Deep Stellar Station (desktop), protocol version 1: same network only.
 *
 * Everything (this phone's key, the linked computers, the shared-items list and the send queue) is
 * kept in one file encrypted with a key from the Android Keystore, like encrypted history.
 */
object LinkManager {
    private const val TAG = "Link"
    private const val SERVICE = "_dsstation._tcp."
    private const val KS_ALIAS = "deep_stellar_link_v1"
    private const val NOTIF_ID = 99
    const val CHANNEL = "link"

    class Station(val fp: String, val pub: String, var name: String, var addrs: List<String>) {
        @Volatile var online = false
        @Volatile var unknownToComputer = false
    }

    class Item(
        val id: String, val ts: Long, val kind: String, val url: String, val title: String, val text: String,
        val peerName: String, val outgoing: Boolean,
    )

    private lateinit var app: Context
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val socks = ConcurrentHashMap<String, Conn>()
    private val keys = ConcurrentHashMap<String, SecretKeySpec>()
    private val listeners = mutableListOf<() -> Unit>()

    // ---- stored state (guarded by this)
    private var myPub = ""
    private var myPrivPkcs8 = ByteArray(0)
    private var myName = Build.MODEL ?: "Android"
    private val stations = LinkedHashMap<String, Station>()
    private val queue = HashMap<String, MutableList<JSONObject>>() // fp -> bodies awaiting ack
    private val items = ArrayList<Item>()
    private val seen = ArrayDeque<String>()
    @Volatile private var loaded = false

    private class Conn(val socket: Socket, val out: DataOutputStream) {
        fun send(o: JSONObject) {
            val b = o.toString().toByteArray(Charsets.UTF_8)
            synchronized(out) { out.writeInt(b.size); out.write(b); out.flush() }
        }
    }

    // ================================================================ setup & storage

    fun init(context: Context) {
        app = context.applicationContext
        io.execute { load() }
    }

    private val file get() = File(app.filesDir, "link.enc")

    @Synchronized
    private fun load() {
        if (loaded) return
        runCatching {
            if (file.exists()) {
                val blob = file.readBytes()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
                val o = JSONObject(String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8))
                myPub = o.getString("pub")
                myPrivPkcs8 = LinkCrypto.unb64(o.getString("priv"))
                myName = o.optString("name", myName)
                o.optJSONArray("stations")?.let { a ->
                    for (i in 0 until a.length()) {
                        val s = a.getJSONObject(i)
                        val addrs = s.optJSONArray("addrs")?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
                        stations[s.getString("fp")] = Station(s.getString("fp"), s.getString("pub"), s.optString("name", "Computer"), addrs)
                    }
                }
                o.optJSONObject("queue")?.let { q ->
                    for (k in q.keys()) {
                        val arr = q.getJSONArray(k)
                        queue[k] = (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
                    }
                }
                o.optJSONArray("items")?.let { a ->
                    for (i in 0 until a.length()) {
                        val m = a.getJSONObject(i)
                        items += Item(m.getString("id"), m.getLong("ts"), m.optString("kind"), m.optString("url"), m.optString("title"),
                            m.optString("text"), m.optString("peer"), m.optBoolean("out"))
                    }
                }
                o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen.addLast(a.getString(i)) }
            }
        }.onFailure { Log.w(TAG, "link state unreadable, starting fresh: ${it.javaClass.simpleName}") }
        if (myPub.isEmpty()) {
            val kp = LinkCrypto.newKeyPair()
            myPub = LinkCrypto.b64(LinkCrypto.rawPublic(kp.public as java.security.interfaces.ECPublicKey))
            myPrivPkcs8 = kp.private.encoded
            save()
        }
        val priv = LinkCrypto.privateFromPkcs8(myPrivPkcs8)
        for (s in stations.values) runCatching { keys[s.fp] = LinkCrypto.sharedKey(priv, myPub, s.pub) }
        loaded = true
        changed()
    }

    @Synchronized
    private fun save() {
        runCatching {
            val o = JSONObject()
                .put("v", 1).put("pub", myPub).put("priv", LinkCrypto.b64(myPrivPkcs8)).put("name", myName)
            o.put("stations", JSONArray().also { a ->
                stations.values.forEach { s -> a.put(JSONObject().put("fp", s.fp).put("pub", s.pub).put("name", s.name).put("addrs", JSONArray(s.addrs))) }
            })
            o.put("queue", JSONObject().also { q -> queue.forEach { (k, v) -> q.put(k, JSONArray(v)) } })
            o.put("items", JSONArray().also { a ->
                items.take(200).forEach { m ->
                    a.put(JSONObject().put("id", m.id).put("ts", m.ts).put("kind", m.kind).put("url", m.url).put("title", m.title)
                        .put("text", m.text).put("peer", m.peerName).put("out", m.outgoing))
                }
            })
            o.put("seen", JSONArray(seen.toList()))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, wrapKey())
            val ct = c.doFinal(o.toString().toByteArray(Charsets.UTF_8))
            val tmp = File(app.filesDir, "link.enc.tmp")
            tmp.writeBytes(c.iv + ct)
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "save failed: ${it.javaClass.simpleName}") }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private val myFp get() = LinkCrypto.fingerprint(myPub)

    // ================================================================ public API (main thread)

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    fun stations(): List<Station> = synchronized(this) { stations.values.toList() }
    fun items(): List<Item> = synchronized(this) { items.toList() }
    fun pending(fp: String): Int = synchronized(this) { queue[fp]?.size ?: 0 }
    val hasStations: Boolean get() = synchronized(this) { stations.isNotEmpty() }

    /** Connects to every linked computer that isn't connected yet (needs Wi-Fi or Ethernet). */
    fun connectAll() {
        if (!::app.isInitialized) return
        io.execute {
            load()
            if (!onLocalNetwork()) return@execute
            for (s in stations()) if (!s.online && !socks.containsKey(s.fp)) io.execute { connect(s) }
        }
    }

    fun disconnectAll() {
        for ((_, c) in socks) runCatching { c.socket.close() }
        socks.clear()
    }

    fun sendLink(fp: String, url: String, title: String) = enqueue(fp, JSONObject().put("kind", "link").put("url", url).put("title", title))
    fun sendText(fp: String, text: String) = enqueue(fp, JSONObject().put("kind", "text").put("text", text))

    fun removeItem(id: String) { synchronized(this) { items.removeAll { it.id == id }; save() }; changed() }
    fun clearItems() { synchronized(this) { items.clear(); save() }; changed() }

    fun unlink(fp: String) {
        socks.remove(fp)?.let { runCatching { it.socket.close() } }
        synchronized(this) { stations.remove(fp); queue.remove(fp); keys.remove(fp); save() }
        changed()
    }

    /** Pairs with the computer showing the QR code [qr]. Calls [done] on the main thread. */
    fun pair(qr: String, done: (ok: Boolean, message: String) -> Unit) {
        io.execute {
            load()
            val result = runCatching { pairBlocking(qr) }
            main.post { result.fold({ done(true, "Linked with $it") }, { done(false, it.message ?: "Couldn't link") }) }
        }
    }

    // ================================================================ pairing

    private fun pairBlocking(qr: String): String {
        val u = Uri.parse(qr)
        require(u.scheme == "dsstation" && u.host == "pair") { "That isn't a Deep Stellar Station code" }
        require(u.getQueryParameter("v") == "1") { "This Station needs a newer Deep Stellar Browsing" }
        val deskPub = u.getQueryParameter("k") ?: error("Damaged code")
        val token = LinkCrypto.unb64(u.getQueryParameter("t") ?: error("Damaged code"))
        val addrs = (u.getQueryParameter("a") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val deskName = u.getQueryParameter("n") ?: "Computer"
        val deskFp = LinkCrypto.fingerprint(deskPub)
        if (!onLocalNetwork()) error("Connect to the same Wi-Fi as the computer first")

        val socket = dial(addrs + discover(deskFp)) ?: error("Couldn't reach the computer. Are both on the same Wi-Fi? Guest networks often block this.")
        return socket.use { s ->
            s.soTimeout = 15_000
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())
            val proof = LinkCrypto.hmac(token, "dsl-pair-1|$myPub|$deskPub")
            writeFrame(out, JSONObject().put("t", "pair").put("v", 1).put("pub", myPub).put("name", myName).put("proof", proof))
            val reply = readFrame(inp)
            when (reply.optString("t")) {
                "paired" -> {
                    require(reply.optString("pub") == deskPub) { "The computer answered with a different key" }
                    val want = LinkCrypto.hmac(token, "dsl-paired-1|$deskPub|$myPub")
                    require(LinkCrypto.sameText(want, reply.optString("proof"))) { "The computer couldn't prove it showed this code" }
                }
                "pair-fail" -> error(reply.optString("why", "The computer refused"))
                else -> error("Unexpected answer from the computer")
            }
            val name = reply.optString("name", deskName).take(60)
            val h = s.inetAddress.hostAddress ?: ""
            val known = (addrs + "${if (h.contains(':')) "[$h]" else h}:${s.port}").distinct()
            synchronized(this) {
                stations[deskFp] = Station(deskFp, deskPub, name, known)
                keys[deskFp] = LinkCrypto.sharedKey(LinkCrypto.privateFromPkcs8(myPrivPkcs8), myPub, deskPub)
                save()
            }
            changed()
            io.execute { stations().firstOrNull { it.fp == deskFp }?.let { connect(it) } }
            name
        }
    }

    // ================================================================ connection

    private fun onLocalNetwork(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun dial(addrs: List<String>): Socket? {
        for (a in addrs.distinct()) {
            val i = a.lastIndexOf(':')
            if (i <= 0) continue
            val host = a.substring(0, i).trim('[', ']')
            val port = a.substring(i + 1).toIntOrNull() ?: continue
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), 3000)
                return s
            } catch (e: Exception) {
                // try the next address
            }
        }
        return null
    }

    /** Finds the computer with fingerprint [fp] on the network (a few seconds at most). */
    @Suppress("DEPRECATION")
    private fun discover(fp: String): List<String> {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return emptyList()
        val wifi = app.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("deepstellar-link")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (!i.serviceName.startsWith("Station-" + fp.take(8))) return
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val txt = s.attributes["fp"]?.toString(Charsets.UTF_8)
                        val host = s.host?.hostAddress
                        if (host != null && (txt == null || txt == fp)) {
                            found += "${if (host.contains(':')) "[$host]" else host}:${s.port}"
                            latch.countDown()
                        }
                    }
                })
            }
        }
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed: ${e.javaClass.simpleName}")
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
        return found.toList()
    }

    private fun connect(s: Station) {
        if (socks.containsKey(s.fp)) return
        val socket = dial(s.addrs) ?: discover(s.fp).let { fresh ->
            dial(fresh)?.also { if (fresh.isNotEmpty()) synchronized(this) { s.addrs = (fresh + s.addrs).distinct().take(4); save() } }
        } ?: return
        val conn = Conn(socket, DataOutputStream(socket.getOutputStream()))
        if (socks.putIfAbsent(s.fp, conn) != null) { runCatching { socket.close() }; return }
        try {
            conn.send(JSONObject().put("t", "hello").put("fp", myFp).put("name", myName))
            val inp = DataInputStream(socket.getInputStream())
            while (true) {
                val f = readFrame(inp)
                when (f.optString("t")) {
                    "hello" -> {
                        s.online = true
                        s.unknownToComputer = false
                        f.optString("name").takeIf { it.isNotBlank() && it != s.name }?.let { n -> synchronized(this) { s.name = n.take(60); save() } }
                        flush(s, conn)
                        changed()
                    }
                    "unknown" -> {
                        s.unknownToComputer = true
                        changed()
                        break
                    }
                    "msg" -> onMessage(s, conn, f)
                }
            }
        } catch (e: Exception) {
            // connection ended
        } finally {
            socks.remove(s.fp, conn)
            runCatching { socket.close() }
            s.online = false
            changed()
        }
    }

    private fun flush(s: Station, conn: Conn) {
        val key = keys[s.fp] ?: return
        val pending = synchronized(this) { queue[s.fp]?.toList() ?: emptyList() }
        for (body in pending) conn.send(LinkCrypto.seal(key, myFp, body))
    }

    private fun onMessage(s: Station, conn: Conn, f: JSONObject) {
        if (f.optString("fp") != s.fp) return
        val key = keys[s.fp] ?: return
        val body = LinkCrypto.open(key, f) ?: return // not from this computer, or altered
        val id = body.optString("id").takeIf { it.isNotEmpty() } ?: return
        if (body.optString("kind") == "ack") {
            synchronized(this) { queue[s.fp]?.removeAll { it.optString("id") == body.optString("ack") }; save() }
            changed()
            return
        }
        conn.send(LinkCrypto.seal(key, myFp, newBody(JSONObject().put("kind", "ack").put("ack", id))))
        val fresh = synchronized(this) {
            if (id in seen) false else {
                seen.addLast(id)
                while (seen.size > 500) seen.removeFirst()
                val item = Item(id, body.optLong("ts", System.currentTimeMillis()), body.optString("kind"),
                    body.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "",
                    body.optString("title").take(300), body.optString("text").take(4000), s.name, false)
                items.add(0, item)
                save()
                notify(item)
                true
            }
        }
        if (fresh) changed()
    }

    private fun newBody(partial: JSONObject): JSONObject {
        partial.put("id", LinkCrypto.b64(LinkCrypto.random(12)))
        partial.put("ts", System.currentTimeMillis())
        partial.put("from", myName)
        return partial
    }

    private fun enqueue(fp: String, partial: JSONObject): Boolean {
        val body = newBody(partial)
        val s = synchronized(this) {
            val st = stations[fp] ?: return false
            queue.getOrPut(fp) { mutableListOf() }.let { q -> q += body; while (q.size > 200) q.removeAt(0) }
            items.add(0, Item(body.getString("id"), body.getLong("ts"), body.optString("kind"), body.optString("url"),
                body.optString("title"), body.optString("text"), st.name, true))
            save()
            st
        }
        changed()
        io.execute {
            val conn = socks[fp]
            if (conn != null) runCatching { conn.send(LinkCrypto.seal(keys[fp] ?: return@execute, myFp, body)) }
            else if (onLocalNetwork()) connect(s)
        }
        return true
    }

    // ================================================================ frames

    private fun writeFrame(out: DataOutputStream, o: JSONObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(b.size); out.write(b); out.flush()
    }

    private fun readFrame(inp: DataInputStream): JSONObject {
        val n = inp.readInt()
        require(n in 1..(4 * 1024 * 1024)) { "bad frame" }
        val b = ByteArray(n)
        inp.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    // ================================================================ notification

    private fun notify(item: Item) {
        val tap = if (item.url.isNotEmpty())
            Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(item.url))
        else Intent(app, dev.deepstellar.browser.ui.LinkActivity::class.java)
        val pi = PendingIntent.getActivity(app, item.id.hashCode(), tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(if (item.kind == "link") "Link from ${item.peerName}" else "Note from ${item.peerName}")
            .setContentText(item.title.ifBlank { item.url.ifBlank { item.text } })
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) }
    }

    /** Panic wipe: forget what was shared (the link with the computer stays). */
    fun panicWipe(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        disconnectAll()
        runCatching { load() }
        synchronized(this) { items.clear(); queue.clear(); seen.clear(); save() }
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
    }
}
