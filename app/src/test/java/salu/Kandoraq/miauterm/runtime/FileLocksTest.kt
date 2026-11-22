package salu.Kandoraq.miauterm.runtime

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLocksTest {
    private val temporary = TemporaryFolder()

    @Test
    fun onlyOneRootfsOperationCanOwnTheSameLock() {
        temporary.create()
        val path = File(temporary.root, "files/.debian-install.lock")
        val first = FileLocks.tryLock(listOf(path))
        assertNotNull(first)
        try {
            assertNull(FileLocks.tryLock(listOf(path)))
        } finally {
            first?.close()
        }
        val retry = FileLocks.tryLock(listOf(path))
        assertNotNull(retry)
        retry?.close()
        temporary.delete()
    }
}
