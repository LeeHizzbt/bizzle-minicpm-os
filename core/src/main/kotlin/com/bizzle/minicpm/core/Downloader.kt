package com.bizzle.minicpm.core

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Resumable HTTP downloader. Writes to "<dest>.part", resumes with a Range header after a drop or app restart,
 * verifies size + SHA-256, then renames to the final name. Follows Hugging Face's CDN redirects.
 */
class Downloader(private val userAgent: String = "BizzleMiniCPM-OS/4.20") {
    class DownloadException(msg: String) : Exception(msg)

    fun interface Progress { fun onProgress(done: Long, total: Long, bytesPerSec: Long) }

    @Volatile var cancelled = false

    fun partFile(dest: File) = File(dest.parentFile, dest.name + ".part")

    fun download(url: String, dest: File, expectedSize: Long, sha256: String?, progress: Progress): File {
        if (dest.exists() && dest.length() == expectedSize) return dest
        dest.parentFile?.mkdirs()
        val part = partFile(dest)
        var attempts = 0
        while (true) {
            if (cancelled) throw DownloadException("Cancelled")
            try {
                fetch(url, part, expectedSize, progress)
                break
            } catch (e: DownloadException) {
                throw e
            } catch (e: Exception) {
                attempts++
                if (attempts >= 6 || cancelled) throw DownloadException("Network error: ${e.message ?: e.javaClass.simpleName} (progress is saved, tap Download to resume)")
                Thread.sleep(1500L * attempts)
            }
        }
        if (expectedSize > 0 && part.length() != expectedSize) throw DownloadException("Size mismatch: got ${part.length()} of $expectedSize bytes")
        if (!sha256.isNullOrBlank()) {
            val got = sha256Of(part)
            if (!got.equals(sha256, ignoreCase = true)) { part.delete(); throw DownloadException("Checksum failed, file deleted. Please download again.") }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw DownloadException("Could not finalize file")
        return dest
    }

    private fun fetch(url: String, part: File, expectedSize: Long, progress: Progress) {
        var start = if (part.exists()) part.length() else 0L
        if (expectedSize > 0 && start > expectedSize) { part.delete(); start = 0 }
        if (expectedSize > 0 && start == expectedSize) return
        var conn = open(url, start)
        var redirects = 0
        while (conn.responseCode in 300..399) {
            val loc = conn.getHeaderField("Location") ?: throw DownloadException("Redirect without location")
            conn.disconnect()
            if (++redirects > 8) throw DownloadException("Too many redirects")
            conn = open(URL(URL(url), loc).toString(), start)
        }
        val code = conn.responseCode
        if (code == 416) { conn.disconnect(); return } // already complete
        if (code == 401 || code == 403) throw DownloadException("The server refused the download (HTTP $code). The model may have been made private.")
        if (code == 404) throw DownloadException("File not found on Hugging Face (HTTP 404).")
        if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
        if (code == 200 && start > 0) start = 0 // server ignored Range: start over
        val total = if (expectedSize > 0) expectedSize else start + conn.contentLengthLong
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(start); raf.seek(start)
            conn.inputStream.use { ins ->
                val buf = ByteArray(256 * 1024)
                var done = start
                var windowStart = System.nanoTime(); var windowBytes = 0L; var speed = 0L; var lastReport = 0L
                while (true) {
                    if (cancelled) throw DownloadException("Paused")
                    val n = ins.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n); done += n; windowBytes += n
                    val now = System.nanoTime()
                    if (now - windowStart > 1_000_000_000L) { speed = windowBytes * 1_000_000_000L / (now - windowStart); windowStart = now; windowBytes = 0 }
                    if (now - lastReport > 250_000_000L) { progress.onProgress(done, total, speed); lastReport = now }
                }
                progress.onProgress(done, total, speed)
            }
        }
        conn.disconnect()
        // connection ended early without an error: retry from where we are (counts as a network error)
        if (expectedSize > 0 && part.length() < expectedSize) throw java.io.IOException("connection closed at ${part.length()} of $expectedSize bytes")
    }

    private fun open(url: String, start: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", userAgent)
        if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
        return c
    }

    companion object {
        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { ins -> val buf = ByteArray(1 shl 20); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
