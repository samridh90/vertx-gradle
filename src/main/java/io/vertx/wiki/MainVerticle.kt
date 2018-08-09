package io.vertx.wiki

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.wiki.database.WikiDatabaseVerticle

class MainVerticle : AbstractVerticle() {
  override fun start(startFuture: Future<Void>) {

    val dbVerticleDeployment = Future.future<String>()
    vertx.deployVerticle(WikiDatabaseVerticle(), dbVerticleDeployment.completer())

    dbVerticleDeployment.compose { _ ->
      val httpVerticleDeployment = Future.future<String>()
      vertx.deployVerticle("io.vertx.wiki.http.HttpServerVerticle",
        DeploymentOptions(instances = 2),
        httpVerticleDeployment.completer())
      httpVerticleDeployment
    }.setHandler { ar ->
      if (ar.succeeded()) {
        startFuture.complete()
      } else {
        startFuture.fail(ar.cause())
      }
    }
  }
}
