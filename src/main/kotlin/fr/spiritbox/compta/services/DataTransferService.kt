package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.AccountingYearStatus
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
import fr.spiritbox.compta.openapi.model.DataImportAnalysis
import fr.spiritbox.compta.openapi.model.DataImportCounts
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
import fr.spiritbox.compta.repositories.TeacherInvoiceAttendanceAllocationRepository
import fr.spiritbox.compta.repositories.TeacherBillingAdjustmentRepository
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
    private val allocationRepository: TeacherInvoiceAttendanceAllocationRepository,
    private val adjustmentRepository: TeacherBillingAdjustmentRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
    private val backupService: BackupService,
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

    suspend fun analyzeImport(dataTransfer: DataTransfer): DataImportAnalysis = panacheCoroutine.withSession {
        val errors = validate(dataTransfer)
        DataImportAnalysis(
            valid = errors.isEmpty(),
            errors = errors,
            current = currentCounts(),
            incoming = incomingCounts(dataTransfer),
            confirmationValue = IMPORT_CONFIRMATION,
        )
    }

    suspend fun importData(dataTransfer: DataTransfer, confirmation: String): DataImportResult {
        if (confirmation != IMPORT_CONFIRMATION) {
            throw BadRequestException("Import confirmation must be $IMPORT_CONFIRMATION.")
        }
        val errors = validate(dataTransfer)
        if (errors.isNotEmpty()) {
            throw BadRequestException("Import validation failed: ${errors.joinToString("; ")}")
        }
        return panacheCoroutine.withExclusiveTransaction {
            val backupFile = backupService.createWithinTransaction("before-import").fileName

            clearBusinessData()

            val importedSettings = importSettings(dataTransfer.settings)
            val teachersById = importTeachers(dataTransfer.teachers)
            val musiciansById = importMusicians(dataTransfer.musicians)
            val bandsById = importBands(
                dataTransfer.bands,
                teachersById,
                musiciansById,
                importedSettings.settingsByYear,
                dataTransfer.formatVersion,
            )
            val individualCoursesById = importIndividualCourses(
                dataTransfer.individualCourses,
                teachersById,
                musiciansById,
                importedSettings.settingsByYear,
                dataTransfer.formatVersion,
            )
            importAttendance(dataTransfer.attendance, importedSettings.termsById, individualCoursesById, bandsById)
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
                backupFile = backupFile,
            )
        }
    }

    private suspend fun currentCounts() = DataImportCounts(
        settings = accountingYearSettingsRepository.count().awaitSuspending(),
        teachers = teacherRepository.count().awaitSuspending(),
        musicians = musicianRepository.count().awaitSuspending(),
        bands = bandRepository.count().awaitSuspending(),
        individualCourses = individualCourseRepository.count().awaitSuspending(),
        attendance = attendanceRepository.count().awaitSuspending(),
        expenses = expenseRepository.count().awaitSuspending(),
        documents = generatedDocumentRepository.count().awaitSuspending(),
    )

    private fun incomingCounts(data: DataTransfer) = DataImportCounts(
        settings = data.settings.size.toLong(),
        teachers = data.teachers.size.toLong(),
        musicians = data.musicians.size.toLong(),
        bands = data.bands.size.toLong(),
        individualCourses = data.individualCourses.size.toLong(),
        attendance = data.attendance.size.toLong(),
        expenses = data.expenses.size.toLong(),
        documents = 0,
    )

    private fun validate(data: DataTransfer): List<String> {
        val errors = mutableListOf<String>()
        if (data.formatVersion !in SUPPORTED_FORMAT_VERSIONS) {
            errors += "Unsupported format version ${data.formatVersion}; expected ${SUPPORTED_FORMAT_VERSIONS.sorted()}."
        }
        val years = data.settings.map { it.year }
        duplicateValues(years).forEach { errors += "Duplicate accounting year $it." }
        val settingsYears = years.toSet()
        data.settings.forEach { settings ->
            settings.individualCourseHours?.let { if (it <= java.math.BigDecimal.ZERO || it > java.math.BigDecimal("24")) errors += "Invalid individual course duration for year ${settings.year}." }
            settings.workshopHours?.let { if (it <= java.math.BigDecimal.ZERO || it > java.math.BigDecimal("24")) errors += "Invalid workshop duration for year ${settings.year}." }
        }
        data.bands.forEach { band ->
            band.startTime?.let { value ->
                if (runCatching { java.time.LocalTime.parse(value) }.isFailure) errors += "Invalid start time $value for band ${band.id ?: band.name}."
            }
            band.durationMinutes?.let { if (it !in SlotRules.GROUP_DURATIONS) errors += "Invalid duration $it for band ${band.id ?: band.name}: 75 or 90 minutes." }
        }
        val terms = data.settings.flatMap { settings -> settings.terms.map { it to settings.year } }
        val termIds = terms.mapNotNull { it.first.id }
        if (termIds.size != terms.size) errors += "Every term must have an id."
        duplicateValues(termIds).forEach { errors += "Duplicate term id $it." }
        terms.forEach { (term, year) ->
            if (term.startWeek !in 1..53 || term.endWeek !in 1..53 || term.startWeek > term.endWeek) {
                errors += "Invalid week range for term ${term.id ?: term.name} in $year."
            }
        }
        val termById = terms.mapNotNull { (term, year) -> term.id?.let { it to (term to year) } }.toMap()
        val teacherIds = validateIds("teacher", data.teachers.map { it.id }, errors)
        val musicianIds = validateIds("musician", data.musicians.map { it.id }, errors)
        val bandIds = validateIds("band", data.bands.map { it.id }, errors)
        val courseIds = validateIds("individual course", data.individualCourses.map { it.id }, errors)
        val bandById = data.bands.mapNotNull { band -> band.id?.let { it to band } }.toMap()
        val courseById = data.individualCourses.mapNotNull { course -> course.id?.let { it to course } }.toMap()
        data.bands.forEach { band ->
            val year = runCatching { band.year.forImport(data.formatVersion) }.getOrElse {
                errors += "Accounting year is required for band ${band.id ?: band.name}."; return@forEach
            }
            if (year !in settingsYears) errors += "Band ${band.id ?: band.name} references missing year $year."
            band.teacherId?.let { if (it !in teacherIds) errors += "Band ${band.id ?: band.name} references missing teacher $it." }
            band.memberIds.forEach { if (it !in musicianIds) errors += "Band ${band.id ?: band.name} references missing musician $it." }
        }
        data.individualCourses.forEach { course ->
            val year = runCatching { course.year.forImport(data.formatVersion) }.getOrElse {
                errors += "Accounting year is required for course ${course.id}."; return@forEach
            }
            if (year !in settingsYears) errors += "Course ${course.id} references missing year $year."
            if (course.teacherId !in teacherIds) errors += "Course ${course.id} references missing teacher ${course.teacherId}."
            if (course.musicianId !in musicianIds) errors += "Course ${course.id} references missing musician ${course.musicianId}."
            if (runCatching { java.time.LocalTime.parse(course.startTime) }.isFailure) errors += "Course ${course.id} has invalid start time ${course.startTime}."
        }
        val attendanceKeys = mutableSetOf<String>()
        data.attendance.forEach { attendance ->
            val termAndYear = termById[attendance.termId]
            if (termAndYear == null) {
                errors += "Attendance references missing term ${attendance.termId}."
                return@forEach
            }
            val key = "${attendance.termId}:${attendance.week}:${attendance.entityType}:${attendance.entityId}"
            if (!attendanceKeys.add(key)) errors += "Duplicate attendance $key."
            val (term, year) = termAndYear
            if (attendance.week !in term.startWeek..term.endWeek) errors += "Attendance $key is outside the term week range."
            attendance.sessionDate?.let { date ->
                val derivedWeek = runCatching { SessionDateCalculator.accountingWeek(year, date) }.getOrNull()
                if (derivedWeek != attendance.week) errors += "Attendance $key has session date $date outside its accounting week."
            }
            when (attendance.entityType.name) {
                "INDIVIDUAL_COURSE" -> {
                    val course = courseById[attendance.entityId]
                    if (course == null) errors += "Attendance $key references missing individual course."
                    else if (runCatching { course.year.forImport(data.formatVersion) }.getOrNull() != year) errors += "Attendance $key crosses accounting years."
                }
                "WORKSHOP" -> {
                    val band = bandById[attendance.entityId]
                    if (band == null) errors += "Attendance $key references missing workshop."
                    else {
                        if (band.type.name != "WORKSHOP") errors += "Attendance $key targets a band that is not a workshop."
                        if (runCatching { band.year.forImport(data.formatVersion) }.getOrNull() != year) errors += "Attendance $key crosses accounting years."
                    }
                }
            }
        }
        data.expenses.forEach { if (it.year !in settingsYears) errors += "Expense ${it.id ?: it.label} references missing year ${it.year}." }
        return errors.distinct()
    }

    private fun validateIds(label: String, ids: List<UUID?>, errors: MutableList<String>): Set<UUID> {
        if (ids.any { it == null }) errors += "Every $label must have an id."
        duplicateValues(ids.filterNotNull()).forEach { errors += "Duplicate $label id $it." }
        return ids.filterNotNull().toSet()
    }

    private fun <T> duplicateValues(values: List<T>): Set<T> = values.groupingBy { it }.eachCount()
        .filterValues { it > 1 }.keys

    private suspend fun clearBusinessData() {
        adjustmentRepository.deleteAll().awaitSuspending()
        allocationRepository.deleteAll().awaitSuspending()
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

    private suspend fun importSettings(settings: List<Settings>): ImportedSettings {
        val termsById = mutableMapOf<UUID, TermEntity>()
        val settingsByYear = mutableMapOf<Int, AccountingYearSettingsEntity>()
        settings.forEach { dto ->
            val entity = AccountingYearSettingsEntity().apply {
                year = dto.year
                status = dto.status?.let { AccountingYearStatus.valueOf(it.name) } ?: AccountingYearStatus.OPEN
                reviewedAt = dto.reviewedAt?.toInstant()
                closedAt = dto.closedAt?.toInstant()
                teacherHourlyRate = dto.teacherHourlyRate
                groupMembershipFee = dto.groupMembershipFee
                individualCourseHours = dto.individualCourseHours ?: DEFAULT_INDIVIDUAL_COURSE_HOURS
                workshopHours = dto.workshopHours ?: DEFAULT_WORKSHOP_HOURS
                schoolHolidayWeeks = mapOf("weeks" to dto.schoolHolidayWeeks)
            }
            accountingYearSettingsRepository.persist(entity).awaitSuspending()
            settingsByYear[dto.year] = entity
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
        return ImportedSettings(termsById, settingsByYear)
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
        settingsByYear: Map<Int, AccountingYearSettingsEntity>,
        formatVersion: Int,
    ): Map<UUID, BandEntity> {
        val bandsById = mutableMapOf<UUID, BandEntity>()
        bands.forEach { dto ->
            val band = BandEntity().apply {
                settings = settingsByYear.required(dto.year.forImport(formatVersion), "Accounting year")
                name = dto.name
                type = BandType.valueOf(dto.type.name)
                teacher = dto.teacherId?.let { teachersById.required(it, "Teacher") }
                weekday = dto.weekday
                startTime = dto.startTime?.let(java.time.LocalTime::parse)
                durationMinutes = if (type == BandType.WORKSHOP) null else dto.durationMinutes
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
        settingsByYear: Map<Int, AccountingYearSettingsEntity>,
        formatVersion: Int,
    ): Map<UUID, IndividualCourseEntity> {
        val entities = individualCourses.map { dto ->
            requireNotNull(dto.id) { "Individual course id is required in import." } to IndividualCourseEntity().apply {
                settings = settingsByYear.required(dto.year.forImport(formatVersion), "Accounting year")
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
                sessionDate = dto.sessionDate ?: SessionDateCalculator.scheduledDate(
                    term.settings.year,
                    dto.week,
                    when (entityType) {
                        AttendanceEntityType.INDIVIDUAL_COURSE -> individualCoursesById.required(dto.entityId, "Individual course").weekday
                        AttendanceEntityType.WORKSHOP -> bandsById.required(dto.entityId, "Band").weekday
                    },
                )
                entityId = when (entityType) {
                    AttendanceEntityType.INDIVIDUAL_COURSE ->
                        requireNotNull(individualCoursesById.required(dto.entityId, "Individual course").id)
                    AttendanceEntityType.WORKSHOP ->
                        requireNotNull(bandsById.required(dto.entityId, "Band").id)
                }
                status = dto.status?.let { fr.spiritbox.compta.domain.AttendanceStatus.valueOf(it.name) }
                    ?: if (dto.present) fr.spiritbox.compta.domain.AttendanceStatus.PRESENT else fr.spiritbox.compta.domain.AttendanceStatus.ABSENT
                present = status == fr.spiritbox.compta.domain.AttendanceStatus.PRESENT
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

    private fun <T> Map<Int, T>.required(year: Int, label: String): T =
        this[year] ?: throw BadRequestException("$label $year was not found in imported data.")

    private fun Int?.forImport(formatVersion: Int): Int = when {
        this != null -> this
        formatVersion == 1 -> LEGACY_ACCOUNTING_YEAR
        else -> throw BadRequestException("Accounting year is required for annualized data.")
    }

    private data class ImportedSettings(
        val termsById: Map<UUID, TermEntity>,
        val settingsByYear: Map<Int, AccountingYearSettingsEntity>,
    )

    companion object {
        private const val FORMAT_VERSION = 2
        private const val LEGACY_ACCOUNTING_YEAR = 2026
        private val DEFAULT_INDIVIDUAL_COURSE_HOURS = java.math.BigDecimal("0.50")
        private val DEFAULT_WORKSHOP_HOURS = java.math.BigDecimal("1.25")
        const val IMPORT_CONFIRMATION = "REPLACE_ALL_DATA"
        private val SUPPORTED_FORMAT_VERSIONS = setOf(1, FORMAT_VERSION)
    }
}
