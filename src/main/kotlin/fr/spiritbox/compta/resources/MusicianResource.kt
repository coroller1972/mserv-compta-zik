package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.MusiciansApi
import fr.spiritbox.compta.openapi.model.Musician
import fr.spiritbox.compta.services.MusicianService
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@Tag(name = "Musicians")
class MusicianResource(
    private val musicianService: MusicianService,
) : MusiciansApi {
    override suspend fun createMusician(musician: Musician): Response =
        Response.status(Response.Status.CREATED)
            .entity(musicianService.createMusician(musician))
            .build()

    override suspend fun deleteMusician(id: UUID): Response =
        musicianService.deleteMusician(id).let { Response.noContent().build() }

    override suspend fun listMusicians(): Response =
        Response.ok(musicianService.listMusicians()).build()

    override suspend fun updateMusician(
        id: UUID,
        musician: Musician
    ): Response =
        Response.ok(musicianService.updateMusician(id, musician)).build()

}
