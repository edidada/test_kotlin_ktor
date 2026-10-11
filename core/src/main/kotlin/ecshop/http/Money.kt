package ecshop.http

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Decimal-string money helpers. JSON never carries floats for money; the server works in
 * integer cents internally and formats two-decimal strings for responses.
 */
object Money {
    private val DECIMAL_RE = Regex("^-?\\d{1,12}(\\.\\d{1,2})?$")

    /** Parses a decimal string like "99.90" into cents; null when malformed. */
    fun parseCents(raw: String?): Long? {
        if (raw == null || !DECIMAL_RE.matches(raw)) return null
        return runCatching { BigDecimal(raw).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }
            .getOrNull()
    }

    /** Parses a decimal string into cents, throwing 400 validation on format errors. */
    fun requireCents(raw: String?, field: String): Long =
        parseCents(raw) ?: throw validationError("$field must be a decimal string with at most 2 fractional digits", field)

    /** Formats integer cents as a two-decimal string, e.g. 10780 -> "107.80". */
    fun fromCents(cents: Long): String =
        BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** Converts a DECIMAL column value to cents. */
    fun toCents(decimal: BigDecimal): Long = decimal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}
