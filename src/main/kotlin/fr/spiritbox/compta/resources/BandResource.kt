package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.BandsApi
import fr.spiritbox.compta.openapi.model.Band
import fr.spiritbox.compta.openapi.model.BandMembers
import fr.spiritbox.compta.services.BandService
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@Tag(name = "Bands")
class BandResource(
    private val bandService: BandService,
) : BandsApi {
    override suspend fun createBand(band: Band): Response =
        Response.status(Response.Status.CREATED).entity(bandService.createBand(band)).build()

    override suspend fun deleteBand(id: UUID): Response =
        bandService.deleteBand(id).let { Response.noContent().build() }

    override suspend fun listBands(): Response = Response.ok(bandService.bands()).build()

    override suspend fun replaceBandMembers(
        id: UUID,
        bandMembers: BandMembers
    ): Response = Response.ok(bandService.replaceBandMembers(id, bandMembers)).build()

    override suspend fun updateBand(
        id: UUID,
        band: Band
    ): Response = Response.ok(bandService.updateBand(id, band)).build()

}
