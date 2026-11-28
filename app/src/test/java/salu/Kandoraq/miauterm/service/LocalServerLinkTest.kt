package salu.Kandoraq.miauterm.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalServerLinkTest {
    @Test
    fun acceptsHttpAndHttpsLocalServerLinks() {
        assertTrue(isCopyableLocalServerUrl("http://localhost:8080/"))
        assertTrue(isCopyableLocalServerUrl("https://localhost:8443/"))
    }

    @Test
    fun rejectsLinksOutsideTheLocalServerAllowlist() {
        assertFalse(isCopyableLocalServerUrl("ftp://localhost:8080/"))
        assertFalse(isCopyableLocalServerUrl("http://example.com:8080/"))
        assertFalse(isCopyableLocalServerUrl("http://localhost/"))
        assertFalse(isCopyableLocalServerUrl("http://localhost:0/"))
        assertFalse(isCopyableLocalServerUrl("http://localhost:65536/"))
        assertFalse(isCopyableLocalServerUrl("http://localhost:not-a-port/"))
        assertFalse(isCopyableLocalServerUrl("http://localhost:8080.evil/"))
    }
}
