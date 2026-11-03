package dev.deepstellar.browser.adblock

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

/**
 * A tiny self-learning ad classifier: online logistic regression over
 * hashed URL features ("feature hashing").
 *
 * How it learns:
 *  - Every request that hits a filter list is a labelled "ad" example.
 *  - A small sample of first-party requests are labelled "not ad".
 *  - Your own feedback ("Block as ad", "Allow this site") is weighted heavily.
 *
 * What it then does: scores third-party requests the lists have never seen
 * and blocks the ones that look strongly like ads/trackers.
 *
 * Privacy: features are hashed into a fixed array of numbers before they touch
 * the weights. No URL, domain, or word is ever stored — the saved file is just
 * 262,144 floats and cannot be turned back into a browsing record.
 */
class AdLearner(private val file: File) {

    private val dim = 1 shl 18
    private val mask = dim - 1
    private val w = FloatArray(dim)
    private val updates = AtomicLong(0)
    @Volatile private var dirty = false

    /** Model only acts once it has seen enough labelled examples. */
    val isWarm: Boolean get() = updates.get() >= WARMUP

    val trainedExamples: Long get() = updates.get()

    init { load() }

    fun score(f: IntArray): Float {
        var z = 0f
        for (i in f) z += w[i]
        return sigmoid(z)
    }

    fun train(f: IntArray, label: Float, lr: Float) {
        val err = label - score(f)
        val step = lr * err
        for (i in f) {
            // Light L2 shrink keeps rare features from dominating.
            w[i] = (w[i] + step) * 0.99999f
        }
        updates.incrementAndGet()
        dirty = true
    }

    fun features(url: String, host: String, pageHost: String?, thirdParty: Boolean): IntArray {
        val out = ArrayList<Int>(48)
        fun add(s: String) { out += mix(s.hashCode()) and mask }

        add("bias")
        add(if (thirdParty) "3p" else "1p")

        val labels = host.split('.')
        for (l in labels) if (l.length in 2..30) add("hl:$l")
        add("hd:" + DomainUtil.registrable(host))
        // sub-label hints: "ads.", "track.", "pixel." etc.
        if (labels.size > 2) add("hsub:" + labels[0])

        val pathStart = url.indexOf('/', url.indexOf("//").let { if (it < 0) 0 else it + 2 })
        if (pathStart >= 0) {
            val qIdx = url.indexOf('?', pathStart)
            val path = if (qIdx >= 0) url.substring(pathStart, qIdx) else url.substring(pathStart)
            val query = if (qIdx >= 0) url.substring(qIdx + 1) else ""

            var depth = 0
            var digits = 0
            for (c in path) {
                if (c == '/') depth++
                if (c.isDigit()) digits++
            }
            add("depth:" + depth.coerceAtMost(8))
            if (path.isNotEmpty() && digits * 3 > path.length) add("numdense")

            path.lowercase().split(TOKEN_SPLIT).forEach { t ->
                if (t.length in 2..24) {
                    add("p:$t")
                    if (thirdParty) add("3p:$t")
                }
            }
            val dot = path.lastIndexOf('.')
            if (dot > path.lastIndexOf('/')) add("x:" + path.substring(dot + 1).lowercase().take(6))

            if (query.isNotEmpty()) {
                var n = 0
                query.split('&').forEach { kv ->
                    val k = kv.substringBefore('=').lowercase()
                    if (k.length in 1..24) add("q:$k")
                    n++
                }
                add("qn:" + n.coerceAtMost(12))
                if (query.length > 300) add("qlong")
            }
        }
        return out.toIntArray()
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(updates.get())
                for (v in w) out.writeFloat(v)
            }
            tmp.renameTo(file)
            dirty = false
        }
    }

    @Synchronized
    fun reset() {
        java.util.Arrays.fill(w, 0f)
        updates.set(0)
        dirty = true
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC) return
                updates.set(inp.readLong())
                for (i in 0 until dim) w[i] = inp.readFloat()
            }
        }
    }

    private fun sigmoid(z: Float): Float = (1.0 / (1.0 + exp(-z.toDouble().coerceIn(-30.0, 30.0)))).toFloat()

    private fun mix(h0: Int): Int {
        var h = h0
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return h
    }

    companion object {
        private const val MAGIC = 0x44534C31 // "DSL1"
        private const val WARMUP = 1500L
        private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
    }
}
