package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.IndividualCourseEntity
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.openapi.model.IndividualCourse
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.repositories.MusicianRepository
import fr.spiritbox.compta.repositories.TeacherRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.time.DateTimeException
import java.time.LocalTime
import java.util.*

@ApplicationScoped
class IndividualCourseService(
    private val individualCourseRepository: IndividualCourseRepository,
    private val musicianRepository: MusicianRepository,
    private val teacherRepository: TeacherRepository,
    private val mapper: Mapper,
    private val panacheCoroutine: PanacheCoroutine,
) {

    suspend fun createIndividualCourse(individualCourse: IndividualCourse): IndividualCourse = panacheCoroutine.withTransaction {
        if (individualCourse.id != null) {
            throw BadRequestException("Individual course id must not be provided when creating a course.")
        }

        val entity = IndividualCourseEntity()
        applyPayload(entity, individualCourse)
        individualCourseRepository.persist(entity).awaitSuspending()

        mapper.run { entity.toDto() }
    }

    suspend fun deleteIndividualCourse(id: UUID): Unit = panacheCoroutine.withTransaction {
        val deleted = individualCourseRepository.delete("id", id).awaitSuspending()
        if (deleted == 0L) {
            throw NotFoundException("Individual course $id was not found.")
        }
    }

    suspend fun listIndividualCourses(): List<IndividualCourse> = panacheCoroutine.withSession {
        val courses = individualCourseRepository.listWithRelations().awaitSuspending()

        courses.map { mapper.run { it.toDto() } }
    }

    suspend fun updateIndividualCourse(id: UUID, individualCourse: IndividualCourse): IndividualCourse = panacheCoroutine.withTransaction {
        if (individualCourse.id != null && individualCourse.id != id) {
            throw BadRequestException("Path individual course id $id does not match payload id ${individualCourse.id}.")
        }

        val entity = getIndividualCourse(id)
        applyPayload(entity, individualCourse)

        mapper.run { entity.toDto() }
    }

    private suspend fun applyPayload(entity: IndividualCourseEntity, individualCourse: IndividualCourse) {
        val startTime = parseStartTime(individualCourse.startTime)
        validateSlotAvailable(entity.id, individualCourse.weekday, startTime, individualCourse.sharedSlot)

        entity.musician = getMusician(individualCourse.musicianId)
        entity.teacher = getTeacher(individualCourse.teacherId)
        entity.instrument = individualCourse.instrument
        entity.weekday = individualCourse.weekday
        entity.startTime = startTime
        entity.sharedSlot = individualCourse.sharedSlot
        entity.active = individualCourse.active
    }

    private suspend fun validateSlotAvailable(courseId: UUID?, weekday: String, startTime: LocalTime, sharedSlot: Boolean) {
        val occupyingCourses = individualCourseRepository.listOccupyingBySlot(weekday, startTime).awaitSuspending()
            .filter { it.id != courseId }
        if (occupyingCourses.isNotEmpty() && !sharedSlot) {
            throw BadRequestException("Slot $weekday $startTime is already occupied.")
        }
    }

    private suspend fun getIndividualCourse(id: UUID): IndividualCourseEntity =
        individualCourseRepository.findByIdWithRelations(id).awaitSuspending()
            ?: throw NotFoundException("Individual course $id was not found.")

    private suspend fun getMusician(id: UUID): MusicianEntity =
        musicianRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Musician $id was not found.")

    private suspend fun getTeacher(id: UUID): TeacherEntity =
        teacherRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Teacher $id was not found.")

    private fun parseStartTime(startTime: String): LocalTime =
        try {
            LocalTime.parse(startTime)
        } catch (exception: DateTimeException) {
            throw BadRequestException("startTime must use ISO local time format, for example 11:30.", exception)
        }

}
