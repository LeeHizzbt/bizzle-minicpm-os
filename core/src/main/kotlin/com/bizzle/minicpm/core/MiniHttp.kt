package com.bizzle.minicpm.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Tiny HTTP/1.1 request parser + response writer for the localhost-only API (no third-party server needed). */
object MiniHttp {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
        val bodyText: String get() = body.toString(Charsets.UTF_8)
    }

    class HttpError(val status: Int, msg: String) : Exception(msg)

    const val MAX_BODY = 40 * 1024 * 1024 // images arrive base64-encoded

    fun read(input: InputStream): Request? {
        val ins = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val requestLine = readLine(ins) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw HttpError(400, "Malformed request line")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(ins) ?: throw HttpError(400, "Unexpected end of headers")
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
            if (headers.size > 100) throw HttpError(431, "Too many headers")
        }
        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        if (len > MAX_BODY) throw HttpError(413, "Body too large")
        val body = if (headers["transfer-encoding"]?.contains("chunked", true) == true) readChunked(ins) else readN(ins, len.toInt())
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
    }

    private fun readLine(ins: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16 * 1024) throw HttpError(431, "Header line too long")
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val out = ByteArray(n); var off = 0
        while (off < n) { val r = ins.read(out, off, n - off); if (r < 0) throw HttpError(400, "Body shorter than Content-Length"); off += r }
        return out
    }

    private fun readChunked(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(ins) ?: throw HttpError(400, "Bad chunk")
            val size = sizeLine.substringBefore(';').trim().toInt(16)
            if (size == 0) { readLine(ins); break }
            if (out.size() + size > MAX_BODY) throw HttpError(413, "Body too large")
            out.write(readN(ins, size)); readLine(ins)
        }
        return out.toByteArray()
    }

    fun reason(status: Int) = when (status) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Status"
    }

    private const val CORS = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n"

    fun writeJson(out: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n${CORS}Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(bytes); out.flush()
    }

    fun writeSseHeaders(out: OutputStream) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\n" +
            "${CORS}Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun writeSse(out: OutputStream, data: String) { out.write("data: $data\n\n".toByteArray(Charsets.UTF_8)); out.flush() }

    fun writeEmpty(out: OutputStream, status: Int) {
        out.write("HTTP/1.1 $status ${reason(status)}\r\n${CORS}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); out.flush()
    }

    /** Constant-time bearer token check. Empty [expected] means auth is off. */
    fun authorized(req: Request, expected: String): Boolean {
        if (expected.isEmpty()) return true
        val got = req.header("authorization")?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(got.toByteArray(), expected.toByteArray())
    }
}
