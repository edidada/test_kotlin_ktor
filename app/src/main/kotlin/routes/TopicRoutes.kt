package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.pathLong
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/topics/{id} (maps PHP topic.php?topic_id=).
 * Topics are only visible inside their time window. `data` is a JSON object mapping group
 * name -> goods ids; each group is re-filtered through the sellable-goods rule.
 */
fun Route.topicRoutes(svc: AppServices) {
    get("/topics/{id}") {
        val topicId = call.pathLong("id")
        val now = System.currentTimeMillis() / 1000L
        val body = svc.db.read { c ->
            val topic = c.selectOne(
                "SELECT topic_id, title, intro, start_time, end_time, data, css, topic_img, title_pic, " +
                    "base_style, keywords, description FROM ecs_topic " +
                    "WHERE topic_id = ? AND start_time <= ? AND end_time >= ?",
                listOf(topicId, now, now),
            ) { rs ->
                TopicRow(
                    rs.long("topic_id"),
                    rs.str("title"),
                    rs.str("intro"),
                    rs.long("start_time"),
                    rs.long("end_time"),
                    rs.str("data"),
                    rs.str("css"),
                    rs.str("topic_img"),
                    rs.str("title_pic"),
                    rs.str("base_style"),
                    rs.str("keywords"),
                    rs.str("description"),
                )
            } ?: throw notFound("topic not found")

            val groups = JsonArray(
                parseTopicGroups(topic.data).map { (groupName, goodsIds) ->
                    val goods = if (goodsIds.isEmpty()) JsonArray(emptyList()) else JsonArray(
                        c.select(
                            "$GOODS_LIST_SELECT FROM ecs_goods WHERE goods_id IN " +
                                "(${goodsIds.joinToString(",") { "?" }}) AND $GOODS_VISIBLE ORDER BY goods_id",
                            goodsIds,
                        ) { rs -> goodsListJson(rs) },
                    )
                    buildJsonObject {
                        put("name", groupName)
                        put("goods", goods)
                    }
                }.let { it.toList() },
            )
            buildJsonObject {
                put("topic_id", topic.topicId)
                put("title", topic.title)
                put("intro", topic.intro)
                put("start_time", topic.start)
                put("end_time", topic.end)
                put("css", topic.css)
                put("topic_img", topic.topicImg)
                put("title_pic", topic.titlePic)
                put("base_style", topic.baseStyle)
                put("keywords", topic.keywords)
                put("description", topic.description)
                put("groups", groups)
            }
        }
        call.respond(body)
    }
}

private data class TopicRow(
    val topicId: Long,
    val title: String,
    val intro: String,
    val start: Long,
    val end: Long,
    val data: String,
    val css: String,
    val topicImg: String,
    val titlePic: String,
    val baseStyle: String,
    val keywords: String,
    val description: String,
)

/** Keeps the JSON object's key order like PHP's json_decode assoc array. */
private fun parseTopicGroups(data: String): List<Pair<String, List<Long>>> {
    if (data.isBlank()) return emptyList()
    val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return emptyList()
    return obj.mapNotNull { (key, value) ->
        val ids = (value as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { id -> id > 0 } }
            ?: emptyList()
        key to ids
    }
}
