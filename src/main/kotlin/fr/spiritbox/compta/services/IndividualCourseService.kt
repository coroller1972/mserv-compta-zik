package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.IndividualCourseEntity
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.openapi.model.IndividualCourse
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.repositories.AccountingYearSettingsRepository
import fr.spiritbox.compta.repositories.MusicianRepository
import fr.spiritbox.compta.repositories.TeacherRepository
import fr.spiritbox.compta.repositories.TeacherInvoiceAttendanceAllocationRepository
import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response
import java.time.DateTimeException
import java.time.LocalTime
import java.util.*

@ApplicationScoped
class IndividualCourseService(
    private val individualCourseRepository: IndividualCourseRepository,
    private val musicianRepository: MusicianRepository,
    private val teacherRepository: TeacherRepository,
    private val accountingYearSettingsRepository: AccountingYearSettingsRepository,
    private val allocationRepository: TeacherInvoiceAttendanceAllocationRepository,
    private val lifecycleService: AccountingYearLifecycleService,
    private val scheduleService: ScheduleService,
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
        val entity = getIndividualCourse(id)
        lifecycleService.requireStructureMutable(entity.settings)
        requireNotAllocated(id)
        val deleted = individualCourseRepository.delete("id", id).awaitSuspending()
        if (deleted == 0L) {
            throw NotFoundException("Individual course $id was not found.")
        }
    }

    suspend fun listIndividualCourses(year: Int): List<IndividualCourse> = panacheCoroutine.withSession {
        val courses = individualCourseRepository.listWithRelationsByYear(year).awaitSuspending()

        courses.map { mapper.run { it.toDto() } }
    }

    suspend fun updateIndividualCourse(id: UUID, individualCourse: IndividualCourse): IndividualCourse = panacheCoroutine.withTransaction {
        if (individualCourse.id != null && individualCourse.id != id) {
            throw BadRequestException("Path individual course id $id does not match payload id ${individualCourse.id}.")
        }

        val entity = getIndividualCourse(id)
        if (entity.teacher.id != individualCourse.teacherId || entity.musician.id != individualCourse.musicianId) requireNotAllocated(id)
        applyPayload(entity, individualCourse)

        mapper.run { entity.toDto() }
    }

    private suspend fun applyPayload(entity: IndividualCourseEntity, individualCourse: IndividualCourse) {
        val year = individualCourse.year
            ?: throw BadRequestException("Individual course year is required.")
        val startTime = parseStartTime(individualCourse.startTime)

        if (entity.id == null) {
            entity.settings = getSettings(year).also(lifecycleService::requireStructureMutable)
        } else if (entity.settings.year != year) {
            throw BadRequestException(
                "Individual course year ${entity.settings.year} cannot be changed to $year.",
            )
        } else {
            lifecycleService.requireStructureMutable(entity.settings)
        }

        val musician = getMusician(individualCourse.musicianId)
        // Seuls les cours actifs d'un élève actif occupent la salle.
        if (individualCourse.active && musician.active) {
            scheduleService.requireAvailable(
                entity.settings,
                scheduleService.courseOccupation(
                    entity.settings,
                    entity.id,
                    "${musician.firstName} ${musician.lastName}".trim(),
                    individualCourse.weekday,
                    startTime,
                    individualCourse.sharedSlot,
                ),
            )
        }

        entity.musician = musician
        entity.teacher = getTeacher(individualCourse.teacherId)
        entity.instrument = individualCourse.instrument
        entity.weekday = individualCourse.weekday
        entity.startTime = startTime
        entity.sharedSlot = individualCourse.sharedSlot
        entity.active = individualCourse.active
    }


    private suspend fun getIndividualCourse(id: UUID): IndividualCourseEntity =
        individualCourseRepository.findByIdWithRelations(id).awaitSuspending()
            ?: throw NotFoundException("Individual course $id was not found.")

    private suspend fun requireNotAllocated(id: UUID) {
        if (allocationRepository.countActiveByTarget(AttendanceEntityType.INDIVIDUAL_COURSE, id).awaitSuspending() > 0) {
            throw ClientErrorException("A course with paid attendance cannot be deleted or reassigned.", Response.Status.CONFLICT)
        }
    }

    private suspend fun getMusician(id: UUID): MusicianEntity =
        musicianRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Musician $id was not found.")

    private suspend fun getTeacher(id: UUID): TeacherEntity =
        teacherRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Teacher $id was not found.")

    private suspend fun getSettings(year: Int) = accountingYearSettingsRepository.findByYearWithTerms(year)
        .awaitSuspending()
        ?: throw NotFoundException("Accounting year $year was not found.")

    private fun parseStartTime(startTime: String): LocalTime =
        try {
            LocalTime.parse(startTime)
        } catch (exception: DateTimeException) {
            throw BadRequestException("startTime must use ISO local time format, for example 11:30.", exception)
        }

}
