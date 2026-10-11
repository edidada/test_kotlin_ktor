package ecshop.routes

import ecshop.AppServices
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.parsePaging
import ecshop.http.pathLong
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M1 — GET /api/v1/articles/{id} (maps PHP article.php). Open articles only; closed or
 * missing articles are 404.
 */
fun Route.articleDetailRoutes(svc: AppServices) {
    get("/articles/{id}") {
        val articleId = call.pathLong("id")
        val body = svc.db.read { c ->
            c.selectOne(
                "SELECT a.article_id, a.cat_id, ac.cat_name, a.title, a.author, a.article_desc, " +
                    "a.content, a.keywords FROM ecs_article a " +
                    "LEFT JOIN ecs_article_cat ac ON ac.cat_id = a.cat_id " +
                    "WHERE a.article_id = ? AND a.is_open = 1",
                listOf(articleId),
            ) { rs ->
                buildJsonObject {
                    put("article_id", rs.long("article_id"))
                    put("cat_id", rs.long("cat_id"))
                    put("cat_name", rs.str("cat_name"))
                    put("title", rs.str("title"))
                    put("author", rs.str("author"))
                    put("intro", rs.str("article_desc"))
                    put("content", rs.str("content"))
                    put("keywords", rs.str("keywords"))
                }
            } ?: throw notFound("article not found")
        }
        call.respond(body)
    }
}

/**
 * M1 — GET /api/v1/article-categories/{id}/articles (maps PHP article_cat.php).
 * Paged open articles of a visible article category.
 */
fun Route.articleCategoryRoutes(svc: AppServices) {
    get("/article-categories/{id}/articles") {
        val catId = call.pathLong("id")
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val catName = c.selectOne(
                "SELECT cat_name FROM ecs_article_cat WHERE cat_id = ? AND is_show = 1",
                listOf(catId),
            ) { it.str("cat_name") } ?: throw notFound("article category not found")
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_article WHERE cat_id = ? AND is_open = 1",
                listOf(catId),
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT article_id, title, author, article_desc, keywords FROM ecs_article " +
                        "WHERE cat_id = ? AND is_open = 1 ORDER BY article_id DESC LIMIT ? OFFSET ?",
                    listOf(catId, paging.limit, paging.offset),
                ) { rs ->
                    buildJsonObject {
                        put("article_id", rs.long("article_id"))
                        put("title", rs.str("title"))
                        put("author", rs.str("author"))
                        put("intro", rs.str("article_desc"))
                        put("keywords", rs.str("keywords"))
                    }
                },
            )
            buildJsonObject {
                put("cat_id", catId)
                put("cat_name", catName)
                put("page", paging.page)
                put("page_size", paging.pageSize)
                put("total", total)
                put("items", items)
            }
        }
        call.respond(body)
    }
}
