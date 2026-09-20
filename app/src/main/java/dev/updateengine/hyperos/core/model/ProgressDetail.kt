package dev.updateengine.hyperos.core.model

sealed interface ProgressDetail {
    data class Bytes(
        val done: Long,
        val total: Long,
        val rateBps: Double = 0.0,
        val etaSecs: Long? = null
    ) : ProgressDetail {
        val fraction: Float
            get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    data class Percent(
        val value: Float, // 0.0f to 1.0f
        val caption: String? = null
    ) : ProgressDetail

    data object Indeterminate : ProgressDetail
}
