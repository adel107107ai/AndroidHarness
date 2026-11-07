package com.androidharness.app.data.storage

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun old(file: File): File { file.setLastModified(1_000); return file }
    private suspend fun plan(root: File, file: File) = requireNotNull(StorageFiles.candidate("test", "Test", "", "Downloads", root, file, 10_000))

    /** f2fs (Android app data) refuses hardlinks; ext4 on CI allows them. */
    private fun hardlinksSupported(dir: File): Boolean = try {
        val a = File(dir, "probe-a").apply { writeText("x") }
        Files.createLink(File(dir, "probe-b").toPath(), a.toPath())
        File(dir, "probe-b").delete(); a.delete(); true
    } catch (_: Exception) { false }

    @Test fun `counts aliases hardlinks and nested roots only once without following links`() = runBlocking {
        val root = temp.newFolder("tree")
        org.junit.Assume.assumeTrue("hardlinks unsupported on this filesystem", hardlinksSupported(root))
        val source = File(root, "source").apply { writeText("hello") }
        Files.createLink(File(root, "alias").toPath(), source.toPath())
        Files.createSymbolicLink(File(root, "outside").toPath(), temp.root.toPath())
        val counter = StorageCounter()
        assertEquals(5, counter.bytes(root))
        assertEquals(0, counter.bytes(source))
        assertFalse(counter.incomplete)
    }
    @Test fun `cleanup deletes reviewed bytes while source recovery and fresh files remain`() = runBlocking {
        val root = temp.newFolder("safe")
        val source = File(root, "src.kt").apply { writeText("source") }
        val recovery = File(root, "checkpoint").apply { writeText("undo") }
        val download = old(File(root, "old.deb").apply { writeText("cache") })
        val fresh = File(root, "new.deb").apply { writeText("live") }
        val result = StorageFiles.clean(listOf(plan(root, download)))
        assertEquals(5L, result.removedBytes); assertEquals(1, result.removedFiles)
        assertFalse(download.exists()); assertEquals("source", source.readText()); assertEquals("undo", recovery.readText()); assertTrue(fresh.exists())
        assertNull(StorageFiles.candidate("x", "x", "", "", root, fresh, 10_000))
    }
    @Test fun `content modification with preserved size and mtime invalidates the preview`() = runBlocking {
        val root = temp.newFolder("stale")
        val file = old(File(root, "old.deb").apply { writeText("12345") })
        val candidate = plan(root, file)
        file.writeText("abcde"); old(file)
        val result = StorageFiles.clean(listOf(candidate))
        assertEquals(1, result.skipped); assertEquals("abcde", file.readText())
    }
    @Test fun `new child invalidates a reviewed directory and cleanup is idempotent`() = runBlocking {
        val root = temp.newFolder("cache")
        val folder = File(root, "pip").apply { mkdirs() }
        old(File(folder, "one").apply { writeText("cache") }); old(folder)
        val candidate = plan(root, folder)
        File(folder, "new").writeText("live")
        assertEquals(1, StorageFiles.clean(listOf(candidate)).skipped)
        assertTrue(File(folder, "one").exists()); assertTrue(File(folder, "new").exists())
        val single = plan(root, old(File(root, "other.deb").apply { writeText("ok") }))
        assertEquals(2L, StorageFiles.clean(listOf(single, single)).removedBytes)
        assertEquals(1, StorageFiles.clean(listOf(single)).skipped)
    }
    @Test fun `symlink ancestors and swapped target never delete outside roots`() = runBlocking {
        val root = temp.newFolder("root")
        val outside = temp.newFolder("outside")
        val original = old(File(root, "old.deb").apply { writeText("cache") })
        val candidate = plan(root, original)
        original.delete()
        val keep = File(outside, "keep").apply { writeText("source") }
        Files.createSymbolicLink(original.toPath(), keep.toPath())
        assertEquals(1, StorageFiles.clean(listOf(candidate)).skipped)
        assertEquals("source", keep.readText())
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        assertNull(StorageFiles.candidate("x", "x", "", "", root, File(root, "linked/keep"), Long.MAX_VALUE))
        assertFalse(StorageFiles.contained(root, root))
        assertFalse(StorageFiles.contained(root, keep))
    }
    @Test fun `cancelled scans and cleanup preserve files`() = runBlocking {
        val root = temp.newFolder("cancel")
        val file = old(File(root, "old.deb").apply { writeText("cache") })
        val candidate = plan(root, file)
        val job = launch(start = CoroutineStart.LAZY) { StorageFiles.clean(listOf(candidate)) }
        job.cancel(); job.join(); assertTrue(file.exists())
    }
}
