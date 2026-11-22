package com.ctm.credit.model

/** Repayment frequency. [wire] is the value used by the server API. */
enum class Frequency(val wire: String, val periodDays: Int) {
    DAILY("daily", 1),
    WEEKLY("weekly", 7),
    MONTHLY("monthly", 30),
    ;

    companion object {
        fun fromWire(value: String): Frequency =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown frequency: $value")
    }
}
