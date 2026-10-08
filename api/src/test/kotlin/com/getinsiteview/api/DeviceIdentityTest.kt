package com.getinsiteview.api

import com.getinsiteview.core.InMemoryKeyValueStore
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@DisplayName("Device identity")
class DeviceIdentityTest {
    @Test
    fun `Creates a UUID once and reuses it`() = test {
        val store = InMemoryDeviceIdStore()
        val first = DeviceIdentity.current(store)
        assertEquals(36, first.length)
        assertEquals(first, UUID.fromString(first).toString())
        assertEquals(first, DeviceIdentity.current(store))
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    fun `Replaces a stored value the API would reject`(stored: String) = test {
        val store = InMemoryDeviceIdStore(stored)
        assertEquals("new-device-id", DeviceIdentity.current(store) { "new-device-id" })
        assertEquals("new-device-id", store.read())
    }

    @Test
    @DisplayName("Valid ids - 8–64 letters, digits, - or _")
    fun `Valid ids - 8-64 letters, digits, - or _`() {
        assertTrue(DeviceIdentity.isValid("3f1c2d4e-0000-4000-8000-000000000001"))
        assertTrue(DeviceIdentity.isValid("device_1234"))
        assertFalse(DeviceIdentity.isValid("device.1234"))
        assertFalse(DeviceIdentity.isValid("1234567"))
    }

    /** iOS: the `UserDefaults` store; here the [KeyValueDeviceIdStore] on a key-value store. */
    @Test
    fun `UserDefaults store`() = test {
        val defaults = InMemoryKeyValueStore()
        val store = KeyValueDeviceIdStore(defaults)
        assertNull(store.read())
        val id = DeviceIdentity.current(store)
        assertEquals(id, KeyValueDeviceIdStore(defaults).read())
        assertEquals(id, defaults.snapshot["com.getinsiteview.deviceId"])
    }

    companion object {
        @JvmStatic
        fun invalidIds(): List<String> = listOf("short", "has space in it", "ünïcode-device-id", "a".repeat(65))
    }
}
