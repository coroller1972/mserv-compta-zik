package fr.spiritbox.compta.resources

import fr.spiritbox.compta.api.model.ErrorResponseDto
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response

internal fun notImplemented(): Uni<Response> =
    Uni.createFrom().item(
        Response.status(Response.Status.NOT_IMPLEMENTED)
            .entity(ErrorResponseDto("Endpoint contract defined; implementation pending."))
            .build(),
    )
