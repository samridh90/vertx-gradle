package io.vertx.wiki

import io.vertx.core.Vertx
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(VertxUnitRunner::class)
class MainVerticleTest {

    private var vertx: Vertx? = null

    @Before
    fun setUp(tc: TestContext) {
        vertx = Vertx.vertx()
        vertx!!.deployVerticle(MainVerticle::class.java.name, tc.asyncAssertSuccess<String>())
    }

    @After
    fun tearDown(tc: TestContext) {
        vertx!!.close(tc.asyncAssertSuccess())
    }

    @Test
    fun testThatTheServerIsStarted(tc: TestContext) {
        val async = tc.async()
        vertx!!.createHttpClient().getNow(8080, "localhost", "/") { response ->
            tc.assertEquals(response.statusCode(), 200)
            response.bodyHandler { body ->
                tc.assertTrue(body.length() > 0)
                async.complete()
            }
        }
    }

}
