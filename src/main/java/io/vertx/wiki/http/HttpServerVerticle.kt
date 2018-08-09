package io.vertx.wiki.http

import com.github.rjeschke.txtmark.Processor
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine
import io.vertx.kotlin.ext.web.client.WebClientOptions
import io.vertx.wiki.database.WikiDatabaseService
import io.vertx.wiki.database.WikiDatabaseServiceFactory
import java.util.Date

class HttpServerVerticle : AbstractVerticle() {
  private companion object {
    val LOGGER = LoggerFactory.getLogger(HttpServerVerticle::class.java)

    val TEMPLATE_ENGINE = FreeMarkerTemplateEngine.create()

    const val EMPTY_PAGE_MD = "A new page\n\nEdit in markdown!\n"

    const val CONFIG_HTTP_SERVER_PORT = "http.server.port"
    const val CONFIG_WIKIDB_QUEUE = "wikidb.queue"
  }

  private lateinit var dbService: WikiDatabaseService
  private lateinit var webClient: WebClient

  override fun start(startFuture: Future<Void>) {
    val wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue")
    dbService = WikiDatabaseServiceFactory.createProxy(vertx, wikiDbQueue)
    webClient = WebClient.create(vertx, WebClientOptions(ssl = true, userAgent = "vert-x3"))

    val server = vertx.createHttpServer()

    val router = Router.router(vertx)
    with(router) {
      get("/").handler(::indexHandler)
      get("/wiki/:page").handler(::pageRenderingHandler)
      get("/backup").handler(::backupHandler)
      post().handler(BodyHandler.create())
      post("/save").handler(::pageUpdateHandler)
      post("/create").handler(::pageCreateHandler)
      post("/delete").handler(::pageDeleteHandler)
    }
    val apiRouter = Router.router(vertx)
    with(apiRouter) {
      get("/pages").handler(::apiRoot)
      /*get("/pages/:id").handler(::apiGetPage)
      post().handler(BodyHandler.create())
      post("/pages").handler(::apiCreatePage)
      put().handler(BodyHandler.create())
      put("/pages/:id").handler(::apiUpdatePage)
      delete("/pages/:id").handler(::apiDeletePage)*/
    }
    router.mountSubRouter("/api", apiRouter)

    val portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080)
    server
      .requestHandler(router::accept)
      .listen(portNumber) { ar ->
        if (ar.succeeded()) {
          LOGGER.info("HTTP server running on port 8080")
          startFuture.complete()
        } else {
          LOGGER.error("Could not start HTTP server", ar.cause())
          startFuture.fail(ar.cause())
        }
      }
  }

  private fun apiRoot(context: RoutingContext) {
    dbService.fetchAllPagesData(Handler { reply ->
      val response = JsonObject()
      if (reply.succeeded()) {
        val pages = reply.result()
          .map { JsonObject().put("id", it.getInteger("ID")).put("name", it.getString("NAME")) }
        response.put("success", true).put("pages", pages)
        context.response().run {
          statusCode = 200
          putHeader("Content-Type", "application/json")
          end(response.encode())
        }
      } else {
        response.put("success", false).put("error", reply.cause().message)
        context.response().run {
          statusCode = 500
          putHeader("Content-Type", "application/json")
          end(response.encode())
        }
      }
    })
  }

  private fun pageDeleteHandler(context: RoutingContext) {
    dbService.deletePage(Integer.valueOf(context.request().getParam("id")), Handler { reply: AsyncResult<Void> ->
      if (reply.succeeded()) {
        context.response().statusCode = 303
        context.response().putHeader("Location", "/")
        context.response().end()
      } else {
        context.fail(reply.cause())
      }
    })
  }

  private fun pageCreateHandler(context: RoutingContext) {
    val pageName = context.request().getParam("name")
    var location = "/wiki/$pageName"
    if (pageName == null || pageName.isEmpty()) {
      location = "/"
    }
    context.response().statusCode = 303
    context.response().putHeader("Location", location)
    context.response().end()
  }

  private fun pageUpdateHandler(context: RoutingContext) {
    val title = context.request().getParam("title")

    val handler = Handler { reply: AsyncResult<Void> ->
      if (reply.succeeded()) {
        context.response().statusCode = 303
        context.response().putHeader("Location", "/wiki/$title")
        context.response().end()
      } else {
        context.fail(reply.cause())
      }
    }

    val markdown = context.request().getParam("markdown")
    if ("yes".equals(context.request().getParam("newPage"))) {
      dbService.createPage(title, markdown, handler)
    } else {
      dbService.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler)
    }
  }

  private fun pageRenderingHandler(context: RoutingContext) {
    val requestedPage = context.request().getParam("page")

    dbService.fetchPage(requestedPage, Handler { reply: AsyncResult<JsonObject> ->
      if (reply.succeeded()) {
        val body = reply.result()

        val found = body.getBoolean("found")
        val rawContent = body.getString("rawContent", EMPTY_PAGE_MD)
        context.put("title", requestedPage)
        context.put("id", body.getInteger("id", -1))
        context.put("newPage", if (!found) "yes" else "no")
        context.put("rawContent", rawContent)
        context.put("content", Processor.process(rawContent))
        context.put("timestamp", Date().toString())

        TEMPLATE_ENGINE.render(context, "templates", "/page.ftl") { ar ->
          if (ar.succeeded()) {
            context.response().putHeader("Content-Type", "text/html")
            context.response().end(ar.result())
          } else {
            context.fail(ar.cause())
          }
        }
      } else {
        context.fail(reply.cause())
      }
    })
  }

  private fun indexHandler(context: RoutingContext) {
    dbService.fetchAllPages(Handler { reply: AsyncResult<JsonArray> ->
      if (reply.succeeded()) {
        context.put("title", "Wiki Home")
        context.put("pages", reply.result().list)
        TEMPLATE_ENGINE.render(context, "templates", "/index.ftl") { ar ->
          if (ar.succeeded()) {
            context.response().putHeader("Content-Type", "text/html")
            context.response().end(ar.result())
          } else {
            context.fail(ar.cause())
          }
        }
      } else {
        context.fail(reply.cause())
      }
    })
  }

  private fun backupHandler(context: RoutingContext) {
    dbService.fetchAllPagesData(Handler { reply: AsyncResult<List<JsonObject>> ->
      if (reply.succeeded()) {
        val filesObject = JsonArray()
        val payload = JsonObject()
          .put("files", filesObject)
          .put("language", "plaintext")
          .put("title", "vertx-wiki-backup")
          .put("public", true)

        reply.result()
          .forEach { page ->
            filesObject.add(
              JsonObject()
                .put("name", page.getString("NAME"))
                .put("content", page.getString("CONTENT"))
            )
          }
        webClient.post(443, "snippets.glot.io", "/snippets")
          .putHeader("Content-Type", "application/json")
          .`as`(BodyCodec.jsonObject())
          .sendJsonObject(payload) { ar ->
            if (ar.succeeded()) {
              val response = ar.result()
              if (response.statusCode() == 200) {
                val url = "https://glot.io/snippets/${response.body().getString("id")}"
                context.put("backup_gist_url", url)
                indexHandler(context)
              } else {
                val body: JsonObject? = response.body()
                val message = "Could not backup wiki: ${response.statusMessage()}\n${body?.encodePrettily()}"
                LOGGER.error(message)
                context.fail(502)
              }
            } else {
              LOGGER.error("HTTP Client error", ar.cause())
              context.fail(ar.cause())
            }
          }
      } else {
        context.fail(reply.cause())
      }
    })
  }
}
