package dev.updateengine.hyperos.core.model

enum class Severity { BLOCKER, WARN, INFO }
enum class Outcome { PASS, FAIL, UNKNOWN }

data class Check(
    val id: String,
    val title: String,
    val severity: Severity,
    val outcome: Outcome,
    val detail: String,
    val remediation: String? = null
)

data class PreflightReport(
    val checks: List<Check> = emptyList()
) {
    val hasBlockers: Boolean
        get() = checks.any { it.severity == Severity.BLOCKER && it.outcome == Outcome.FAIL }

    val hasWarnings: Boolean
        get() = checks.any { it.severity == Severity.WARN && it.outcome == Outcome.FAIL }

    val isAllPassed: Boolean
        get() = checks.all { it.outcome == Outcome.PASS }
}
