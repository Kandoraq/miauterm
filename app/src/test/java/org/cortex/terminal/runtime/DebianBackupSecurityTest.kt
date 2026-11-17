package org.cortex.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DebianBackupSecurityTest {
    @Test
    fun rebasesAbsoluteSymlinkTargetsInsideTheGuestRootfs() {
        assertEquals("../lib/loader.so", DebianBackup.safeSymlinkTarget("usr/bin/loader", "/usr/lib/loader.so"))
        assertEquals("../proc/self/fd", DebianBackup.safeSymlinkTarget("dev/fd", "/proc/self/fd"))
    }

    @Test
    fun clampsRelativeSymlinkTraversalAtTheGuestRoot() {
        assertEquals(
            "../../data/private",
            DebianBackup.safeSymlinkTarget("usr/bin/tool", "../../../../data/private")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNulInSymlinkTargets() {
        DebianBackup.safeSymlinkTarget("usr/bin/tool", "../../safe\u0000/unsafe")
    }
}
