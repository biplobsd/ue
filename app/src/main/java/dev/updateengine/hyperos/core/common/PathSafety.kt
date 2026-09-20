package dev.updateengine.hyperos.core.common

/**
 * File names and paths end up inside `su -c` command lines, so anything that comes from a remote
 * catalogue entry or from a user picked document must be validated and quoted before it is
 * interpolated into a root shell command.
 */
object PathSafety {

    private val SAFE_FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun isSafeFileName(name: String): Boolean {
        if (name.length !in 1..180) return false
        if (name.contains("..")) return false
        return SAFE_FILE_NAME.matches(name)
    }

    /** Single-quotes a path for POSIX shells, escaping embedded single quotes. */
    fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
