package co.podzim.inka.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatibilityTest {
    @Test
    fun `recognizes BOOX manufacturer or brand`() {
        assertTrue(
            DeviceCompatibility.isBooxDevice(
                AndroidDeviceProfile(
                    manufacturer = "ONYX",
                    brand = "BOOX",
                    model = "Note Air 5C",
                    device = "NoteAir5C",
                    product = "NoteAir5C",
                ),
            ),
        )
    }

    @Test
    fun `recognizes BOOX model even when manufacturer is generic`() {
        assertTrue(
            DeviceCompatibility.isBooxDevice(
                AndroidDeviceProfile(
                    manufacturer = "Android",
                    brand = "Android",
                    model = "BOOX Tab Ultra",
                    device = "tabultra",
                    product = "tabultra",
                ),
            ),
        )
    }

    @Test
    fun `does not recognize standard non BOOX devices`() {
        assertFalse(
            DeviceCompatibility.isBooxDevice(
                AndroidDeviceProfile(
                    manufacturer = "Google",
                    brand = "google",
                    model = "Pixel Tablet",
                    device = "tangorpro",
                    product = "tangorpro",
                ),
            ),
        )
    }
}
