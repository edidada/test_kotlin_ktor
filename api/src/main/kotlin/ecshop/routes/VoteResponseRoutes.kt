package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.http.conflict
import ecshop.http.invalidState
import ecshop.http.notFound
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/**
 * M3 — POST /api/v1/votes/{id}/responses (maps PHP vote.php submission).
 * Single-choice votes accept exactly one option; multi-choice up to 20 distinct ids.
 * The client IP is taken from the socket, never from forwarding headers. The unique
 * (vote_id, ip_address) vote log is inserted first inside the transaction, so a duplicate
 * submission from the same address fails with 409 before any counter is touched.
 */
@Serializable
data class VoteResponseRequest(val option_ids: List<Long> = emptyList())

fun Route.voteResponseRoutes(svc: AppServices) {
    post("/votes/{id}/responses") {
        val voteId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("vote not found")
        val req = call.receive<VoteResponseRequest>()
        val options = req.option_ids.distinct()
        if (options.isEmpty() || options.size > 20 || options.any { it <= 0 }) {
            throw validationError("option_ids must be 1-20 distinct positive ids", "option_ids")
        }
        val ip = call.request.local.remoteHost.take(45)
        val now = System.currentTimeMillis() / 1000L
        svc.db.transaction { c ->
            val vote = c.selectOne(
                "SELECT vote_id, start_time, end_time, can_multi FROM ecs_vote WHERE vote_id = ?",
                listOf(voteId),
            ) { rs -> Triple(rs.long("start_time"), rs.long("end_time"), rs.int("can_multi")) } ?: throw notFound("vote not found")
            if (vote.first > 0 && vote.first > now) throw invalidState("vote has not started")
            if (vote.second > 0 && vote.second < now) throw conflict("vote has ended")
            if (vote.third == 0 && options.size != 1) throw validationError("this vote accepts exactly one option", "option_ids")
            val placeholders = options.joinToString(",") { "?" }
            val matched = c.selectOne(
                "SELECT COUNT(*) AS found FROM ecs_vote_option WHERE vote_id = ? AND option_id IN ($placeholders)",
                listOf(voteId) + options,
            ) { it.long("found") } ?: 0L
            if (matched != options.size.toLong()) throw validationError("one or more options do not belong to this vote", "option_ids")
            try {
                c.execute(
                    "INSERT INTO ecs_vote_log(vote_id, ip_address) VALUES(?, ?)",
                    listOf(voteId, ip),
                )
            } catch (e: org.sqlite.SQLiteException) {
                if (e.message?.contains("UNIQUE") == true) throw conflict("this address already voted")
                throw e
            }
            c.execute("UPDATE ecs_vote SET vote_count = vote_count + 1 WHERE vote_id = ?", listOf(voteId))
            for (optionId in options) {
                c.execute("UPDATE ecs_vote_option SET option_count = option_count + 1 WHERE option_id = ? AND vote_id = ?", listOf(optionId, voteId))
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
