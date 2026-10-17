package com.bizzle.minicpm.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.random.Random

class DownloaderTest {
    private fun serve(data: ByteArray, dropAfter: Int? = null): Pair<HttpServer, String> {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var first = true
        srv.createContext("/redirect") { ex -> ex.responseHeaders.add("Location", "/file"); ex.sendResponseHeaders(302, -1); ex.close() }
        srv.createContext("/file") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val slice = data.copyOfRange(start, data.size)
            ex.sendResponseHeaders(if (start > 0) 206 else 200, slice.size.toLong())
            ex.responseBody.use { os ->
                if (dropAfter != null && first) { first = false; os.write(slice, 0, dropAfter); os.flush(); throw RuntimeException("drop") }
                os.write(slice)
            }
        }
        srv.start()
        return srv to "http://127.0.0.1:${srv.address.port}"
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun downloadsFollowsRedirectAndVerifies() {
        val data = Random(1).nextBytes(700_000)
        val (srv, base) = serve(data)
        try {
            val dest = File.createTempFile("bizdl", ".bin").also { it.delete() }
            var last = 0L
            Downloader().download("$base/redirect", dest, data.size.toLong(), sha(data)) { d, _, _ -> last = d }
            assertArrayEquals(data, dest.readBytes()); assertEquals(data.size.toLong(), last)
            dest.delete()
        } finally { srv.stop(0) }
    }

    @Test fun resumesAfterDrop() {
        val data = Random(2).nextBytes(900_000)
        val (srv, base) = serve(data, dropAfter = 300_000)
        try {
            val dest = File.createTempFile("bizdl", ".bin").also { it.delete() }
            Downloader().download("$base/file", dest, data.size.toLong(), sha(data)) { _, _, _ -> }
            assertArrayEquals(data, dest.readBytes())
            dest.delete()
        } finally { srv.stop(0) }
    }

    @Test fun resumesFromExistingPartFile() {
        val data = Random(3).nextBytes(500_000)
        val (srv, base) = serve(data)
        try {
            val dest = File.createTempFile("bizdl", ".bin").also { it.delete() }
            val dl = Downloader()
            dl.partFile(dest).writeBytes(data.copyOfRange(0, 123_456))
            dl.download("$base/file", dest, data.size.toLong(), sha(data)) { _, _, _ -> }
            assertArrayEquals(data, dest.readBytes())
            dest.delete()
        } finally { srv.stop(0) }
    }

    @Test fun badChecksumDeletesFile() {
        val data = Random(4).nextBytes(100_000)
        val (srv, base) = serve(data)
        try {
            val dest = File.createTempFile("bizdl", ".bin").also { it.delete() }
            val dl = Downloader()
            try { dl.download("$base/file", dest, data.size.toLong(), "0".repeat(64)) { _, _, _ -> }; fail() }
            catch (e: Downloader.DownloadException) { assertTrue(e.message!!.contains("Checksum")) }
            assertFalse(dest.exists()); assertFalse(dl.partFile(dest).exists())
        } finally { srv.stop(0) }
    }
}
