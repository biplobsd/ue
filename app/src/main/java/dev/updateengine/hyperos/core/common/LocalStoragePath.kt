package dev.updateengine.hyperos.core.common

/**
 * Turns a document URI that the storage picker handed back into a real filesystem path.
 *
 * The zip has to be usable from the root shell (`unzip`, `update_engine`), and a `content://` URI is
 * not. Resolving a real path lets the engine read the user's existing download in place instead of
 * copying 7–8 GB next to it, which is what made the storage preflight fail on devices that followed
 * the guide's 16–20 GB advice.
 *
 * Only the shared-storage provider is resolved, because that is the one whose document id maps
 * directly onto a path. Anything else (Downloads provider media ids, cloud providers) returns null and
 * the caller falls back to copying the file.
 */
object LocalStoragePath {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "/storage/emulated/0"

    /** Returns a real path for a `file://` or external-storage `content://` URI, or null. */
    fun fromUriString(uri: String): String? {
        val value = uri.trim()
        return when {
            value.startsWith("file://") -> {
                val path = percentDecode(value.removePrefix("file://"))
                path.takeIf { it.startsWith("/") && isSharedStoragePath(it) }
            }

            value.startsWith("content://") -> {
                if (!value.contains(EXTERNAL_STORAGE_AUTHORITY)) return null
                val encodedDocId = when {
                    value.contains("/document/") -> value.substringAfterLast("/document/")
                    value.contains("/tree/") -> value.substringAfterLast("/tree/")
                    else -> return null
                }.substringBefore('?').substringBefore('#')
                if (encodedDocId.isBlank()) return null
                fromExternalStorageDocId(percentDecode(encodedDocId))
            }

            else -> null
        }
    }

    /**
     * Maps an external-storage document id (`primary:Download/DLManager/zorn-ota_full-….zip`, or
     * `1A2B-3C4D:OTA/x.zip` for a removable volume) onto its path.
     */
    fun fromExternalStorageDocId(docId: String): String? {
        val decoded = docId.trim()
        val volume = decoded.substringBefore(':', "")
        val relative = decoded.substringAfter(':', "")
        if (volume.isBlank() || relative.isBlank()) return null
        // A relative path that escapes the volume would let a crafted id address anything.
        if (relative.contains("..") || relative.startsWith("/")) return null

        val base = when (volume.lowercase()) {
            "primary", "emulated", "self" -> PRIMARY_VOLUME
            else -> "/storage/$volume"
        }
        val path = "$base/${relative.trim('/')}"
        return path.takeIf { isSharedStoragePath(it) }
    }

    /** True when the path points into shared storage, where the root shell can read the file. */
    fun isSharedStoragePath(path: String): Boolean {
        if (path.contains('\n') || path.contains('\r')) return false
        return path.startsWith("/storage/") || path.startsWith("/sdcard/")
    }

    /** Percent-decodes a URI component without turning '+' into a space (paths may contain '+'). */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
