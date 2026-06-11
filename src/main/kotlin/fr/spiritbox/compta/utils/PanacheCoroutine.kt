package fr.spiritbox.compta.utils

import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.asUni
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.jboss.resteasy.reactive.server.runtime.kotlin.ApplicationCoroutineScope

@ApplicationScoped
class PanacheCoroutine(
    private val coroutineScope: ApplicationCoroutineScope,
    private val vertx: Vertx,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> withSession(block: suspend () -> T): T =
        Panache.withSession {
            coroutineScope.async(vertx.dispatcher()) {
                block()
            }.asUni()
        }.awaitSuspending()

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> withTransaction(block: suspend () -> T): T =
        Panache.withTransaction {
            coroutineScope.async(vertx.dispatcher()) {
                block()
            }.asUni()
        }.awaitSuspending()
}
