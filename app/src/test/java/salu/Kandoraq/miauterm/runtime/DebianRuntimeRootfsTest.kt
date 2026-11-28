package salu.Kandoraq.miauterm.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import salu.Kandoraq.Miauterm.BuildConfig
import java.io.File

class DebianRuntimeRootfsTest {
    @Test
    fun generatedReadmeMatchesTheCurrentBuildAndNetworkBehavior() {
        val contents = DebianRuntime.managedReadmeContents()

        assertTrue(contents.contains(BuildConfig.VERSION_NAME))
        assertFalse(contents.contains("0.1.6-coral"))
        assertTrue(contents.contains("does not install SSH packages automatically"))
        assertTrue(contents.contains("checks GitHub Releases daily"))
    }

    @Test
    fun forcedResetDoesNotReuseAHealthyMatchingRootfs() {
        assertFalse(DebianRuntime.shouldReuseRootfs(force = true, rootfsWorks = true, versionMatches = true))
    }

    @Test
    fun matchingHealthyRootfsIsReusedOnlyWhenResetWasNotRequested() {
        assertTrue(DebianRuntime.shouldReuseRootfs(force = false, rootfsWorks = true, versionMatches = true))
        assertFalse(DebianRuntime.shouldReuseRootfs(force = false, rootfsWorks = false, versionMatches = true))
        assertFalse(DebianRuntime.shouldReuseRootfs(force = false, rootfsWorks = true, versionMatches = false))
    }

    @Test
    fun rejectsTruncatedAccountAndPackageCommands() {
        val root = createValidRootfs()
        try {
            File(root, "usr/sbin/useradd").writeText("\n")

            try {
                DebianRuntime.verifyRootfs(root)
                fail("A truncated useradd binary must invalidate the rootfs")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("/usr/sbin/useradd"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsAccountHomesThatEscapeTheRootfs() {
        val root = createValidRootfs()
        try {
            File(root, "etc/passwd").writeText(
                "root:x:0:0:root:/root:/bin/bash\n" +
                    "guest:x:1000:1000:guest:/../../data/data/private:/bin/bash\n"
            )

            assertNull(DebianRuntime.accountByName(root, "guest"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsCompleteArm64AccountAndPackageCommands() {
        val root = createValidRootfs()
        try {
            DebianRuntime.verifyRootfs(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createValidRootfs(): File {
        val root = kotlin.io.path.createTempDirectory("miauterm-rootfs-test").toFile()
        File(root, "etc").apply { mkdirs() }
        File(root, "etc/os-release").writeText("ID=debian\n")
        File(root, "etc/debian_version").writeText("13\n")
        File(root, "root").mkdirs()
        File(root, "bin").apply { mkdirs(); resolve("placeholder").writeText("") }
        File(root, "sbin").apply { mkdirs(); resolve("placeholder").writeText("") }

        listOf(
            "usr/bin/bash",
            "usr/bin/passwd",
            "usr/sbin/useradd",
            "usr/sbin/groupadd",
            "usr/sbin/userdel",
            "usr/sbin/usermod",
            "usr/bin/chown",
            "usr/bin/dpkg",
            "usr/bin/apt",
            "usr/bin/apt-get"
        ).forEach { path ->
            val command = File(root, path)
            command.parentFile.mkdirs()
            val elf = ByteArray(4096)
            elf[0] = 0x7f
            elf[1] = 'E'.code.toByte()
            elf[2] = 'L'.code.toByte()
            elf[3] = 'F'.code.toByte()
            elf[4] = 2
            elf[5] = 1
            elf[18] = 183.toByte()
            command.writeBytes(elf)
            command.setExecutable(true)
        }
        return root
    }
}
