package co.podzim.inka.device

import android.os.Build

internal data class AndroidDeviceProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
) {
    companion object {
        fun current(): AndroidDeviceProfile {
            return AndroidDeviceProfile(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                brand = Build.BRAND.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                product = Build.PRODUCT.orEmpty(),
            )
        }
    }
}

internal object DeviceCompatibility {
    fun isBooxDevice(profile: AndroidDeviceProfile = AndroidDeviceProfile.current()): Boolean {
        return listOf(
            profile.manufacturer,
            profile.brand,
            profile.model,
            profile.device,
            profile.product,
        ).any { field ->
            val normalized = field.trim().lowercase()
            normalized.contains("boox") || normalized.contains("onyx")
        }
    }
}
