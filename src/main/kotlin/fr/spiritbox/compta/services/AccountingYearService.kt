package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.domain.BandType
import fr.spiritbox.compta.domain.GeneratedDocumentEntity
import fr.spiritbox.compta.domain.GeneratedDocumentStatus
import fr.spiritbox.compta.domain.GeneratedDocumentType
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.AccountingYearSnapshot
import fr.spiritbox.compta.openapi.model.BillingSummary
import fr.spiritbox.compta.openapi.model.BillingTotals
import fr.spiritbox.compta.openapi.model.GeneratedDocument
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.openapi.model.StudentInvoiceDraft
import fr.spiritbox.compta.openapi.model.TeacherInvoiceRequestDraft
import fr.spiritbox.compta.openapi.model.TeacherInvoiceWeeklyLine
import fr.spiritbox.compta.repositories.AccountingYearSettingsRepository
import fr.spiritbox.compta.repositories.AttendanceRepository
import fr.spiritbox.compta.repositories.BandRepository
import fr.spiritbox.compta.repositories.GeneratedDocumentRepository
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.repositories.MusicianRepository
import fr.spiritbox.compta.repositories.TeacherRepository
import fr.spiritbox.compta.repositories.TermRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.*

@ApplicationScoped
class AccountingYearService(
    private val accountingYearSettingsRepository: AccountingYearSettingsRepository,
    private val termRepository: TermRepository,
    private val teacherRepository: TeacherRepository,
    private val musicianRepository: MusicianRepository,
    private val bandRepository: BandRepository,
    private val individualCourseRepository: IndividualCourseRepository,
    private val attendanceRepository: AttendanceRepository,
    private val generatedDocumentRepository: GeneratedDocumentRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
    private val htmlPdfService: HtmlPdfService,
    private val documentTemplateService: DocumentTemplateService,
    @ConfigProperty(name = "compta.billing.individual-course")
    private val individualCourseHours: BigDecimal,
    @ConfigProperty(name = "compta.billing.workshop")
    private val workshopHours: BigDecimal,
) {
    private val defaultTeacherHourlyRate = BigDecimal("54.00")
    private val defaultGroupMembershipFee = BigDecimal("30.00")

    suspend fun getSnapshot(year: Int): AccountingYearSnapshot = panacheCoroutine.withTransaction {
        getSnapshotInSession(year)
    }

    private suspend fun getSnapshotInSession(year: Int): AccountingYearSnapshot {

        val settings = getOrCreateSettings(year)
        val teachers = teacherRepository.findAll().list().awaitSuspending()
        val musicians = musicianRepository.findAll().list().awaitSuspending()
        val bands = bandRepository.listWithMembers().awaitSuspending()
        val individualCourse = individualCourseRepository.listWithRelations().awaitSuspending()
        val attendance = attendanceRepository.listByAccountingYear(year).awaitSuspending()

        return mapper.toSnapshotDto(
            settings,
            teachers,
            musicians,
            bands,
            individualCourse,
            attendance
        )
    }

    suspend fun getTermBilling(year: Int, termId: UUID): BillingSummary = panacheCoroutine.withSession {
        getTermBillingInSession(year, termId)
    }

    private suspend fun getTermBillingInSession(year: Int, termId: UUID): BillingSummary {
        val settings = getSettings(year)
        val term = getTerm(year, termId)
        val attendance = attendanceRepository.listByAccountingYearAndTerm(year, termId).awaitSuspending()
        val individualCourses = individualCourseRepository.listWithRelations().awaitSuspending()
        val bands = bandRepository.listWithMembers().awaitSuspending()
        val teachers = teacherRepository.findAll().list().awaitSuspending()

        val presentAttendance = attendance.filter { it.present }
        val coursesById = individualCourses.associateBy { requireNotNull(it.id) }
        val courseCountsByMusician = presentAttendance
            .filter { it.entityType == AttendanceEntityType.INDIVIDUAL_COURSE }
            .mapNotNull { coursesById[it.entityId]?.musician?.id }
            .groupingBy { it }
            .eachCount()

        val groupMemberIds = bands
            .flatMap { band -> band.members.mapNotNull { it.musician.id } }
            .toSet()
        val firstTerm = settings.terms.minByOrNull { it.displayOrder }
        val applyGroupMembershipFee = firstTerm?.id == term.id

        val studentIds = (courseCountsByMusician.keys + groupMemberIds).filterNotNull().toSortedSet()
        val studentInvoices = studentIds.map { musicianId ->
            val courseCount = courseCountsByMusician[musicianId] ?: 0
            val individualCourseAmount = halfHourAmount(settings.teacherHourlyRate, courseCount)
            val groupMembershipAmount = if (applyGroupMembershipFee && musicianId in groupMemberIds) {
                settings.groupMembershipFee.toMoney()
            } else {
                BigDecimal.ZERO.toMoney()
            }

            StudentInvoiceDraft(
                musicianId = musicianId,
                individualCourseCount = courseCount,
                individualCourseAmount = individualCourseAmount,
                groupMembershipAmount = groupMembershipAmount,
                totalAmount = individualCourseAmount.add(groupMembershipAmount).toMoney(),
            )
        }

        val teacherRequests = teachers.mapNotNull { teacher ->
            val teacherId = teacher.id ?: return@mapNotNull null
            val hoursByWeek = sortedMapOf<Int, BigDecimal>()

            presentAttendance
                .filter { it.entityType == AttendanceEntityType.INDIVIDUAL_COURSE }
                .forEach { entry ->
                    val course = coursesById[entry.entityId]
                    if (course?.teacher?.id == teacherId) {
                        hoursByWeek.merge(entry.week, individualCourseHours, BigDecimal::add)
                    }
                }

            val teacherWorkshopIds = bands
                .filter { it.type == BandType.WORKSHOP && it.teacher?.id == teacherId }
                .mapNotNull { it.id }
                .toSet()
            presentAttendance
                .filter { it.entityType == AttendanceEntityType.WORKSHOP && it.entityId in teacherWorkshopIds }
                .forEach { entry -> hoursByWeek.merge(entry.week, workshopHours, BigDecimal::add) }

            val weeklyLines = hoursByWeek
                .filterValues { it > BigDecimal.ZERO }
                .map { (week, hours) ->
                    TeacherInvoiceWeeklyLine(
                        week = week,
                        date = firstDayOfAccountingWeek(year, week),
                        hours = hours.toHours(),
                        amount = hours.multiply(settings.teacherHourlyRate).toMoney(),
                    )
                }
            if (weeklyLines.isEmpty()) {
                null
            } else {
                val totalHours = weeklyLines.fold(BigDecimal.ZERO) { total, line -> total.add(line.hours) }.toHours()
                val totalAmount = weeklyLines.fold(BigDecimal.ZERO) { total, line -> total.add(line.amount) }.toMoney()

                TeacherInvoiceRequestDraft(
                    teacherId = teacherId,
                    weeklyLines = weeklyLines,
                    totalHours = totalHours,
                    totalAmount = totalAmount,
                )
            }
        }

        val studentBilling = studentInvoices
            .fold(BigDecimal.ZERO) { total, invoice -> total.add(invoice.individualCourseAmount) }
            .toMoney()
        val groupFees = studentInvoices
            .fold(BigDecimal.ZERO) { total, invoice -> total.add(invoice.groupMembershipAmount) }
            .toMoney()
        val teacherDue = teacherRequests
            .fold(BigDecimal.ZERO) { total, request -> total.add(request.totalAmount) }
            .toMoney()

        return BillingSummary(
            termId = termId,
            studentInvoices = studentInvoices,
            teacherInvoiceRequests = teacherRequests,
            totals = BillingTotals(
                studentBilling = studentBilling,
                groupFees = groupFees,
                teacherDue = teacherDue,
                subsidy = studentBilling.add(groupFees).subtract(teacherDue).toMoney(),
            ),
        )
    }

    suspend fun listTermAttendance(year: Int, termId: UUID) = panacheCoroutine.withSession {
        attendanceRepository.listByAccountingYearAndTerm(year, termId)
            .awaitSuspending()
            .map { mapper.run { it.toDto() } }
    }

    suspend fun prepareStudentInvoices(year: Int, termId: UUID): List<GeneratedDocument> =
        panacheCoroutine.withTransaction {
            prepareStudentInvoicesInTransaction(year, termId)
        }

    private suspend fun prepareStudentInvoicesInTransaction(year: Int, termId: UUID): List<GeneratedDocument> {
        val term = getTerm(year, termId)
        val existingDocuments = generatedDocumentRepository
            .listByTermAndType(termId, GeneratedDocumentType.STUDENT_INVOICE)
            .awaitSuspending()
        val billing = getTermBillingInSession(year, termId)
        val documentsByMusician = existingDocuments.mapNotNull { document ->
            document.musician?.id?.let { it to document }
        }.toMap()
        val documents = billing.studentInvoices
            .filter { it.totalAmount > BigDecimal.ZERO }
            .map { draft ->
                val document = documentsByMusician[draft.musicianId] ?: GeneratedDocumentEntity().apply {
                    this.term = term
                    this.type = GeneratedDocumentType.STUDENT_INVOICE
                    this.status = GeneratedDocumentStatus.DRAFT
                    this.musician = musicianRepository.findById(draft.musicianId).awaitSuspending()
                }
                document.totalAmount = draft.totalAmount
                generateStudentInvoicePdf(year, term, document, draft)
                document
            }
        if (documents.isEmpty()) {
            return emptyList()
        }
        val newDocuments = documents.filter { it.id == null }
        if (newDocuments.isNotEmpty()) {
            generatedDocumentRepository.persist(newDocuments).awaitSuspending()
        }
        prepareStudentInvoiceSummaryDocument(year, term, billing)
        return documents.map { mapper.run { it.toDto() } }
    }

    suspend fun prepareStudentInvoiceSummary(year: Int, termId: UUID): GeneratedDocument =
        panacheCoroutine.withTransaction {
            val term = getTerm(year, termId)
            val billing = getTermBillingInSession(year, termId)
            val document = prepareStudentInvoiceSummaryDocument(year, term, billing)
            mapper.run { document.toDto() }
        }

    private suspend fun prepareStudentInvoiceSummaryDocument(
        year: Int,
        term: TermEntity,
        billing: BillingSummary,
    ): GeneratedDocumentEntity {
        val termId = requireNotNull(term.id) { "Cannot generate a document for a transient term." }
        val document = generatedDocumentRepository
            .listByTermAndType(termId, GeneratedDocumentType.STUDENT_INVOICE)
            .awaitSuspending()
            .firstOrNull { it.musician == null && it.teacher == null }
            ?: GeneratedDocumentEntity().apply {
                this.term = term
                this.type = GeneratedDocumentType.STUDENT_INVOICE
                this.status = GeneratedDocumentStatus.DRAFT
            }
        val drafts = billing.studentInvoices.filter { it.totalAmount > BigDecimal.ZERO }
        val total = drafts.fold(BigDecimal.ZERO) { sum, draft -> sum.add(draft.totalAmount) }.toMoney()
        document.totalAmount = total
        val musicianIds = drafts.map { it.musicianId }.distinct()
        val musiciansById = if (musicianIds.isEmpty()) {
            emptyMap()
        } else {
            musicianRepository.find("id in ?1", musicianIds)
                .list()
                .awaitSuspending()
                .mapNotNull { musician -> musician.id?.let { it to musician } }
                .toMap()
        }
        generateStudentInvoiceSummaryPdf(year, term, document, drafts, musiciansById, total)
        if (document.id == null) {
            generatedDocumentRepository.persist(document).awaitSuspending()
        }
        return document
    }

    suspend fun prepareTeacherInvoiceRequests(year: Int, termId: UUID): List<GeneratedDocument> =
        panacheCoroutine.withTransaction {
            prepareTeacherInvoiceRequestsInTransaction(year, termId)
        }

    private suspend fun prepareTeacherInvoiceRequestsInTransaction(year: Int, termId: UUID): List<GeneratedDocument> {
        val term = getTerm(year, termId)
        val existingDocuments = generatedDocumentRepository
            .listByTermAndType(termId, GeneratedDocumentType.TEACHER_INVOICE_REQUEST)
            .awaitSuspending()
        val billing = getTermBillingInSession(year, termId)
        val documentsByTeacher = existingDocuments.mapNotNull { document ->
            document.teacher?.id?.let { it to document }
        }.toMap()
        val documents = billing.teacherInvoiceRequests
            .filter { it.totalAmount > BigDecimal.ZERO }
            .map { draft ->
                val document = documentsByTeacher[draft.teacherId] ?: GeneratedDocumentEntity().apply {
                    this.term = term
                    this.type = GeneratedDocumentType.TEACHER_INVOICE_REQUEST
                    this.status = GeneratedDocumentStatus.DRAFT
                    this.teacher = teacherRepository.findById(draft.teacherId).awaitSuspending()
                }
                document.totalAmount = draft.totalAmount
                generateTeacherInvoiceRequestPdf(year, term, document, draft)
                document
            }
        if (documents.isEmpty()) {
            return emptyList()
        }
        val newDocuments = documents.filter { it.id == null }
        if (newDocuments.isNotEmpty()) {
            generatedDocumentRepository.persist(newDocuments).awaitSuspending()
        }
        return documents.map { mapper.run { it.toDto() } }
    }

    private fun generateStudentInvoicePdf(
        year: Int,
        term: TermEntity,
        document: GeneratedDocumentEntity,
        draft: StudentInvoiceDraft,
    ) {
        val musician = requireNotNull(document.musician) { "Student invoice requires a musician." }
        val documentNumber = document.documentNumber ?: buildDocumentNumber("FE", year, term, musician.id)
        val fileName = "${documentNumber.toFileName()}-${musician.fullName().toFileName()}.pdf"
        val storagePath = "$year/${requireNotNull(term.id)}/$fileName"

        document.documentNumber = documentNumber
        document.fileName = fileName
        document.contentType = "application/pdf"
        document.storagePath = htmlPdfService.writeHtmlPdf(
            storagePath,
            documentTemplateService.studentInvoice(
                documentNumber = documentNumber,
                year = year,
                term = term,
                musician = musician,
                draft = draft,
            ),
        )
        document.status = GeneratedDocumentStatus.GENERATED
    }

    private fun generateStudentInvoiceSummaryPdf(
        year: Int,
        term: TermEntity,
        document: GeneratedDocumentEntity,
        drafts: List<StudentInvoiceDraft>,
        musiciansById: Map<UUID, MusicianEntity>,
        total: BigDecimal,
    ) {
        val termId = requireNotNull(term.id)
        val documentNumber = document.documentNumber ?: "FE-$year-T${term.displayOrder}-GLOBAL"
        val fileName = "${documentNumber.toFileName()}-synthese-eleves.pdf"
        val storagePath = "$year/$termId/$fileName"

        document.documentNumber = documentNumber
        document.fileName = fileName
        document.contentType = "application/pdf"
        document.storagePath = htmlPdfService.writeHtmlPdf(
            storagePath,
            documentTemplateService.studentInvoiceSummary(
                documentNumber = documentNumber,
                year = year,
                term = term,
                drafts = drafts,
                musiciansById = musiciansById,
                total = total,
            ),
        )
        document.status = GeneratedDocumentStatus.GENERATED
    }

    private fun generateTeacherInvoiceRequestPdf(
        year: Int,
        term: TermEntity,
        document: GeneratedDocumentEntity,
        draft: TeacherInvoiceRequestDraft,
    ) {
        val teacher = requireNotNull(document.teacher) { "Teacher invoice request requires a teacher." }
        val documentNumber = document.documentNumber ?: buildDocumentNumber("DFP", year, term, teacher.id)
        val fileName = "${documentNumber.toFileName()}-${teacher.fullName().toFileName()}.pdf"
        val storagePath = "$year/${requireNotNull(term.id)}/$fileName"
        document.documentNumber = documentNumber
        document.fileName = fileName
        document.contentType = "application/pdf"
        document.storagePath = htmlPdfService.writeHtmlPdf(
            storagePath,
            documentTemplateService.teacherInvoiceRequest(
                documentNumber = documentNumber,
                year = year,
                term = term,
                teacher = teacher,
                draft = draft,
            ),
        )
        document.status = GeneratedDocumentStatus.GENERATED
    }

    private fun buildDocumentNumber(prefix: String, year: Int, term: TermEntity, ownerId: UUID?) =
        "$prefix-$year-T${term.displayOrder}-${ownerId.toString().take(8)}".uppercase()

    private fun MusicianEntity.fullName() = "$firstName $lastName".trim()

    private fun TeacherEntity.fullName() = "$firstName $lastName".trim()

    private fun String.toFileName() =
        lowercase()
            .replace(Regex("""[^a-z0-9._-]+"""), "-")
            .trim('-')

    suspend fun updateSettings(year: Int, settings: Settings): Settings =
        panacheCoroutine.withTransaction {
            updateSettingsInTransaction(year, settings)
        }

    private suspend fun updateSettingsInTransaction(year: Int, settings: Settings): Settings {
        if (settings.year != year) {
            throw BadRequestException("Path year $year does not match payload year ${settings.year}.")
        }

        val entity = accountingYearSettingsRepository.findByYearWithTerms(year).awaitSuspending()
            ?: AccountingYearSettingsEntity().also {
                it.year = year
                accountingYearSettingsRepository.persist(it).awaitSuspending()
            }

        entity.teacherHourlyRate = settings.teacherHourlyRate
        entity.groupMembershipFee = settings.groupMembershipFee
        entity.schoolHolidayWeeks = mapOf("weeks" to settings.schoolHolidayWeeks.sorted())

        val existingTermsById = entity.terms.mapNotNull { term -> term.id?.let { it to term } }.toMap()
        val newTerms = settings.terms.map { termDto ->
            val termEntity = termDto.id?.let { existingTermsById[it] } ?: TermEntity().apply {
                this.settings = entity
            }
            termEntity.name = termDto.name
            termEntity.startWeek = termDto.startWeek
            termEntity.endWeek = termDto.endWeek
            termEntity.displayOrder = termDto.displayOrder
            termEntity
        }.toMutableList()
        entity.terms.clear()
        entity.terms.addAll(newTerms)

        return mapper.run { entity.toDto() }
    }

    private suspend fun getSettings(year: Int) = accountingYearSettingsRepository.findByYearWithTerms(year)
        .awaitSuspending()
        ?: throw NotFoundException("Accounting year $year was not found.")

    private suspend fun getOrCreateSettings(year: Int): AccountingYearSettingsEntity {
        val settings = accountingYearSettingsRepository.findByYearWithTerms(year).awaitSuspending()
            ?: return createDefaultSettings(year)

        if (settings.teacherHourlyRate.compareTo(BigDecimal.ZERO) == 0) {
            settings.teacherHourlyRate = defaultTeacherHourlyRate
        }
        if (settings.groupMembershipFee.compareTo(BigDecimal.ZERO) == 0) {
            settings.groupMembershipFee = defaultGroupMembershipFee
        }
        return settings
    }

    private suspend fun createDefaultSettings(year: Int): AccountingYearSettingsEntity {
        val settings = AccountingYearSettingsEntity().apply {
            this.year = year
            teacherHourlyRate = defaultTeacherHourlyRate
            groupMembershipFee = defaultGroupMembershipFee
            schoolHolidayWeeks = mapOf("weeks" to emptyList())
        }
        settings.terms.addAll(
            listOf(
                defaultTerm(settings, "Trimestre 1", 2, 14, 1),
                defaultTerm(settings, "Trimestre 2", 16, 27, 2),
                defaultTerm(settings, "Trimestre 3", 38, 51, 3),
            ),
        )
        accountingYearSettingsRepository.persist(settings).awaitSuspending()

        return settings
    }

    private fun defaultTerm(
        settings: AccountingYearSettingsEntity,
        name: String,
        startWeek: Int,
        endWeek: Int,
        displayOrder: Int,
    ) = TermEntity().apply {
        this.settings = settings
        this.name = name
        this.startWeek = startWeek
        this.endWeek = endWeek
        this.displayOrder = displayOrder
    }

    private suspend fun getTerm(year: Int, termId: UUID) = termRepository.findByYearAndId(year, termId)
        .awaitSuspending()
        ?: throw NotFoundException("Term $termId was not found for accounting year $year.")

    private fun halfHourAmount(hourlyRate: BigDecimal, count: Int) =
        hourlyRate.multiply(individualCourseHours).multiply(BigDecimal(count)).toMoney()

    private fun firstDayOfAccountingWeek(year: Int, week: Int): LocalDate {
        if (week !in 1..53) {
            throw BadRequestException("Week must be between 1 and 53.")
        }

        val firstDayOfYear = LocalDate.of(year, 1, 1)
        if (week == 1) {
            return firstDayOfYear
        }

        val firstMonday = firstDayOfYear.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        return firstMonday.plusWeeks((week - 2).toLong())
    }

    private fun BigDecimal.toMoney() = setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    private fun BigDecimal.toHours() = setScale(HOUR_SCALE, RoundingMode.HALF_UP)

    private companion object {
        const val MONEY_SCALE = 2
        const val HOUR_SCALE = 2
    }


}
