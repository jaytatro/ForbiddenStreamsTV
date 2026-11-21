package com.forbiddenvid.tv.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * "Add from your phone": while the Add-ons screen is open, the TV answers a tiny web page on the
 * home network. The phone scans the QR code, pastes the add-on address and taps Send; the TV asks
 * to confirm before installing. Nothing leaves the home network.
 */
class PhoneLink(private val onAddress: (String) -> Unit) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    var address: String? = null
        private set

    fun start(): String? {
        if (running) return address
        val ip = localIp() ?: return null
        val s = try { ServerSocket(8765) } catch (e: Exception) { try { ServerSocket(0) } catch (e2: Exception) { return null } }
        server = s
        running = true
        address = "http://$ip:${s.localPort}/"
        Thread({
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                Thread({ handle(c) }, "phone-link-client").start()
            }
        }, "phone-link").apply { isDaemon = true }.start()
        return address
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 10_000
            val input = BufferedInputStream(c.getInputStream())
            val head = readHead(input)
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val method = requestLine.substringBefore(' ')
            val length = head.lineSequence().firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceAtMost(16_384) ?: 0
            var sent = false
            if (method == "POST" && length > 0) {
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val form = String(body, 0, read, Charsets.UTF_8)
                val url = form.split('&').firstOrNull { it.startsWith("url=") }?.substringAfter("url=")
                    ?.let { URLDecoder.decode(it, "UTF-8").trim() }
                if (!url.isNullOrEmpty()) { onAddress(url); sent = true }
            }
            val html = page(sent)
            val bytes = html.toByteArray(Charsets.UTF_8)
            val out = c.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
        } finally {
            try { c.close() } catch (e: Exception) { }
        }
    }

    private fun readHead(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return buf.toString("UTF-8")
    }

    private fun page(sent: Boolean): String = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Forbidden Vid</title>
<style>
body{margin:0;background:#040604;color:#E6F5DF;font-family:system-ui,sans-serif;padding:28px}
h1{color:#7CFF3A;margin:0 0 6px;font-size:26px}p{color:#8FA88A}
input{width:100%;box-sizing:border-box;padding:14px;border-radius:10px;border:2px solid #1E6B12;background:#0C140D;color:#fff;font-size:16px}
button{margin-top:14px;width:100%;padding:14px;border:0;border-radius:999px;background:#7CFF3A;color:#000;font-weight:700;font-size:17px}
.ok{background:#15361A;border:1px solid #3FBF1F;border-radius:10px;padding:12px;margin-bottom:16px;color:#7CFF3A}
</style></head><body>
<h1>Forbidden Vid</h1><p>Paste an add-on address (it usually ends in manifest.json). Your TV will ask you to confirm.</p>
${if (sent) "<div class=\"ok\">Sent! Check your TV.</div>" else ""}
<form method="POST" action="/"><input name="url" type="url" placeholder="https://…/manifest.json" autofocus required>
<button type="submit">Send to TV</button></form></body></html>
""".trimIndent()

    companion object {
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }

        fun qr(text: String, size: Int = 360): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
