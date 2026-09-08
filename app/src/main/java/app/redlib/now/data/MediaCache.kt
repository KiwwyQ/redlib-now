package app.redlib.now.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.redlib.now.model.Post
import java.nio.ByteBuffer
import app.redlib.now.net.Http
import app.redlib.now.net.Logd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * App-private media cache. Everything the user sees gets a local copy that
 * OUR stack serves to the player/Coil (file:// URIs) — never exposed to
 * other apps or to the user's gallery/SD card.
 *
 * Videos are downloaded fully and remuxed (MediaExtractor -> MediaMuxer)
 * into a vanilla progressive MP4, sidestepping ExoPlayer's trouble with
 * reddit's DASH-branded fMP4 streams (spurious STATE_ENDED).
 *
 * Retention: entries older than [RETENTION_MS] (72h) are purged on startup.
 */
object MediaCache {
    const val RETENTION_MS = 72L * 3600 * 1000
    private const val MAX_VIDEO_BYTES = 300L * 1024 * 1024

    private lateinit var dir: File

    fun init(context: android.content.Context) {
        dir = File(context.filesDir, "media").apply { mkdirs() }
        purgeOld()
    }

    private fun fileFor(url: String, ext: String): File =
        File(dir, md5(url) + ext)

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun extOf(url: String): String =
        Regex("""\.(jpe?g|png|gif|webp|mp4)(\?|$)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase()?.let { ".$it" } ?: ".bin"

    /** file:// URI if we already have this media locally, else null. */
    fun localUri(url: String?): String? {
        if (url == null || !this::dir.isInitialized) return null
        return fileFor(url, extOf(url)).takeIf { it.length() > 0 }
            ?.let { "file://${it.absolutePath}" }
    }

    /** Download (or reuse) a local copy. Progress callback 0..100, may be null. */
    suspend fun getOrDownload(url: String, onProgress: ((Int?) -> Unit)? = null): File? =
        withContext(Dispatchers.IO) {
            try {
                val f = fileFor(url, extOf(url))
                if (f.length() > 0L) {
                    onProgress?.invoke(100)
                    return@withContext f
                }
                val tmp = File(dir, md5(url) + ".part")
                val req = okhttp3.Request.Builder().url(url).build()
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Logd.w("media download failed $url -> ${resp.code}")
                        return@withContext null
                    }
                    val body = resp.body ?: return@withContext null
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            var lastPct = -1
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                done += read
                                if (total > 0 && onProgress != null) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { onProgress(pct); lastPct = pct }
                                }
                            }
                        }
                    }
                }
                // Sanity: some CDNs answer bot-walls with 200 + HTML.
                if (tmp.length() > 16 && tmp.inputStream().use { it.readNBytes(256).toString(Charsets.ISO_8859_1) }
                        .lowercase().let { it.contains("<html") || it.contains("<!doctype") }) {
                    Logd.w("media download returned HTML (bot wall?), discarding: $url")
                    tmp.delete()
                    return@withContext null
                }
                val ok = tmp.renameTo(f)
                return@withContext if (ok && f.length() > 0L) f else null
            } catch (t: Throwable) {
                Logd.e("media download error $url", t)
                null
            }
        }

    /**
     * Full video pipeline: download -> remux to clean MP4 -> local file.
     * Falls back to the raw download if remuxing fails; returns null only
     * if we could not get the video at all.
     *
     * Redlib video sources are typically:
     *  - /hls/<id>/HLSPlaylist.m3u8  (HLS master; segments point at progressive MP4s)
     *  - /vid/<id>/<size>            (proxies to DASH_<size> or CMAF_<size>)
     *  - direct .mp4 (including redgifs proxies when the instance embeds them)
     * We try several progressive candidates (CMAF then DASH, 480 then 720)
     * because older/newer instances and rich:video posts differ.
     */
    suspend fun videoReadyCopy(url0: String, onProgress: (Int?) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val candidates = progressiveVideoCandidates(url0)
            for (url in candidates) {
                val existing = fileFor(url, ".r.mp4")
                if (existing.length() > 0L) {
                    onProgress(100)
                    return@withContext existing
                }
            }
            var lastRaw: File? = null
            for (url in candidates) {
                Logd.i("video try: $url")
                val raw = fileFor(url, extOf(url))
                if (raw.length() == 0L) {
                    val head = headContentLength(url)
                    if (head != null && head > MAX_VIDEO_BYTES) {
                        Logd.w("video too large ($head bytes), skip: $url")
                        continue
                    }
                    if (getOrDownload(url, onProgress) == null) continue
                }
                if (raw.length() == 0L) continue
                // Distinct name from raw — muxer and extractor cannot share a path.
                val remuxed = File(dir, md5(url) + ".r.mp4")
                if (remux(raw, remuxed)) {
                    raw.delete()
                    onProgress(100)
                    return@withContext remuxed
                }
                Logd.w("remux failed for $url; keep raw as fallback")
                lastRaw = raw
            }
            lastRaw?.also { onProgress(100) }
        }

    /**
     * Build ordered list of progressive MP4 URLs to try for a given video URL
     * from the Redlib HTML (HLS playlist, /vid/ proxy, or already-mp4).
     */
    private fun progressiveVideoCandidates(url0: String): List<String> {
        val out = linkedSetOf<String>()
        when {
            // HLS master: /hls/<id>/HLSPlaylist.m3u8  →  try CMAF_ then DASH_ qualities
            url0.contains("HLSPlaylist.m3u8", ignoreCase = true) -> {
                val base = url0.substringBefore("HLSPlaylist.m3u8", url0)
                for (q in listOf("480", "720", "360", "1080")) {
                    out += base + "CMAF_$q.mp4"
                    out += base + "DASH_$q.mp4"
                }
            }
            // /vid/<id>/<size> or /vid/<id>/DASH_xx  → expand qualities
            Regex("""/vid/[^/]+/""", RegexOption.IGNORE_CASE).containsMatchIn(url0) -> {
                val base = url0.replace(Regex("""/(?:DASH_|CMAF_)?\d+(?:\.mp4)?$""", RegexOption.IGNORE_CASE), "/")
                for (q in listOf("480", "720", "360", "1080")) {
                    out += base + "CMAF_$q.mp4"
                    out += base + "DASH_$q.mp4"
                    out += base + q  // some proxies use bare size
                }
                out += url0
            }
            // Already a progressive file (mp4, or redgifs-style proxy)
            else -> out += url0
        }
        // Always keep original as last resort (e.g. non-HLS mp4 source tag)
        out += url0
        return out.toList()
    }

    private fun headContentLength(url: String): Long? = try {
        val req = okhttp3.Request.Builder().url(url).head().build()
        Http.client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() else null
        }
    } catch (_: Exception) { null }

    /** Copy all A/V samples into a fresh, boring, maximally-compatible MP4. */
    private fun remux(src: File, dst: File): Boolean {
        try {
            val ex = MediaExtractor()
            ex.setDataSource(src.absolutePath)
            val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val indexMap = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val fmt: MediaFormat = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    indexMap[i] = mux.addTrack(fmt)
                }
            }
            if (indexMap.isEmpty()) {
                mux.release(); ex.release()
                Logd.w("remux: no video/audio tracks in source")
                return false
            }
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            mux.start()
            var samplesWritten = 0
            while (true) {
                val trackIdx = ex.sampleTrackIndex
                if (trackIdx < 0) break
                buf.clear()
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                buf.flip()
                indexMap[trackIdx]?.let { mux.writeSampleData(it, buf, info) }
                samplesWritten++
                if (!ex.advance()) break
            }
            mux.stop(); mux.release(); ex.release()
            if (samplesWritten == 0 || dst.length() < 1024) {
                Logd.w("remux: produced empty/too-small output (samples=$samplesWritten, size=${dst.length()})")
                dst.delete()
                return false
            }
            Logd.i("remux ok: ${dst.name} (${dst.length()} bytes, $samplesWritten samples)")
            return true
        } catch (t: Throwable) {
            Logd.e("remux failed", t)
            dst.delete()
            return false
        }
    }

    /** Human-readable age for the drawer status line. */
    fun ageString(savedAt: Long): String {
        val mins = (System.currentTimeMillis() - savedAt) / 60000
        return when {
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    /** Warm the cache with card images for a feed's posts (videos on view). */
    suspend fun prefetch(posts: List<Post>) = withContext(Dispatchers.IO) {
        // Skip entirely in data-saver mode; keep the window small so lower-end
        // devices aren't hammered with parallel downloads while scrolling.
        if (Settings.dataSaver) return@withContext
        posts.take(12).forEach { post ->
            val url = post.imageUrl ?: return@forEach
            if (post.isVideo) return@forEach // full videos on demand only
            if (localUri(url) == null) getOrDownload(url)
        }
    }

    private fun purgeOld() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}

