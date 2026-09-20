package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.ota.ChecksumVerifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumHashTest {

    @Test
    fun testBase64MetadataHashConvertsToHex() {
        // METADATA_HASH fixture from the real zorn OS4.0.0.8 payload_properties.txt.
        // AOSP defines METADATA_HASH as the SHA-256 of the first METADATA_SIZE bytes of payload.bin.
        val hex = ChecksumVerifier.toHex("WxQHwbXa74rRYMAd9/4PdI+bksl9Ssas5S8CrVbosz8=")
        assertEquals("5b1407c1b5daef8ad160c01df7fe0f748f9b92c97d4ac6ace52f02ad56e8b33f", hex)
    }

    @Test
    fun testHexHashPassesThroughLowercased() {
        val hex = ChecksumVerifier.toHex("5B1407C1B5DAEF8AD160C01DF7FE0F748F9B92C97D4AC6ACE52F02AD56E8B33F")
        assertEquals("5b1407c1b5daef8ad160c01df7fe0f748f9b92c97d4ac6ace52f02ad56e8b33f", hex)
    }
}
