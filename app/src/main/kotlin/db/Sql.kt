package ecshop.db

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/** JDBC helpers shared by every route module. All parameters go through placeholders. */

private fun bind(ps: PreparedStatement, params: List<Any?>) {
    params.forEachIndexed { index, value ->
        val i = index + 1
        when (value) {
            null -> ps.setObject(i, null)
            is Int -> ps.setInt(i, value)
            is Long -> ps.setLong(i, value)
            is Short -> ps.setShort(i, value)
            is Double -> ps.setDouble(i, value)
            is BigDecimal -> ps.setBigDecimal(i, value)
            is Boolean -> ps.setInt(i, if (value) 1 else 0)
            else -> ps.setString(i, value.toString())
        }
    }
}

fun <T> Connection.select(sql: String, params: List<Any?> = emptyList(), mapper: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        bind(ps, params)
        ps.executeQuery().use { rs ->
            val rows = ArrayList<T>()
            while (rs.next()) rows.add(mapper(rs))
            rows
        }
    }

fun <T> Connection.selectOne(sql: String, params: List<Any?> = emptyList(), mapper: (ResultSet) -> T): T? =
    select(sql, params, mapper).firstOrNull()

fun Connection.exists(sql: String, params: List<Any?> = emptyList()): Boolean =
    selectOne(sql, params) { it.long("found") }?.let { it > 0 } ?: false

fun Connection.execute(sql: String, params: List<Any?> = emptyList()): Int =
    prepareStatement(sql).use { ps ->
        bind(ps, params)
        ps.executeUpdate()
    }

/** Inserts a row and returns its AUTOINCREMENT primary key. */
fun Connection.insert(sql: String, params: List<Any?> = emptyList()): Long =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
        bind(ps, params)
        ps.executeUpdate()
        ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else error("insert returned no key") }
    }

// ResultSet convenience accessors -------------------------------------------------------------

fun ResultSet.str(col: String): String = getString(col) ?: ""

fun ResultSet.strOrNull(col: String): String? = getString(col)

fun ResultSet.long(col: String): Long = getLong(col)

fun ResultSet.int(col: String): Int = getInt(col)

fun ResultSet.longOrNull(col: String): Long? {
    val v = getLong(col)
    return if (wasNull()) null else v
}

fun ResultSet.intOrNull(col: String): Int? {
    val v = getInt(col)
    return if (wasNull()) null else v
}

/** Money as a two-decimal string; never a JSON float. */
fun ResultSet.amount(col: String): String =
    getBigDecimal(col)?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "0.00"

/** Integer-cent column as Long. */
fun ResultSet.cents(col: String): Long =
    getBigDecimal(col)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() ?: 0L

/** Two-decimal yuan DECIMAL column converted to integer cents (8.00 -> 800). */
fun ResultSet.yuanCents(col: String): Long =
    getBigDecimal(col)?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact() ?: 0L
