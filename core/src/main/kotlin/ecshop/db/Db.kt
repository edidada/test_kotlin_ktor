package ecshop.db

import ecshop.db.SqlScript
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Process-wide SQLite access point. All SQL is bound through placeholders; the mutex keeps
 * JDBC's single-connection semantics safe under concurrent request handling and gives
 * transactions a serialized writer, matching the local-development goal of the blueprint.
 */
class Db(private val path: String) {
    private val mutex = java.util.concurrent.locks.ReentrantLock()
    private var connection: Connection? = null

    private fun openConnection(): Connection {
        File(path).parentFile?.mkdirs()
        val c = DriverManager.getConnection("jdbc:sqlite:$path")
        c.autoCommit = true
        c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        connection = c
        return c
    }

    private val conn: Connection
        get() = connection ?: openConnection()

    /** Executes [block] against a fresh read; callers must not keep ResultSet references outside. */
    fun <T> read(block: (Connection) -> T): T {
        mutex.lock()
        try {
            return block(conn)
        } finally {
            mutex.unlock()
        }
    }

    /** Runs [block] inside one transaction; rolls back on any exception. */
    fun <T> transaction(block: (Connection) -> T): T {
        mutex.lock()
        try {
            val c = conn
            if (c.autoCommit) {
                c.autoCommit = false
                try {
                    val result = block(c)
                    c.commit()
                    return result
                } catch (e: Exception) {
                    runCatching { c.rollback() }
                    throw e
                } finally {
                    c.autoCommit = true
                }
            }
            return block(c)
        } finally {
            mutex.unlock()
        }
    }

    /** Applies the bundled SQLite schema + seed script; safe to run repeatedly. */
    fun initializeSchema() {
        val script = SqlScript.loadResource("/sql/001_ecshop_catalog.sql")
        read { c ->
            c.createStatement().use { stmt ->
                SqlScript.split(script).forEach { stmt.execute(it) }
            }
        }
    }

    fun close() {
        connection?.close()
        connection = null
    }
}
