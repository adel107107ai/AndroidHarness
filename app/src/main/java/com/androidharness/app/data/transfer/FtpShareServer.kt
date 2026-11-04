package com.androidharness.app.data.transfer

import java.io.*
import java.net.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Passive FTP only. No shell commands, anonymous access, active data connections or symlinks. */
internal class FtpShareServer(
    private val files: LocalTransferFiles,
    private val username: String,
    private val password: String,
    private val readOnly: Boolean = true,
    private val writeGuard: (() -> Unit) -> Unit = { it() },
    private val clientsChanged: (Int) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val count = AtomicInteger()
    private val sockets = ConcurrentHashMap.newKeySet<Closeable>()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "FTP-share-client").apply { isDaemon = true } }
    private val slots = Semaphore(4)
    @Volatile private var listener: ServerSocket? = null
    val port get() = listener?.localPort ?: 0
    init {
        require(username.isNotBlank() && username.length <= 100 && username.none { it.isWhitespace() || it.isISOControl() }) { "Enter a username without spaces." }
        require(password.length in 8..256 && password.none(Char::isISOControl)) { "Use a password with at least 8 characters." }
    }
    fun start(port: Int = 2121, lan: Boolean = false) {
        check(listener == null && !closed.get()) { "Server already started." }
        require(port == 0 || port in 1024..65535) { "Use a port from 1024 to 65535." }
        val server = ServerSocket().apply { reuseAddress = true }
        try { server.bind(InetSocketAddress(if (lan) "0.0.0.0" else "127.0.0.1", port), 4) }
        catch (e: Exception) { server.close(); throw e }
        listener = server
        Thread({
            try { while (!closed.get()) {
                val socket = server.accept()
                if (lan && !socket.inetAddress.isLoopbackAddress && !socket.inetAddress.isSiteLocalAddress) { socket.close(); continue }
                if (!slots.tryAcquire()) { socket.close(); continue }
                sockets += socket
                // close() may have finished its broadcast before this socket
                // joined the set; re-check so shutdown cannot miss it.
                if (closed.get()) { sockets -= socket; slots.release(); runCatching { socket.close() }; continue }
                pool.execute {
                    clientsChanged(count.incrementAndGet())
                    try { socket.use { Session(it).serve() } } catch (_: IOException) { }
                    finally { sockets -= socket; slots.release(); clientsChanged(count.decrementAndGet()) }
                }
            } } catch (_: SocketException) { }
        }, "FTP-share-listener").apply { isDaemon = true; start() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener?.close() }; sockets.forEach { runCatching { it.close() } }; pool.shutdownNow()
        // A barrier, not a race: an aborted session must finish unwinding (and
        // delete its staged .harness-transfer-* file) before callers observe
        // the shared folder again. Without it a cancelled STOR leaves its temp
        // file behind and every later listing shows it.
        runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }
    }
    private inner class Session(private val socket: Socket) {
        private val input = socket.getInputStream().buffered()
        private val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        private var loggedIn = false
        private var userAccepted = false
        private var attempts = 0
        private var cwd = ""
        private var passive: ServerSocket? = null
        private var renameFrom: String? = null
        private fun reply(code: Int, text: String) { output.write("$code $text\r\n"); output.flush() }
        private fun lines(vararg value: String) { value.forEach { output.write(it + "\r\n") }; output.flush() }
        private fun line(): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 8192) {
                val next = input.read(); if (next < 0) return null
                if (next == 10) return bytes.toString("UTF-8").removeSuffix("\r")
                bytes.write(next)
            }
            throw IOException("FTP command is too long")
        }
        private fun path(value: String) = TransferPaths.ftp(cwd, value)
        private fun writable(action: () -> Unit) { check(!readOnly) { "This share is read only." }; writeGuard(action) }
        fun serve() {
            socket.soTimeout = 60_000
            try {
                reply(220, "AndroidHarness FTP ready")
                while (!closed.get()) {
                    val request = line() ?: return
                    val command = request.substringBefore(' ').uppercase(java.util.Locale.ROOT)
                    val argument = request.substringAfter(' ', "")
                    if (command != "RNTO" && command != "RNFR") renameFrom = null
                    try {
                        when (command) {
                            "QUIT" -> { reply(221, "Goodbye"); return }
                            "USER" -> { loggedIn = false; userAccepted = equal(argument, username); reply(331, "Password required") }
                            "PASS" -> {
                                loggedIn = userAccepted && equal(argument, password)
                                if (loggedIn) reply(230, "Logged in") else { reply(530, "Login failed"); if (++attempts >= 3) return }
                            }
                            "FEAT" -> lines("211-Features", " UTF8", " EPSV", " MLST type*;size*;modify*;", " SIZE", " MDTM", "211 End")
                            "SYST" -> reply(215, "UNIX Type: L8")
                            "OPTS" -> reply(200, "Options accepted")
                            "NOOP" -> reply(200, "OK")
                            else -> {
                                if (!loggedIn) { reply(530, "Log in first"); continue }
                                when (command) {
                                    "PWD", "XPWD" -> reply(257, "\"/${cwd.replace("\"", "\"\"")}\" is the current directory")
                                    "TYPE" -> if (argument.uppercase() in listOf("I", "A", "L 8")) reply(200, "Type set") else reply(504, "Unsupported type")
                                    "CWD", "XCWD", "CDUP" -> {
                                        val target = path(if (command == "CDUP") ".." else argument)
                                        require(files.stat(target)?.directory == true) { "Folder unavailable." }
                                        cwd = target; reply(250, "Directory changed")
                                    }
                                    "EPSV", "PASV" -> openPassive(command)
                                    "PORT", "EPRT" -> reply(502, "Use passive mode")
                                    "LIST", "NLST", "MLSD" -> listing(command, argument)
                                    "MLST" -> {
                                        val target = path(argument); val entry = requireNotNull(files.stat(target)) { "File unavailable." }
                                        lines("250-Listing", " ${facts(entry)} /$target", "250 End")
                                    }
                                    "SIZE" -> { val e = requireNotNull(files.stat(path(argument))); require(!e.directory); reply(213, e.size.toString()) }
                                    "MDTM" -> { val e = requireNotNull(files.stat(path(argument))); reply(213, stamp(e.modified)) }
                                    "RETR" -> {
                                        val target = path(argument)
                                        require(files.stat(target)?.let { !it.directory && it.size <= MAX_TRANSFER_FILE } == true) { "File unavailable or larger than 512 MB." }
                                        data { connection -> files.read(target).use { source -> copyTransfer(source, connection.getOutputStream()); connection.getOutputStream().flush() } }
                                    }
                                    "STOR" -> {
                                        check(!readOnly) { "This share is read only." }
                                        val target = path(argument); require(target.isNotEmpty()); files.file(target)
                                        data { connection -> writable { files.writeAtomic(target, connection.getInputStream()) {} } }
                                    }
                                    "MKD", "XMKD" -> { writable { files.mkdir(path(argument)) }; reply(257, "Directory created") }
                                    "DELE" -> {
                                        writable { val target = path(argument); require(files.stat(target)?.directory == false); check(files.file(target).delete()) }
                                        reply(250, "File deleted")
                                    }
                                    "RMD", "XRMD" -> {
                                        writable { val target = path(argument); require(target.isNotEmpty() && files.stat(target)?.directory == true); check(files.file(target).delete()) { "Folder is not empty." } }
                                        reply(250, "Directory removed")
                                    }
                                    "RNFR" -> { check(!readOnly); val target = path(argument); require(target.isNotEmpty() && files.stat(target) != null); renameFrom = target; reply(350, "Send RNTO") }
                                    "RNTO" -> {
                                        val from = requireNotNull(renameFrom); renameFrom = null
                                        writable {
                                            val target = path(argument); require(target.isNotEmpty())
                                            val a = files.file(from); val b = files.file(target)
                                            require(!b.exists() || a.isFile && b.isFile) { "Destination already exists." }
                                            try { Files.move(a.toPath(), b.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                                            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(a.toPath(), b.toPath(), REPLACE_EXISTING) }
                                        }
                                        reply(250, "Renamed")
                                    }
                                    else -> reply(502, "Command not supported")
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.CancellationException) { return }
                    catch (_: Exception) { reply(550, "Operation unavailable; check the path, permissions and active tasks") }
                }
            } finally { closePassive() }
        }
        private fun closePassive() { passive?.let { sockets -= it; runCatching { it.close() } }; passive = null }
        private fun openPassive(command: String) {
            closePassive()
            val server = ServerSocket(0, 1, socket.localAddress).apply { soTimeout = 15_000 }
            passive = server; sockets += server
            if (command == "EPSV") reply(229, "Entering Extended Passive Mode (|||${server.localPort}|)")
            else {
                val bytes = socket.localAddress.address
                require(bytes.size == 4) { "Use EPSV for IPv6." }
                val address = bytes.map { it.toInt() and 255 } + listOf(server.localPort / 256, server.localPort % 256)
                reply(227, "Entering Passive Mode (${address.joinToString(",")})")
            }
        }
        private fun data(action: (Socket) -> Unit) {
            val server = passive
            if (server == null) { reply(425, "Use PASV or EPSV first"); return }
            reply(150, "Opening data connection")
            try {
                server.accept().use { connection ->
                    sockets += connection
                    // Register-then-recheck (same window as the control loop):
                    // a socket accepted just after close()'s broadcast would
                    // otherwise block forever in read() and strand its temp file.
                    if (closed.get()) runCatching { connection.close() }
                    try {
                        require(connection.inetAddress == socket.inetAddress) { "Data connection must use the same peer." }
                        connection.soTimeout = 15_000
                        action(connection)
                    } finally { sockets -= connection }
                }
                reply(226, "Transfer complete")
            } catch (_: Exception) { reply(426, "Transfer interrupted; changes were not published") }
            finally { closePassive() }
        }
        private fun listing(command: String, argument: String) {
            val target = path(if (argument.startsWith('-')) "" else argument)
            val entry = requireNotNull(files.stat(target))
            val entries = if (entry.directory) files.list(target) else listOf(entry)
            data { connection ->
                val writer = connection.getOutputStream().bufferedWriter(Charsets.UTF_8)
                entries.forEach { e ->
                    val name = e.path.substringAfterLast('/')
                    val text = when (command) {
                        "NLST" -> name
                        "MLSD" -> "${facts(e)} $name"
                        else -> "${if (e.directory) "drwxr-xr-x" else "-rw-r--r--"} 1 user group ${e.size} ${DateTimeFormatter.ofPattern("MMM dd yyyy", java.util.Locale.US).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(e.modified))} $name"
                    }
                    writer.write(text + "\r\n")
                }
                writer.flush()
            }
        }
    }
    private fun facts(e: TransferEntry) = "type=${if (e.directory) "dir" else "file"};size=${e.size};modify=${stamp(e.modified)};"
    private fun stamp(time: Long) = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(time))
    private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
