package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.AuthApi
import fr.spiritbox.compta.openapi.model.CurrentUser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@ApplicationScoped
@Tag(name = "Auth")
class AuthResource : AuthApi {
    @Context
    lateinit var headers: HttpHeaders

    override suspend fun getCurrentUser(): Response {
        val username = header("Remote-User")
        val displayName = header("Remote-Name")
        val email = header("Remote-Email")
        val groups = header("Remote-Groups")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        return Response.ok(
            CurrentUser(
                authenticated = !username.isNullOrBlank(),
                username = username,
                displayName = displayName,
                email = email,
                groups = groups,
            ),
        ).build()
    }

    private fun header(name: String): String? =
        headers.getHeaderString(name)?.trim()?.takeIf { it.isNotBlank() }
}
