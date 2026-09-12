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

    suspend fun <T> withTransaction(block: suspend () -> T): T = transaction(false, block)

    suspend fun <T> withExclusiveTransaction(block: suspend () -> T): T = transaction(true, block)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> transaction(exclusive: Boolean, block: suspend () -> T): T =
        Panache.withTransaction {
            coroutineScope.async(vertx.dispatcher()) {
                // All application writes participate, including PDF generation. The lock is
                // released by PostgreSQL on commit/rollback, also across service instances.
                val lock = if (exclusive) "pg_advisory_xact_lock" else "pg_advisory_xact_lock_shared"
                Panache.getSession().awaitSuspending()
                    .createNativeQuery("select 1 from $lock(721640982173)", Int::class.javaObjectType)
                    .singleResult.awaitSuspending()
                block()
            }.asUni()
        }.awaitSuspending()
}
