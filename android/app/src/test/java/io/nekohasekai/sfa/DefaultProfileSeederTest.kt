package io.nekohasekai.sfa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultProfileSeederTest {
    private val uuid = "c5f2f082-3189-4b11-ae4d-e18d7c62bc1f"

    @Test
    fun parsesManagedVlessNode() {
        val node = DefaultProfileSeeder.parseVless(
            "vless://$uuid@vpn.example.com:443" +
                "?security=tls&type=ws&sni=vpn.example.com&host=vpn.example.com&path=%2Fws",
        )

        requireNotNull(node)
        assertEquals("vpn.example.com", node.server)
        assertEquals(443, node.port)
        assertEquals(uuid, node.uuid)
        assertEquals("vpn.example.com", node.sni)
        assertEquals("vpn.example.com", node.wsHost)
        assertEquals("/ws", node.path)
    }

    @Test
    fun rejectsUnsupportedOrInsecureNodes() {
        assertNull(DefaultProfileSeeder.parseVless("https://vpn.example.com"))
        assertNull(DefaultProfileSeeder.parseVless("vless://$uuid@vpn.example.com:443?security=none&type=ws"))
        assertNull(DefaultProfileSeeder.parseVless("vless://$uuid@vpn.example.com:443?security=tls&type=tcp"))
        assertNull(DefaultProfileSeeder.parseVless("vless://not-a-uuid@vpn.example.com:443?security=tls&type=ws"))
    }
}
