package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.DataTransferApi
import fr.spiritbox.compta.openapi.model.DataTransfer
import fr.spiritbox.compta.services.DataTransferService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@ApplicationScoped
@Tag(name = "Data transfer")
class DataTransferResource(
    private val dataTransferService: DataTransferService,
) : DataTransferApi {
    override suspend fun exportData(): Response =
        Response.ok(dataTransferService.exportData()).build()

    override suspend fun importData(dataTransfer: DataTransfer): Response =
        Response.ok(dataTransferService.importData(dataTransfer)).build()
}
