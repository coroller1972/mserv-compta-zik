package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.DataTransferApi
import fr.spiritbox.compta.openapi.model.DataTransfer
import fr.spiritbox.compta.services.DataTransferService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@ApplicationScoped
@Path("/data")
@Tag(name = "Data transfer")
class DataTransferResource(
    private val dataTransferService: DataTransferService,
) : DataTransferApi {
    @GET
    @Path("/export")
    @Produces("application/json")
    override suspend fun exportData(): Response =
        Response.ok(dataTransferService.exportData()).build()

    @POST
    @Path("/import")
    @Consumes("application/json")
    @Produces("application/json")
    override suspend fun importData(
        @HeaderParam("X-Import-Confirmation") xImportConfirmation: String,
        dataTransfer: DataTransfer,
    ): Response =
        Response.ok(dataTransferService.importData(dataTransfer, xImportConfirmation)).build()

    override suspend fun analyzeImport(dataTransfer: DataTransfer): Response =
        Response.ok(dataTransferService.analyzeImport(dataTransfer)).build()
}
