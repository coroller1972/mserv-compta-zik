package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.BandEntity
import fr.spiritbox.compta.domain.BandMemberEntity
import fr.spiritbox.compta.domain.BandType
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.openapi.model.Band
import fr.spiritbox.compta.openapi.model.BandMembers
import fr.spiritbox.compta.repositories.BandMemberRepository
import fr.spiritbox.compta.repositories.BandRepository
import fr.spiritbox.compta.repositories.MusicianRepository
import fr.spiritbox.compta.repositories.TeacherRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.util.*

@ApplicationScoped
class BandService(
    private val bandRepository: BandRepository,
    private val bandMemberRepository: BandMemberRepository,
    private val teacherRepository: TeacherRepository,
    private val musicianRepository: MusicianRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper
) {

    suspend fun bands(): List<Band> = panacheCoroutine.withSession {
        val bands = bandRepository.listWithMembers().awaitSuspending()

        bands.map { mapper.run { it.toDto() } }
    }

    suspend fun createBand(band: Band): Band = panacheCoroutine.withTransaction {
        if (band.id != null) {
            throw BadRequestException("Band id must not be provided when creating a band.")
        }

        val entity = BandEntity().apply {
            name = band.name
            type = BandType.valueOf(band.type.name)
            teacher = band.teacherId?.let { getTeacher(it) }
            weekday = band.weekday
        }
        bandRepository.persist(entity).awaitSuspending()
        replaceMembers(entity, band.memberIds)

        mapper.run { getBand(requireNotNull(entity.id)).toDto() }
    }

    suspend fun deleteBand(id: UUID): Unit = panacheCoroutine.withTransaction {
        val deleted = bandRepository.delete("id", id).awaitSuspending()
        if (deleted == 0L) {
            throw NotFoundException("Band $id was not found.")
        }
    }

    suspend fun replaceBandMembers(id: UUID, bandMembers: BandMembers): Band = panacheCoroutine.withTransaction {
        val entity = getBandForUpdate(id)
        replaceMembers(entity, bandMembers.memberIds)

        mapper.run { getBand(id).toDto() }
    }

    suspend fun updateBand(id: UUID, band: Band): Band = panacheCoroutine.withTransaction {
        if (band.id != null && band.id != id) {
            throw BadRequestException("Path band id $id does not match payload band id ${band.id}.")
        }

        val entity = getBandForUpdate(id)
        entity.name = band.name
        entity.type = BandType.valueOf(band.type.name)
        entity.teacher = band.teacherId?.let { getTeacher(it) }
        entity.weekday = band.weekday
        replaceMembers(entity, band.memberIds)

        mapper.run { getBand(id).toDto() }
    }

    private suspend fun getTeacher(id: UUID): TeacherEntity =
        teacherRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Teacher $id was not found.")

    private suspend fun getBand(id: UUID): BandEntity =
        bandRepository.findByIdWithMembers(id).awaitSuspending()
            ?: throw NotFoundException("Band $id was not found.")

    private suspend fun getBandForUpdate(id: UUID): BandEntity =
        bandRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Band $id was not found.")

    private suspend fun replaceMembers(band: BandEntity, memberIds: List<UUID>) {
        val musicians = getMusicians(memberIds.distinct())
        val bandId = requireNotNull(band.id) { "Cannot replace members before band is persisted." }
        bandMemberRepository.delete("band.id", bandId).awaitSuspending()

        musicians.forEach { musician ->
            val member = BandMemberEntity().apply {
                this.band = band
                this.musician = musician
            }
            bandMemberRepository.persist(member).awaitSuspending()
        }
    }

    private suspend fun getMusicians(ids: List<UUID>): List<MusicianEntity> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        val musicians = musicianRepository.find("id in ?1", ids).list().awaitSuspending()
        val foundIds = musicians.mapNotNull { it.id }.toSet()
        val missingIds = ids.filterNot { it in foundIds }
        if (missingIds.isNotEmpty()) {
            throw NotFoundException("Musicians were not found: ${missingIds.joinToString()}.")
        }

        return ids.map { id -> musicians.first { it.id == id } }
    }
}
