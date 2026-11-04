package com.androidharness.app.data.transfer

import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTP
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class FtpShareServerTest {
    private fun fixture(readOnly: Boolean = false, guard: (() -> Unit) -> Unit = { it() }, block: (LocalTransferFiles, FtpShareServer) -> Unit) {
        val root = Files.createTempDirectory("ftp-share").toFile()
        val files = LocalTransferFiles(root)
        val server = FtpShareServer(files, "fixture", "fixture-password", readOnly, guard)
        try { server.start(0); block(files, server) } finally { server.close(); root.deleteRecursively() }
    }
    private fun client(server: FtpShareServer): FTPClient = FTPClient().apply {
        controlEncoding = "UTF-8"; connectTimeout = 3000; defaultTimeout = 3000
        setDataTimeout(java.time.Duration.ofSeconds(3)); connect("127.0.0.1", server.port)
        assertTrue(login("fixture", "fixture-password")); enterLocalPassiveMode(); setUseEPSVwithIPv4(true); setFileType(FTP.BINARY_FILE_TYPE)
    }
    @Test fun realFtpClientCanListUploadDownloadRenameAndRemoveBinaryFiles() = fixture { files, server ->
        val ftp = client(server)
        try {
            assertTrue(ftp.makeDirectory("new folder"))
            val bytes = byteArrayOf(0, 1, -1, 13, 10, 65)
            assertTrue(ftp.storeFile("new folder/a + b.bin", bytes.inputStream()))
            assertEquals(1, ftp.mlistDir("new folder").size)
            assertEquals("a + b.bin", ftp.mlistDir("new folder").single().name)
            assertEquals(6L, ftp.mlistFile("new folder/a + b.bin").size)
            val output = ByteArrayOutputStream(); assertTrue(ftp.retrieveFile("new folder/a + b.bin", output)); assertArrayEquals(bytes, output.toByteArray())
            assertTrue(ftp.rename("new folder/a + b.bin", "new folder/renamed.bin"))
            assertTrue(ftp.storeFile("new folder/renamed.bin", "replaced".byteInputStream()))
            assertEquals("replaced", files.read("new folder/renamed.bin").bufferedReader().use { it.readText() })
            assertTrue(ftp.deleteFile("new folder/renamed.bin")); assertTrue(ftp.removeDirectory("new folder"))
            assertEquals(502, ftp.sendCommand("PORT", "127,0,0,1,1,2"))
        } finally { ftp.disconnect() }
    }
    @Test fun readOnlySharesRejectEveryMutation() = fixture(readOnly = true) { files, server ->
        files.writeAtomic("keep", "original".byteInputStream()) {}
        val ftp = client(server)
        try {
            assertFalse(ftp.storeFile("keep", "replacement".byteInputStream()))
            assertFalse(ftp.deleteFile("keep")); assertFalse(ftp.rename("keep", "moved")); assertFalse(ftp.makeDirectory("new"))
            assertEquals("original", files.read("keep").bufferedReader().use { it.readText() })
        } finally { ftp.disconnect() }
    }
    @Test fun loginIsRequiredAndWrongCredentialsAreRejected() = fixture { _, server ->
        val ftp = FTPClient()
        try {
            ftp.connect("127.0.0.1", server.port)
            assertEquals(530, ftp.sendCommand("RETR", "file"))
            assertFalse(ftp.login("fixture", "wrong")); assertFalse(ftp.login("other", "fixture-password"))
            assertTrue(ftp.login("fixture", "fixture-password"))
        } finally { ftp.disconnect() }
    }
    @Test fun traversalAndSymlinksCannotExposeFilesOutsideTheSharedRoot() = fixture { files, server ->
        val outside = Files.createTempFile("outside-ftp", ".txt").toFile().apply { writeText("secret") }
        try {
            Files.createSymbolicLink(files.root.resolve("escape"), outside.toPath())
            val ftp = client(server)
            try {
                assertEquals(550, ftp.sendCommand("SIZE", "../${outside.name}"))
                assertEquals(550, ftp.sendCommand("SIZE", "escape"))
                assertFalse(ftp.changeWorkingDirectory("/../../etc"))
                assertTrue(ftp.mlistDir("/").none { it.name == "escape" })
            } finally { ftp.disconnect() }
        } finally { outside.delete() }
    }
    @Test fun anActiveWorkspaceGuardBlocksPublication() = fixture(guard = { throw IllegalStateException("Agent is working") }) { files, server ->
        files.writeAtomic("keep", "original".byteInputStream()) {}
        val ftp = client(server)
        try {
            assertFalse(ftp.storeFile("keep", "replacement".byteInputStream()))
            assertFalse(ftp.deleteFile("keep"))
            assertEquals("original", files.read("keep").bufferedReader().use { it.readText() })
        } finally { ftp.disconnect() }
    }
    @Test fun stopClosesAnInProgressUploadWithoutPublishingAPartialFile() = fixture { files, server ->
        files.writeAtomic("keep", "original".byteInputStream()) {}
        Socket("127.0.0.1", server.port).use { control ->
            control.soTimeout = 3000
            val input = control.getInputStream().bufferedReader(); val output = control.getOutputStream().bufferedWriter()
            assertTrue(input.readLine().startsWith("220"))
            fun command(value: String): String { output.write("$value\r\n"); output.flush(); return input.readLine() }
            assertTrue(command("USER fixture").startsWith("331")); assertTrue(command("PASS fixture-password").startsWith("230"))
            val epsv = command("EPSV"); val port = epsv.substringAfter("|||").substringBefore('|').toInt()
            Socket("127.0.0.1", port).use { data ->
                assertTrue(command("STOR keep").startsWith("150")); data.getOutputStream().write("partial".toByteArray()); data.getOutputStream().flush()
                server.close()
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (files.root.toFile().listFiles()!!.any { it.name.startsWith(".harness-transfer-") } && System.nanoTime() < until) Thread.sleep(20)
            assertEquals("original", files.read("keep").bufferedReader().use { it.readText() })
            val names = files.root.toFile().listFiles()!!.map { it.name }
            assertEquals("leftover entries: $names", listOf("keep"), names)
            assertEquals(-1, control.getInputStream().read())
        }
    }
    @Test fun syncClientUsesTheServerForPreviewsAndAtomicUploadsAndDownloads() = fixture { remoteRoot, server ->
        val localDir = Files.createTempDirectory("ftp-sync-client").toFile()
        try {
            val local = LocalTransferFiles(localDir)
            local.writeAtomic("folder/a.txt", "one".byteInputStream()) {}
            remoteRoot.writeAtomic("only-remote", "keep".byteInputStream()) {}
            val profile = SyncProfile(protocol = SyncProtocol.FTP, host = "127.0.0.1", port = server.port, username = "fixture", password = "fixture-password")
            FtpTransferFiles(profile).use { remote ->
                remote.connect()
                val plan = SyncEngine.preview(local, remote, SyncDirection.UPLOAD)
                assertEquals(SyncResult(1, 0), SyncEngine.apply(local, remote, plan, true))
                assertNotNull(remote.stat("only-remote")); assertEquals(1, SyncEngine.preview(local, remote, SyncDirection.UPLOAD).unchanged)
                remoteRoot.writeAtomic("folder/a.txt", "two".byteInputStream()) {}
                val download = SyncEngine.preview(local, remote, SyncDirection.DOWNLOAD)
                assertEquals(SyncResult(2, 0), SyncEngine.apply(local, remote, download, true))
                assertEquals("two", local.read("folder/a.txt").bufferedReader().use { it.readText() })
            }
        } finally { localDir.deleteRecursively() }
    }
    @Test fun passiveDataConnectionsMustComeFromTheAuthenticatedControlPeer() = fixture { files, server ->
        files.writeAtomic("keep", "secret".byteInputStream()) {}
        Socket("127.0.0.1", server.port).use { control ->
            control.soTimeout = 3000
            val input = control.getInputStream().bufferedReader(); val output = control.getOutputStream().bufferedWriter()
            input.readLine()
            fun command(value: String): String { output.write("$value\r\n"); output.flush(); return input.readLine() }
            command("USER fixture"); command("PASS fixture-password")
            val port = command("EPSV").substringAfter("|||").substringBefore('|').toInt()
            Socket().use { data ->
                data.bind(InetSocketAddress("127.0.0.2", 0)); data.connect(InetSocketAddress("127.0.0.1", port)); data.soTimeout = 3000
                assertTrue(command("RETR keep").startsWith("150")); assertTrue(input.readLine().startsWith("426"))
                assertEquals(-1, data.getInputStream().read())
            }
        }
    }
}
