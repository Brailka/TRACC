package app.tracc.data

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File

class AtomicWriteTest {
    @Test fun replacesExistingContents() {
        val dir = Files.createTempDirectory("tracc-atomic").toFile()
        try {
            val file = File(dir, "state.bin").apply { writeText("old") }
            SecureStore.atomicWrite(file, "new".toByteArray())
            assertEquals("new", file.readText())
            assertFalse(File(dir, "state.bin.tmp").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedReplacementPreservesExistingDestination() {
        val dir = Files.createTempDirectory("tracc-atomic").toFile()
        try {
            val destination = File(dir, "state.bin").apply { mkdir() }
            val sentinel = File(destination, "keep").apply { writeText("existing data") }
            assertTrue(runCatching { SecureStore.atomicWrite(destination, byteArrayOf(1)) }.isFailure)
            assertEquals("existing data", sentinel.readText())
        } finally { dir.deleteRecursively() }
    }
}
