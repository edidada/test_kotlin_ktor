package ecshop.routes

import ecshop.AppServices
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/votes/current (maps PHP vote.php display branch).
 * The oldest poll still inside its time window, with option counts.
 */
fun Route.voteCurrentRoutes(svc: AppServices) {
    get("/votes/current") {
        val now = System.currentTimeMillis() / 1000L
        val body = svc.db.read { c ->
            val vote = c.selectOne(
                "SELECT vote_id, vote_name, start_time, end_time, can_multi, vote_count FROM ecs_vote " +
                    "WHERE start_time <= ? AND end_time >= ? ORDER BY vote_id LIMIT 1",
                listOf(now, now),
            ) { rs ->
                val voteId = rs.long("vote_id")
                val json = buildJsonObject {
                    put("vote_id", voteId)
                    put("vote_name", rs.str("vote_name"))
                    put("start_time", rs.long("start_time"))
                    put("end_time", rs.long("end_time"))
                    put("can_multi", rs.int("can_multi") == 1)
                    put("vote_count", rs.long("vote_count"))
                }
                voteId to json
            } ?: throw notFound("no current vote")
            val options = JsonArray(
                c.select(
                    "SELECT option_id, option_name, option_count, option_order FROM ecs_vote_option " +
                        "WHERE vote_id = ? ORDER BY option_order, option_id",
                    listOf(vote.first),
                ) { rs ->
                    buildJsonObject {
                        put("option_id", rs.long("option_id"))
                        put("option_name", rs.str("option_name"))
                        put("option_count", rs.long("option_count"))
                    }
                },
            )
            buildJsonObject {
                put("vote", vote.second)
                put("options", options)
            }
        }
        call.respond(body)
    }
}
