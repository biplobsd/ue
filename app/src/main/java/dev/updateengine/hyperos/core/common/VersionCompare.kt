package dev.updateengine.hyperos.core.common

object VersionCompare {

    /**
     * Compares two HyperOS/MIUI version strings numerically segment-by-segment.
     * Examples:
     * "OS4.0.0.8.XOKCNXM" vs "OS3.0.308.0.WOKCNXM" -> > 0
     * "OS3.0.308.0" vs "OS3.0.9.0" -> > 0 (308 > 9)
     * "OS1.0.1.0" vs "OS1.0.1.0" -> == 0
     */
    fun compare(v1: String, v2: String): Int {
        val s1 = normalize(v1)
        val s2 = normalize(v2)

        val len = maxOf(s1.size, s2.size)
        for (i in 0 until len) {
            val p1 = s1.getOrNull(i) ?: "0"
            val p2 = s2.getOrNull(i) ?: "0"

            val n1 = p1.toLongOrNull()
            val n2 = p2.toLongOrNull()

            if (n1 != null && n2 != null) {
                val cmp = n1.compareTo(n2)
                if (cmp != 0) return cmp
            } else {
                val cmp = p1.compareTo(p2, ignoreCase = true)
                if (cmp != 0) return cmp
            }
        }
        return 0
    }

    private fun normalize(v: String): List<String> {
        // Strip common prefixes like "OS", "V"
        var clean = v.trim()
        if (clean.startsWith("OS", ignoreCase = true)) {
            clean = clean.substring(2)
        } else if (clean.startsWith("V", ignoreCase = true)) {
            clean = clean.substring(1)
        }

        // Split by dots or dashes
        return clean.split('.', '-').filter { it.isNotBlank() }
    }
}
