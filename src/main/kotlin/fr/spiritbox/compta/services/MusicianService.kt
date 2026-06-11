package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.openapi.model.Musician
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.repositories.MusicianRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.util.UUID

@ApplicationScoped
class MusicianService(
    private val musicianRepository: MusicianRepository,
    private val individualCourseRepository: IndividualCourseRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
) {
    suspend fun createMusician(musician: Musician): Musician = panacheCoroutine.withTransaction {
        if (musician.id != null) {
            throw BadRequestException("Musician id must not be provided when creating a musician.")
        }

        val entity = MusicianEntity()
        applyPayload(entity, musician)
        musicianRepository.persist(entity).awaitSuspending()

        mapper.run { entity.toDto() }
    }

    suspend fun deleteMusician(id: UUID): Unit = panacheCoroutine.withTransaction {
        val entity = getMusician(id)
        entity.active = false
        individualCourseRepository.listByMusician(id)
            .awaitSuspending()
            .forEach { course -> course.active = false }
    }

    suspend fun listMusicians(): List<Musician> = panacheCoroutine.withSession {
        musicianRepository.find("order by lastName, firstName").list()
            .awaitSuspending()
            .map { mapper.run { it.toDto() } }
    }

    suspend fun updateMusician(id: UUID, musician: Musician): Musician = panacheCoroutine.withTransaction {
        if (musician.id != null && musician.id != id) {
            throw BadRequestException("Path musician id $id does not match payload id ${musician.id}.")
        }

        val entity = getMusician(id)
        applyPayload(entity, musician)

        mapper.run { entity.toDto() }
    }

    private fun applyPayload(entity: MusicianEntity, musician: Musician) {
        entity.firstName = musician.firstName
        entity.lastName = musician.lastName
        entity.email = musician.email
        entity.active = musician.active
    }

    private suspend fun getMusician(id: UUID): MusicianEntity =
        musicianRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Musician $id was not found.")

}
