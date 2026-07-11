package fr.spiritbox.compta.api

import jakarta.annotation.Priority
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.util.UUID

@Provider
@Priority(1)
class ProblemExceptionMapper : ExceptionMapper<WebApplicationException> {
    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response.status
        val code = when (status) {
            400 -> "VALIDATION_ERROR"
            401 -> "UNAUTHORIZED"
            403 -> "FORBIDDEN"
            404 -> "NOT_FOUND"
            409 -> "CONFLICT"
            422 -> "UNPROCESSABLE_ENTITY"
            else -> "REQUEST_FAILED"
        }
        return Response.status(status)
            .type(PROBLEM_JSON)
            .entity(
                ProblemResponse(
                    type = "https://api.compta-zik.local/problems/${code.lowercase().replace('_', '-')}",
                    title = code,
                    status = status,
                    code = code,
                    detail = exception.message?.takeIf { it.isNotBlank() },
                    correlationId = UUID.randomUUID(),
                ),
            ).build()
    }

    companion object {
        private val PROBLEM_JSON = MediaType.valueOf("application/problem+json")
    }
}

data class ProblemResponse(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val detail: String?,
    val correlationId: UUID,
)
