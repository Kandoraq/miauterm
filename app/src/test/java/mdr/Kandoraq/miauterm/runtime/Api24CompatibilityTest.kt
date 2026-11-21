package mdr.Kandoraq.miauterm.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], shadows = [ShadowJavaFilesOs::class])
class Api24CompatibilityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsAndVerifiesFileOnApi24() {
        val file = File(temporaryFolder.root, "bin/tool")
        assertTrue(FileInstaller.install(file, "payload", 493))
        assertEquals("payload", file.readText())
        assertTrue(FileInstaller.holds(file, "payload".toByteArray()))
        assertTrue(file.canExecute())
    }

    @Test
    fun detectsSymlinkWithoutNioOnApi24() {
        val target = File(temporaryFolder.root, "target")
        target.writeText("x")
        val link = File(temporaryFolder.root, "link")
        assertTrue(ElfLinkerPatcher.createSymlink("target", link))
        assertTrue(ElfLinkerPatcher.isSymlink(link))
        assertEquals("target", ElfLinkerPatcher.readSymlink(link))
    }
}
