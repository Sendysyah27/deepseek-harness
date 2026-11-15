package com.senzwh.deepharness

import android.content.Context
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/** Gmail ringan TANPA library: IMAP (baca) + SMTP (kirim) memakai App Password Google
 *  yang disimpan terenkripsi (SecureStore / Android Keystore).
 *  Butuh: akun Google dengan 2FA aktif + App Password dibuat di myaccount.google.com/apppasswords. */
object GmailLite {

    private fun creds(ctx: Context): Pair<String, String>? {
        val email = SecureStore.get(ctx, "gmail_email") ?: return null
        val pass = SecureStore.get(ctx, "gmail_app_pass") ?: return null
        if (email.isBlank() || pass.isBlank()) return null
        return email to pass.replace(" ", "")
    }

    private const val NO_CRED = "Gmail belum diatur. Minta user membuka Setup tombol 25: isi alamat Gmail + App Password Google (dibuat di myaccount.google.com/apppasswords, butuh 2FA Google aktif). Tanpa itu tool Gmail tidak bisa jalan — jangan mengarang isi email."

    // ---------------- IMAP ----------------

    private class Imap(email: String, pass: String) {
        private val socket: Socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket("imap.gmail.com", 993)
        private val reader: BufferedReader
        private val writer: PrintWriter
        private var tag = 0

        init {
            socket.soTimeout = 25_000
            reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            writer = PrintWriter(socket.outputStream, true)
            readUntil { it.startsWith("* OK") }
            cmd("LOGIN \"$email\" \"$pass\"")
        }

        private fun readUntil(pred: (String) -> Boolean): List<String> {
            val lines = ArrayList<String>()
            while (true) {
                val line = reader.readLine() ?: throw Exception("Koneksi IMAP putus.")
                lines.add(line)
                if (pred(line)) break
            }
            return lines
        }

        fun cmd(command: String): List<String> {
            tag++
            val t = "A$tag"
            writer.print("$t $command\r\n")
            writer.flush()
            val lines = readUntil { it.startsWith("$t ") }
            val last = lines.last()
            if (!last.startsWith("$t OK")) throw Exception("IMAP menolak: ${last.take(160)}")
            return lines
        }

        fun close() {
            try { cmd("LOGOUT") } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun decodeWord(s: String): String {
        var out = s
        val rx = Regex("=\\?[^?]+\\?([bBqQ])\\?([^?]*)\\?=")
        while (true) {
            val m = rx.find(out) ?: break
            val enc = m.groupValues[1].uppercase()
            val data = m.groupValues[2]
            val decoded = try {
                if (enc == "B") String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8)
                else {
                    val bytes = ArrayList<Byte>()
                    var i = 0
                    while (i < data.length) {
                        when {
                            data[i] == '_' -> { bytes.add(32); i++ }
                            data[i] == '=' && i + 2 < data.length -> {
                                bytes.add(data.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
                            }
                            else -> { bytes.add(data[i].code.toByte()); i++ }
                        }
                    }
                    String(bytes.toByteArray(), Charsets.UTF_8)
                }
            } catch (e: Exception) { data }
            out = out.substring(0, m.range.first) + decoded + out.substring(m.range.last + 1)
        }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    fun unread(ctx: Context, limit: Int): String {
        val (email, pass) = creds(ctx) ?: return NO_CRED
        return try {
            val imap = Imap(email, pass)
            try {
                imap.cmd("SELECT INBOX")
                val search = imap.cmd("SEARCH UNSEEN")
                val ids = search.joinToString(" ").split(" ")
                    .mapNotNull { it.toIntOrNull() }
                if (ids.isEmpty()) return "Tidak ada email belum terbaca di INBOX $email."
                fetchHeaders(imap, ids.takeLast(limit.coerceIn(1, 20)))
            } finally { imap.close() }
        } catch (e: Exception) { "Gmail (IMAP) gagal: ${e.message}" }
    }

    fun search(ctx: Context, query: String, limit: Int): String {
        val (email, pass) = creds(ctx) ?: return NO_CRED
        return try {
            val imap = Imap(email, pass)
            try {
                imap.cmd("SELECT INBOX")
                val q = query.replace("\"", "")
                val search = imap.cmd("SEARCH TEXT \"$q\"")
                val ids = search.joinToString(" ").split(" ")
                    .mapNotNull { it.toIntOrNull() }
                if (ids.isEmpty()) return "Tidak ada email cocok dengan '$query' di $email."
                "Ditemukan ${ids.size} email untuk '$query'. Terbaru:\n" + fetchHeaders(imap, ids.takeLast(limit.coerceIn(1, 20)))
            } finally { imap.close() }
        } catch (e: Exception) { "Gmail (IMAP) gagal: ${e.message}" }
    }

    private fun fetchHeaders(imap: Imap, ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids.asReversed()) {
            val lines = imap.cmd("FETCH $id (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])")
            var from = ""; var subj = ""; var date = ""
            for (l in lines) {
                val low = l.lowercase()
                when {
                    low.startsWith("from:") -> from = decodeWord(l.substring(5))
                    low.startsWith("subject:") -> subj = decodeWord(l.substring(8))
                    low.startsWith("date:") -> date = l.substring(5).trim().take(31)
                }
            }
            sb.append("- [$date] $from — ${subj.ifBlank { "(tanpa subjek)" }}\n")
        }
        return sb.toString().trim()
    }

    // ---------------- SMTP ----------------

    fun send(ctx: Context, to: String, subject: String, body: String): String {
        val (email, pass) = creds(ctx) ?: return NO_CRED
        if (!to.contains("@")) return "Alamat tujuan tidak valid: $to"
        var socket: Socket? = null
        return try {
            socket = Socket("smtp.gmail.com", 587)
            socket.soTimeout = 25_000
            var reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            var writer = PrintWriter(socket.outputStream, true)
            fun expect(prefix: String) {
                val line = reader.readLine() ?: throw Exception("SMTP putus.")
                if (!line.startsWith(prefix)) throw Exception("SMTP menolak: ${line.take(160)}")
            }
            fun sendLine(s: String) { writer.print("$s\r\n"); writer.flush() }
            expect("220")
            sendLine("EHLO deepharness"); readSmtpLines(reader)
            sendLine("STARTTLS"); expect("220")
            val wrapped = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, "smtp.gmail.com", 587, true) as javax.net.ssl.SSLSocket
            wrapped.startHandshake()
            socket = wrapped
            reader = BufferedReader(InputStreamReader(wrapped.inputStream, Charsets.UTF_8))
            writer = PrintWriter(wrapped.outputStream, true)
            sendLine("EHLO deepharness"); readSmtpLines(reader)
            sendLine("AUTH LOGIN"); expect("334")
            sendLine(Base64.encodeToString(email.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)); expect("334")
            sendLine(Base64.encodeToString(pass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)); expect("235")
            sendLine("MAIL FROM:<$email>"); expect("250")
            sendLine("RCPT TO:<$to>"); expect("250")
            sendLine("DATA"); expect("354")
            val msg = StringBuilder()
            msg.append("From: $email\r\nTo: $to\r\nSubject: $subject\r\n")
            msg.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
            msg.append(body.replace("\r\n", "\n").replace("\n", "\r\n"))
            msg.append("\r\n.\r\n")
            writer.print(msg.toString()); writer.flush()
            expect("250")
            sendLine("QUIT")
            wrapped.close()
            "Email terkirim dari $email ke $to: $subject"
        } catch (e: Exception) {
            try { socket?.close() } catch (e2: Exception) {}
            "Gmail (SMTP) gagal: ${e.message}"
        }
    }

    private fun readSmtpLines(reader: BufferedReader): List<String> {
        val lines = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: break
            lines.add(line)
            if (line.length < 4 || line[3] == ' ') break
        }
        return lines
    }
}
