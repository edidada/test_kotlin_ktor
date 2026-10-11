package ecshop.http

import ecshop.db.Db
import ecshop.db.execute
import ecshop.db.insert
import ecshop.db.long
import ecshop.db.selectOne
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.security.SecureRandom

/** Bearer-session auth. Only the SHA-256 hash of a token is ever stored. */

private val random = SecureRandom()
private val hexChars = "0123456789abcdef".toCharArray()

fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (b in digest) {
            append(hexChars[(b.toInt() shr 4) and 0x0F])
            append(hexChars[b.toInt() and 0x0F])
        }
    }
}

fun randomHex(byteLength: Int = 32): String {
    val bytes = ByteArray(byteLength)
    random.nextBytes(bytes)
    return buildString(byteLength * 2) {
        for (b in bytes) {
            append(hexChars[(b.toInt() shr 4) and 0x0F])
            append(hexChars[b.toInt() and 0x0F])
        }
    }
}

fun bearerToken(call: ApplicationCall): String? =
    call.request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = false) }
        ?.removePrefix("Bearer ")
        ?.takeIf { it.isNotBlank() }

suspend fun resolveUserId(db: Db, call: ApplicationCall): Long? {
    val token = bearerToken(call) ?: return null
    return db.read { c ->
        c.selectOne(
            "SELECT user_id FROM ecs_sessions WHERE token_hash = ?",
            listOf(sha256Hex(token)),
        ) { it.long("user_id") }
    }
}

/** Returns the authenticated user id or throws 401 unauthenticated. */
suspend fun requireUserId(db: Db, call: ApplicationCall): Long =
    resolveUserId(db, call) ?: throw unauthorized()

/** Issues a fresh token; the plaintext is returned to the client exactly once. */
suspend fun createSession(db: Db, userId: Long): String {
    val token = randomHex()
    db.transaction { c ->
        c.insert(
            "INSERT INTO ecs_sessions(token_hash, user_id) VALUES(?, ?)",
            listOf(sha256Hex(token), userId),
        )
    }
    return token
}

suspend fun destroySession(db: Db, call: ApplicationCall) {
    val token = bearerToken(call) ?: return
    db.transaction { c ->
        c.execute(
            "DELETE FROM ecs_sessions WHERE token_hash = ?",
            listOf(sha256Hex(token)),
        )
    }
}
