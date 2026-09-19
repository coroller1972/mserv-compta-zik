package fr.spiritbox.compta.security

import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/** Uses the identity service's existing session gate, including password and MFA requirements. */
@Path("/")
@RegisterRestClient(configKey = "identity")
interface IdentitySessionClient {
    @GET
    @Path("/session")
    @Produces(MediaType.APPLICATION_JSON)
    fun session(@HeaderParam("Authorization") authorization: String): Uni<Response>

    @GET
    @Path("/.well-known/jwks.json")
    @Produces(MediaType.APPLICATION_JSON)
    fun publicKeys(): Uni<Response>
}
