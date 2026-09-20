package dev.updateengine.hyperos.core.common

import dev.updateengine.hyperos.core.model.OtaTarget

/**
 * HyperOS build tags carry the regional resign in their tail:
 * `OS4.0.0.8.XOKCNXM` -> CN, `OS4.0.0.8.XOKMIXM` -> MI (Global), `OS4.0.0.8.XOKEUXM` -> EU (EEA).
 *
 * A package resigned for another region can fail userdata decryption and bootloop the device, so
 * the catalogue is restricted to the resign of the installed build. Virtual A/B also cannot
 * downgrade, so only strictly newer builds are selectable.
 */
object RomCompatibility {

    private val SUFFIX_REGION = Regex("([A-Z]{2})XM$")
    private val BARE_REGION = Regex("^([A-Z]{2})$")

    private val REGION_NAMES = mapOf(
        "CN" to "China",
        "MI" to "Global",
        "EU" to "EEA / Europe",
        "IN" to "India",
        "RU" to "Russia",
        "ID" to "Indonesia",
        "TW" to "Taiwan",
        "TR" to "Turkey",
        "JP" to "Japan",
        "KR" to "Korea",
        "TH" to "Thailand",
        "VN" to "Vietnam",
        "MY" to "Malaysia",
        "SG" to "Singapore",
        "MX" to "Mexico",
        "BR" to "Brazil"
    )

    /** Extracts the resign region from a build tag, e.g. "OS4.0.0.8.XOKCNXM" -> "CN". */
    fun region(buildTag: String): String? {
        val tag = buildTag.trim().uppercase()
        if (tag.isBlank()) return null
        val tail = tag.substringAfterLast('.')
        SUFFIX_REGION.find(tail)?.let { return it.groupValues[1] }
        return BARE_REGION.find(tail)?.groupValues?.get(1)
    }

    /** Human readable resign name, e.g. "CN" -> "China (CN)". */
    fun regionLabel(region: String?): String {
        if (region.isNullOrBlank()) return "Unknown"
        val name = REGION_NAMES[region.uppercase()] ?: return region
        return "$name ($region)"
    }

    /**
     * ROMs this device may install: same regional resign as the installed build and strictly
     * newer OS version, newest release first. When the installed region cannot be determined the
     * resign filter is skipped rather than hiding every entry.
     */
    fun selectableRoms(roms: List<OtaTarget>, installedBuild: String): List<OtaTarget> {
        val installedRegion = region(installedBuild)
        val hasInstalledBuild = installedBuild.isNotBlank()

        return roms
            .filter { rom ->
                installedRegion == null || region(rom.osVersion) == installedRegion
            }
            .filter { rom ->
                !hasInstalledBuild || VersionCompare.compare(rom.osVersion, installedBuild) > 0
            }
            .sortedWith { a, b ->
                val byRelease = b.releaseDate.compareTo(a.releaseDate)
                if (byRelease != 0) byRelease else VersionCompare.compare(b.osVersion, a.osVersion)
            }
    }
}
