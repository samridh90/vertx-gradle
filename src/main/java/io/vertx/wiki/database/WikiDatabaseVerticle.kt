package io.vertx.wiki.database

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.serviceproxy.ServiceBinder
import java.io.FileInputStream
import java.io.InputStream
import java.util.Properties
import kotlin.collections.HashMap


class WikiDatabaseVerticle : AbstractVerticle() {
  private companion object {
    const val CONFIG_WIKIDB_JDBC_URL = "wikidb.jdbc.url"
    const val CONFIG_WIKIDB_JDBC_DRIVER_CLASS = "wikidb.jdbc.driver_class"
    const val CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE = "wikidb.jdbc.max_pool_size"
    const val CONFIG_WIKDB_SQL_QUERIES_RESOURCE_FILE = "wikdb.sqlqueries.resource.file"

    const val CONFIG_WIKIDB_QUEUE = "wikidb.queue"
  }

  override fun start(startFuture: Future<Void>) {
    val sqlQueries = loadQueries()
    val dbClient = JDBCClient.createShared(vertx, JsonObject()
      .put("url", config().getString(CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:file:db/wiki"))
      .put("driver_class", config().getString(CONFIG_WIKIDB_JDBC_DRIVER_CLASS, "org.hsqldb.jdbcDriver"))
      .put("max_pool_size", config().getInteger(CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 30)))

    WikiDatabaseServiceFactory.create(dbClient, sqlQueries, Handler { event ->
      if (event.succeeded()) {
        val binder = ServiceBinder(vertx)
        binder
          .setAddress(CONFIG_WIKIDB_QUEUE)
          .register(WikiDatabaseService::class.java, event.result())
        startFuture.complete()
      } else {
        startFuture.fail(event.cause())
      }
    })
  }

  private fun loadQueries(): HashMap<SqlQuery, String> {
    val queriesFile = config().getString(CONFIG_WIKDB_SQL_QUERIES_RESOURCE_FILE)
    val inputStream: InputStream
    if (queriesFile != null) {
      inputStream = FileInputStream(queriesFile)
    } else {
      inputStream = this::class.java.getResourceAsStream("/db-queries.properties")
    }
    val queriesProps = Properties()
    queriesProps.load(inputStream)
    inputStream.close()

    val sqlQueries = HashMap<SqlQuery, String>()
    sqlQueries.put(SqlQuery.CREATE_PAGES_TABLE, queriesProps.getProperty("create-pages-table"))
    sqlQueries.put(SqlQuery.ALL_PAGES, queriesProps.getProperty("all-pages"))
    sqlQueries.put(SqlQuery.ALL_PAGES_DATA, queriesProps.getProperty("all-pages-data"))
    sqlQueries.put(SqlQuery.GET_PAGE, queriesProps.getProperty("get-page"))
    sqlQueries.put(SqlQuery.CREATE_PAGE, queriesProps.getProperty("create-page"))
    sqlQueries.put(SqlQuery.SAVE_PAGE, queriesProps.getProperty("save-page"))
    sqlQueries.put(SqlQuery.DELETE_PAGE, queriesProps.getProperty("delete-page"))
    return sqlQueries
  }
}
