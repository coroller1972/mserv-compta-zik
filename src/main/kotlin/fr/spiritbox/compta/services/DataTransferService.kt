package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.AttendanceEntity
import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.domain.BandEntity
import fr.spiritbox.compta.domain.BandMemberEntity
import fr.spiritbox.compta.domain.BandType
import fr.spiritbox.compta.domain.ExpenseCategory
import fr.spiritbox.compta.domain.ExpenseEntity
import fr.spiritbox.compta.domain.IndividualCourseEntity
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.DataImportResult
import fr.spiritbox.compta.openapi.model.DataTransfer
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.repositories.AccountingYearSettingsRepository
import fr.spiritbox.compta.repositories.AttendanceRepository
import fr.spiritbox.compta.repositories.BandMemberRepository
import fr.spiritbox.compta.repositories.BandRepository
import fr.spiritbox.compta.repositories.ExpenseRepository
import fr.spiritbox.compta.repositories.GeneratedDocumentRepository
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.repositories.MusicianRepository
import fr.spiritbox.compta.repositories.TeacherRepository
import fr.spiritbox.compta.repositories.TermRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ApplicationScoped
class DataTransferService(
    private val accountingYearSettingsRepository: AccountingYearSettingsRepository,
    private val termRepository: TermRepository,
    private val teacherRepository: TeacherRepository,
    private val musicianRepository: MusicianRepository,
    private val bandRepository: BandRepository,
    private val bandMemberRepository: BandMemberRepository,
    private val individualCourseRepository: IndividualCourseRepository,
    private val attendanceRepository: AttendanceRepository,
    private val expenseRepository: ExpenseRepository,
    private val generatedDocumentRepository: GeneratedDocumentRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
) {
    suspend fun exportData(): DataTransfer = panacheCoroutine.withSession {
        DataTransfer(
            formatVersion = FORMAT_VERSION,
            exportedAt = OffsetDateTime.now(ZoneOffset.UTC),
            settings = accountingYearSettingsRepository.listWithTerms().awaitSuspending()
                .map { mapper.run { it.toDto() } }
                .sortedBy { it.year },
            teachers = teacherRepository.findAll().list().awaitSuspending()
                .map { mapper.run { it.toDto() } },
            musicians = musicianRepository.findAll().list().awaitSuspending()
                .map { mapper.run { it.toDto() } },
            bands = bandRepository.listWithMembers().awaitSuspending()
                .map { mapper.run { it.toDto() } },
            individualCourses = individualCourseRepository.listWithRelations().awaitSuspending()
                .map { mapper.run { it.toDto() } },
            attendance = attendanceRepository.listWithTerms().awaitSuspending()
                .map { mapper.run { it.toDto() } },
            expenses = expenseRepository.findAll().list().awaitSuspending()
                .map { mapper.run { it.toDto() } },
        )
    }

    suspend fun importData(dataTransfer: DataTransfer): DataImportResult = panacheCoroutine.withTransaction {
        if (dataTransfer.formatVersion != FORMAT_VERSION) {
            throw BadRequestException("Unsupported data transfer format version ${dataTransfer.formatVersion}.")
        }

        clearBusinessData()

        val termsById = importSettings(dataTransfer.settings)
        val teachersById = importTeachers(dataTransfer.teachers)
        val musiciansById = importMusicians(dataTransfer.musicians)
        val bandsById = importBands(dataTransfer.bands, teachersById, musiciansById)
        val individualCoursesById = importIndividualCourses(dataTransfer.individualCourses, teachersById, musiciansById)
        importAttendance(dataTransfer.attendance, termsById, individualCoursesById, bandsById)
        importExpenses(dataTransfer.expenses)

        DataImportResult(
            importedAt = OffsetDateTime.now(ZoneOffset.UTC),
            settings = dataTransfer.settings.size,
            teachers = dataTransfer.teachers.size,
            musicians = dataTransfer.musicians.size,
            bands = dataTransfer.bands.size,
            individualCourses = dataTransfer.individualCourses.size,
            attendance = dataTransfer.attendance.size,
            expenses = dataTransfer.expenses.size,
        )
    }

    private suspend fun clearBusinessData() {
        generatedDocumentRepository.deleteAll().awaitSuspending()
        expenseRepository.deleteAll().awaitSuspending()
        attendanceRepository.deleteAll().awaitSuspending()
        individualCourseRepository.deleteAll().awaitSuspending()
        bandMemberRepository.deleteAll().awaitSuspending()
        bandRepository.deleteAll().awaitSuspending()
        musicianRepository.deleteAll().awaitSuspending()
        teacherRepository.deleteAll().awaitSuspending()
        termRepository.deleteAll().awaitSuspending()
        accountingYearSettingsRepository.deleteAll().awaitSuspending()
    }

    private suspend fun importSettings(settings: List<Settings>): Map<UUID, TermEntity> {
        val termsById = mutableMapOf<UUID, TermEntity>()
        settings.forEach { dto ->
            val entity = AccountingYearSettingsEntity().apply {
                year = dto.year
                teacherHourlyRate = dto.teacherHourlyRate
                groupMembershipFee = dto.groupMembershipFee
                schoolHolidayWeeks = mapOf("weeks" to dto.schoolHolidayWeeks)
            }
            accountingYearSettingsRepository.persist(entity).awaitSuspending()
            dto.terms.forEach { termDto ->
                val term = TermEntity().apply {
                    this.settings = entity
                    name = termDto.name
                    startWeek = termDto.startWeek
                    endWeek = termDto.endWeek
                    displayOrder = termDto.displayOrder
                }
                termRepository.persist(term).awaitSuspending()
                termDto.id?.let { termsById[it] = term }
            }
        }
        return termsById
    }

    private suspend fun importTeachers(teachers: List<fr.spiritbox.compta.openapi.model.Teacher>): Map<UUID, TeacherEntity> {
        val entities = teachers.map { dto ->
            val entity = TeacherEntity().apply {
                firstName = dto.firstName
                lastName = dto.lastName
                instrument = dto.instrument
                active = dto.active
            }
            requireNotNull(dto.id) { "Teacher id is required in import." } to entity
        }
        if (entities.isNotEmpty()) {
            teacherRepository.persist(entities.map { it.second }).awaitSuspending()
        }
        return entities.toMap()
    }

    private suspend fun importMusicians(musicians: List<fr.spiritbox.compta.openapi.model.Musician>): Map<UUID, MusicianEntity> {
        val entities = musicians.map { dto ->
            val entity = MusicianEntity().apply {
                firstName = dto.firstName
                lastName = dto.lastName
                email = dto.email
                active = dto.active
            }
            requireNotNull(dto.id) { "Musician id is required in import." } to entity
        }
        if (entities.isNotEmpty()) {
            musicianRepository.persist(entities.map { it.second }).awaitSuspending()
        }
        return entities.toMap()
    }

    private suspend fun importBands(
        bands: List<fr.spiritbox.compta.openapi.model.Band>,
        teachersById: Map<UUID, TeacherEntity>,
        musiciansById: Map<UUID, MusicianEntity>,
    ): Map<UUID, BandEntity> {
        val bandsById = mutableMapOf<UUID, BandEntity>()
        bands.forEach { dto ->
            val band = BandEntity().apply {
                name = dto.name
                type = BandType.valueOf(dto.type.name)
                teacher = dto.teacherId?.let { teachersById.required(it, "Teacher") }
                weekday = dto.weekday
            }
            bandRepository.persist(band).awaitSuspending()
            requireNotNull(dto.id) { "Band id is required in import." }.let { bandsById[it] = band }
            dto.memberIds.distinct().forEach { musicianId ->
                bandMemberRepository.persist(BandMemberEntity().apply {
                    this.band = band
                    this.musician = musiciansById.required(musicianId, "Musician")
                }).awaitSuspending()
            }
        }
        return bandsById
    }

    private suspend fun importIndividualCourses(
        individualCourses: List<fr.spiritbox.compta.openapi.model.IndividualCourse>,
        teachersById: Map<UUID, TeacherEntity>,
        musiciansById: Map<UUID, MusicianEntity>,
    ): Map<UUID, IndividualCourseEntity> {
        val entities = individualCourses.map { dto ->
            requireNotNull(dto.id) { "Individual course id is required in import." } to IndividualCourseEntity().apply {
                musician = musiciansById.required(dto.musicianId, "Musician")
                teacher = teachersById.required(dto.teacherId, "Teacher")
                instrument = dto.instrument
                weekday = dto.weekday
                startTime = java.time.LocalTime.parse(dto.startTime)
                sharedSlot = dto.sharedSlot
                active = dto.active
            }
        }
        if (entities.isNotEmpty()) {
            individualCourseRepository.persist(entities.map { it.second }).awaitSuspending()
        }
        return entities.toMap()
    }

    private suspend fun importAttendance(
        attendance: List<fr.spiritbox.compta.openapi.model.Attendance>,
        termsById: Map<UUID, TermEntity>,
        individualCoursesById: Map<UUID, IndividualCourseEntity>,
        bandsById: Map<UUID, BandEntity>,
    ) {
        val entities = attendance.map { dto ->
            AttendanceEntity().apply {
                term = termsById.required(dto.termId, "Term")
                week = dto.week
                entityType = AttendanceEntityType.valueOf(dto.entityType.name)
                entityId = when (entityType) {
                    AttendanceEntityType.INDIVIDUAL_COURSE ->
                        requireNotNull(individualCoursesById.required(dto.entityId, "Individual course").id)
                    AttendanceEntityType.WORKSHOP ->
                        requireNotNull(bandsById.required(dto.entityId, "Band").id)
                }
                present = dto.present
            }
        }
        if (entities.isNotEmpty()) {
            attendanceRepository.persist(entities).awaitSuspending()
        }
    }

    private suspend fun importExpenses(expenses: List<fr.spiritbox.compta.openapi.model.Expense>) {
        val entities = expenses.map { dto ->
            ExpenseEntity().apply {
                year = dto.year
                date = dto.date
                category = ExpenseCategory.valueOf(dto.category.name)
                label = dto.label
                amount = dto.amount
                notes = dto.notes
            }
        }
        if (entities.isNotEmpty()) {
            expenseRepository.persist(entities).awaitSuspending()
        }
    }

    private fun <T> Map<UUID, T>.required(id: UUID, label: String): T =
        this[id] ?: throw BadRequestException("$label $id was not found in imported data.")

    companion object {
        private const val FORMAT_VERSION = 1
    }
}
