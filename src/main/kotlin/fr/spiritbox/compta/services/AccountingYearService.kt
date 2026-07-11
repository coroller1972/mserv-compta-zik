package fr.spiritbox.compta.services

import com.fasterxml.jackson.databind.ObjectMapper
import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.AccountingYearStatus as AccountingYearStatusEntity
import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.domain.BandType
import fr.spiritbox.compta.domain.BandEntity
import fr.spiritbox.compta.domain.BandMemberEntity
import fr.spiritbox.compta.domain.IndividualCourseEntity
import fr.spiritbox.compta.domain.GeneratedDocumentEntity
import fr.spiritbox.compta.domain.GeneratedDocumentStatus
import fr.spiritbox.compta.domain.GeneratedDocumentType
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.AccountingYearSnapshot
import fr.spiritbox.compta.openapi.model.AccountingYearStatusUpdate
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
import fr.spiritbox.compta.repositories.BandMemberRepository
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
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

@ApplicationScoped
class AccountingYearService(
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
    private val lifecycleService: AccountingYearLifecycleService,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
    private val htmlPdfService: HtmlPdfService,
    private val documentTemplateService: DocumentTemplateService,
    private val objectMapper: ObjectMapper,
    private val auditService: AuditService,
) {
    private val defaultTeacherHourlyRate = BigDecimal("54.00")
    private val defaultGroupMembershipFee = BigDecimal("30.00")

    suspend fun getSnapshot(year: Int): AccountingYearSnapshot = panacheCoroutine.withTransaction {
        getSnapshotInSession(year)
    }

    suspend fun copyConfiguration(year: Int, sourceYear: Int): AccountingYearSnapshot =
        panacheCoroutine.withTransaction {
            if (year == sourceYear) {
                throw BadRequestException("Source and target accounting years must be different.")
            }
            val sourceSettings = getSettings(sourceYear)
            val existingTargetSettings = accountingYearSettingsRepository.findByYearWithTerms(year).awaitSuspending()
            val targetSettings = existingTargetSettings ?: AccountingYearSettingsEntity().apply {
                this.year = year
                teacherHourlyRate = sourceSettings.teacherHourlyRate
                groupMembershipFee = sourceSettings.groupMembershipFee
                individualCourseHours = sourceSettings.individualCourseHours
                workshopHours = sourceSettings.workshopHours
                schoolHolidayWeeks = sourceSettings.schoolHolidayWeeks
                terms.addAll(sourceSettings.terms.map { sourceTerm -> copiedTerm(this, sourceTerm) })
                accountingYearSettingsRepository.persist(this).awaitSuspending()
            }
            lifecycleService.requireStructureMutable(targetSettings)
            val targetBands = bandRepository.listWithMembersByYear(year).awaitSuspending()
            val targetCourses = individualCourseRepository.listWithRelationsByYear(year).awaitSuspending()
            val targetAttendance = attendanceRepository.listByAccountingYear(year).awaitSuspending()
            val targetDocuments = generatedDocumentRepository.count("term.settings.year", year).awaitSuspending()
            if (targetBands.isNotEmpty() || targetCourses.isNotEmpty() || targetAttendance.isNotEmpty() || targetDocuments > 0) {
                throw BadRequestException("Accounting year $year already contains annual business data.")
            }

            if (existingTargetSettings != null) {
                targetSettings.teacherHourlyRate = sourceSettings.teacherHourlyRate
                targetSettings.groupMembershipFee = sourceSettings.groupMembershipFee
                targetSettings.individualCourseHours = sourceSettings.individualCourseHours
                targetSettings.workshopHours = sourceSettings.workshopHours
                targetSettings.schoolHolidayWeeks = sourceSettings.schoolHolidayWeeks
                targetSettings.terms.clear()
                targetSettings.terms.addAll(sourceSettings.terms.map { sourceTerm ->
                    copiedTerm(targetSettings, sourceTerm)
                })
            }

            val sourceBands = bandRepository.listWithMembersByYear(sourceYear).awaitSuspending()
            sourceBands.forEach { sourceBand ->
                val targetBand = BandEntity().apply {
                    settings = targetSettings
                    name = sourceBand.name
                    type = sourceBand.type
                    teacher = sourceBand.teacher
                    weekday = sourceBand.weekday
                }
                bandRepository.persist(targetBand).awaitSuspending()
                sourceBand.members.forEach { sourceMember ->
                    bandMemberRepository.persist(BandMemberEntity().apply {
                        band = targetBand
                        musician = sourceMember.musician
                    }).awaitSuspending()
                }
            }

            individualCourseRepository.listWithRelationsByYear(sourceYear).awaitSuspending().forEach { sourceCourse ->
                individualCourseRepository.persist(IndividualCourseEntity().apply {
                    settings = targetSettings
                    musician = sourceCourse.musician
                    teacher = sourceCourse.teacher
                    instrument = sourceCourse.instrument
                    weekday = sourceCourse.weekday
                    startTime = sourceCourse.startTime
                    sharedSlot = sourceCourse.sharedSlot
                    active = sourceCourse.active
                }).awaitSuspending()
            }

            getSnapshotInSession(year)
        }

    private fun copiedTerm(settings: AccountingYearSettingsEntity, source: TermEntity) = TermEntity().apply {
        this.settings = settings
        name = source.name
        startWeek = source.startWeek
        endWeek = source.endWeek
        displayOrder = source.displayOrder
    }

    private suspend fun getSnapshotInSession(year: Int): AccountingYearSnapshot {

        val settings = getOrCreateSettings(year)
        val teachers = teacherRepository.findAll().list().awaitSuspending()
        val musicians = musicianRepository.findAll().list().awaitSuspending()
        val bands = bandRepository.listWithMembersByYear(year).awaitSuspending()
        val individualCourse = individualCourseRepository.listWithRelationsByYear(year).awaitSuspending()
        val attendance = attendanceRepository.listByAccountingYear(year).awaitSuspending()
        val expenses = expenseRepository.find("year = ?1 order by date desc, label", year).list().awaitSuspending()

        return mapper.toSnapshotDto(
            settings,
            teachers,
            musicians,
            bands,
            individualCourse,
            attendance,
            expenses
        )
    }

    suspend fun getTermBilling(year: Int, termId: UUID): BillingSummary = panacheCoroutine.withSession {
        getTermBillingInSession(year, termId)
    }

    private suspend fun getTermBillingInSession(year: Int, termId: UUID): BillingSummary {
        val settings = getSettings(year)
        val term = getTerm(year, termId)
        val attendance = attendanceRepository.listByAccountingYearAndTerm(year, termId).awaitSuspending()
        val individualCourses = individualCourseRepository.listWithRelationsByYear(year).awaitSuspending()
        val bands = bandRepository.listWithMembersByYear(year).awaitSuspending()
        val teachers = teacherRepository.findAll().list().awaitSuspending()

        val presentAttendance = attendance.filter { it.status == fr.spiritbox.compta.domain.AttendanceStatus.PRESENT }
        val coursesById = individualCourses.associateBy { requireNotNull(it.id) }
        val courseCountsByMusician = presentAttendance
            .filter { it.entityType == AttendanceEntityType.INDIVIDUAL_COURSE }
            .mapNotNull { coursesById[it.entityId]?.musician?.id }
            .groupingBy { it }
            .eachCount()

        val groupMemberIds = bands
            .flatMap { band -> band.members.mapNotNull { it.musician.id } }
            .toSet()
        val groupMembershipCounts = bands
            .flatMap { band -> band.members.mapNotNull { it.musician.id } }
            .groupingBy { it }
            .eachCount()
        val firstTerm = settings.terms.minByOrNull { it.displayOrder }
        val applyGroupMembershipFee = firstTerm?.id == term.id

        val studentIds = (courseCountsByMusician.keys + groupMemberIds).filterNotNull().toSortedSet()
        val studentInvoices = studentIds.map { musicianId ->
            val courseCount = courseCountsByMusician[musicianId] ?: 0
            val amounts = BillingCalculator.studentAmounts(
                individualCourseCount = courseCount,
                hourlyRate = settings.teacherHourlyRate,
                individualCourseHours = settings.individualCourseHours,
                groupMembershipCount = groupMembershipCounts[musicianId] ?: 0,
                applyGroupMembershipFee = applyGroupMembershipFee,
                groupMembershipFee = settings.groupMembershipFee,
            )

            StudentInvoiceDraft(
                musicianId = musicianId,
                individualCourseCount = courseCount,
                individualCourseAmount = amounts.individualCourseAmount,
                groupMembershipAmount = amounts.groupMembershipAmount,
                totalAmount = amounts.totalAmount,
            )
        }

        val teacherRequests = teachers.mapNotNull { teacher ->
            val teacherId = teacher.id ?: return@mapNotNull null
            val hoursBySession = mutableMapOf<Pair<Int, LocalDate>, BigDecimal>()

            presentAttendance
                .filter { it.entityType == AttendanceEntityType.INDIVIDUAL_COURSE }
                .forEach { entry ->
                    val course = coursesById[entry.entityId]
                    if (course?.teacher?.id == teacherId) {
                        hoursBySession.merge(entry.week to entry.sessionDate, settings.individualCourseHours, BigDecimal::add)
                    }
                }

            val teacherWorkshopIds = bands
                .filter { it.type == BandType.WORKSHOP && it.teacher?.id == teacherId }
                .mapNotNull { it.id }
                .toSet()
            presentAttendance
                .filter { it.entityType == AttendanceEntityType.WORKSHOP && it.entityId in teacherWorkshopIds }
                .forEach { entry -> hoursBySession.merge(entry.week to entry.sessionDate, settings.workshopHours, BigDecimal::add) }

            val weeklyLines = hoursBySession
                .filterValues { it > BigDecimal.ZERO }
                .entries.sortedWith(compareBy({ it.key.second }, { it.key.first }))
                .map { (key, hours) ->
                    TeacherInvoiceWeeklyLine(
                        week = key.first,
                        date = key.second,
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
            currency = BillingSummary.Currency.EUR,
            studentInvoices = studentInvoices,
            teacherInvoiceRequests = teacherRequests,
            totals = BillingTotals(
                studentBilling = studentBilling,
                groupFees = groupFees,
                teacherDue = teacherDue,
                subsidy = BillingCalculator.subsidy(
                    teacherDue = teacherDue,
                    studentBilling = studentBilling,
                    groupFees = groupFees,
                ),
            ),
        )
    }

    suspend fun listTermAttendance(year: Int, termId: UUID) = panacheCoroutine.withSession {
        attendanceRepository.listByAccountingYearAndTerm(year, termId)
            .awaitSuspending()
            .map { mapper.run { it.toDto() } }
    }

    suspend fun listTermDocuments(year: Int, termId: UUID) = panacheCoroutine.withSession {
        getTerm(year, termId)
        generatedDocumentRepository.listByTerm(termId).awaitSuspending().map { mapper.run { it.toDto() } }
    }

    suspend fun prepareStudentInvoices(year: Int, termId: UUID): List<GeneratedDocument> =
        panacheCoroutine.withTransaction {
            lifecycleService.requireDocumentsMutable(getSettings(year))
            prepareStudentInvoicesInTransaction(year, termId)
        }

    private suspend fun prepareStudentInvoicesInTransaction(year: Int, termId: UUID): List<GeneratedDocument> {
        val term = getTerm(year, termId)
        val existingDocuments = generatedDocumentRepository
            .listByTermAndType(termId, GeneratedDocumentType.STUDENT_INVOICE)
            .awaitSuspending()
        val billing = getTermBillingInSession(year, termId)
        val documentsByMusician = existingDocuments
            .filter { it.status !in setOf(GeneratedDocumentStatus.CANCELLED, GeneratedDocumentStatus.CREDITED) }
            .mapNotNull { document ->
            document.musician?.id?.let { it to document }
        }.toMap()
        val documents = billing.studentInvoices
            .filter { it.totalAmount > BigDecimal.ZERO }
            .map { draft ->
                val existingDocument = documentsByMusician[draft.musicianId]
                if (existingDocument != null && existingDocument.status != GeneratedDocumentStatus.DRAFT) {
                    return@map existingDocument
                }
                val document = existingDocument ?: GeneratedDocumentEntity().apply {
                    this.term = term
                    this.type = GeneratedDocumentType.STUDENT_INVOICE
                    this.status = GeneratedDocumentStatus.DRAFT
                    this.musician = musicianRepository.findById(draft.musicianId).awaitSuspending()
                }
                document.totalAmount = draft.totalAmount
                generateStudentInvoicePdf(year, term, document, draft, true)
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
            lifecycleService.requireDocumentsMutable(getSettings(year))
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
            .firstOrNull { it.musician == null && it.teacher == null && it.status !in setOf(GeneratedDocumentStatus.CANCELLED, GeneratedDocumentStatus.CREDITED) }
            ?: GeneratedDocumentEntity().apply {
                this.term = term
                this.type = GeneratedDocumentType.STUDENT_INVOICE
                this.status = GeneratedDocumentStatus.DRAFT
            }
        if (document.status != GeneratedDocumentStatus.DRAFT) {
            return document
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
        generateStudentInvoiceSummaryPdf(year, term, document, drafts, musiciansById, total, true)
        if (document.id == null) {
            generatedDocumentRepository.persist(document).awaitSuspending()
        }
        return document
    }

    suspend fun prepareTeacherInvoiceRequests(year: Int, termId: UUID): List<GeneratedDocument> =
        panacheCoroutine.withTransaction {
            lifecycleService.requireDocumentsMutable(getSettings(year))
            prepareTeacherInvoiceRequestsInTransaction(year, termId)
        }

    private suspend fun prepareTeacherInvoiceRequestsInTransaction(year: Int, termId: UUID): List<GeneratedDocument> {
        val term = getTerm(year, termId)
        val existingDocuments = generatedDocumentRepository
            .listByTermAndType(termId, GeneratedDocumentType.TEACHER_INVOICE_REQUEST)
            .awaitSuspending()
        val billing = getTermBillingInSession(year, termId)
        val documentsByTeacher = existingDocuments
            .filter { it.status !in setOf(GeneratedDocumentStatus.CANCELLED, GeneratedDocumentStatus.CREDITED) }
            .mapNotNull { document ->
            document.teacher?.id?.let { it to document }
        }.toMap()
        val documents = billing.teacherInvoiceRequests
            .filter { it.totalAmount > BigDecimal.ZERO }
            .map { draft ->
                val existingDocument = documentsByTeacher[draft.teacherId]
                if (existingDocument != null && existingDocument.status != GeneratedDocumentStatus.DRAFT) {
                    return@map existingDocument
                }
                val document = existingDocument ?: GeneratedDocumentEntity().apply {
                    this.term = term
                    this.type = GeneratedDocumentType.TEACHER_INVOICE_REQUEST
                    this.status = GeneratedDocumentStatus.DRAFT
                    this.teacher = teacherRepository.findById(draft.teacherId).awaitSuspending()
                }
                document.totalAmount = draft.totalAmount
                generateTeacherInvoiceRequestPdf(year, term, document, draft, true)
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

    suspend fun finalizeStudentInvoices(year: Int, termId: UUID): List<GeneratedDocument> =
        panacheCoroutine.withTransaction {
            lifecycleService.requireDocumentsMutable(getSettings(year))
            prepareStudentInvoicesInTransaction(year, termId)
            val term = getTerm(year, termId)
            val billing = getTermBillingInSession(year, termId)
            val draftsByMusician = billing.studentInvoices.associateBy { it.musicianId }
            val documents = generatedDocumentRepository
                .listByTermAndType(termId, GeneratedDocumentType.STUDENT_INVOICE)
                .awaitSuspending()
            val musiciansById = if (draftsByMusician.isEmpty()) emptyMap() else
                musicianRepository.find("id in ?1", draftsByMusician.keys.toList())
                    .list().awaitSuspending().mapNotNull { musician -> musician.id?.let { it to musician } }.toMap()
            documents.filter { it.status == GeneratedDocumentStatus.DRAFT }.forEach { document ->
                val musicianId = document.musician?.id
                if (musicianId != null) {
                    draftsByMusician[musicianId]?.let { generateStudentInvoicePdf(year, term, document, it, false) }
                } else {
                    val drafts = billing.studentInvoices.filter { it.totalAmount > BigDecimal.ZERO }
                    val total = drafts.fold(BigDecimal.ZERO) { sum, draft -> sum.add(draft.totalAmount) }.toMoney()
                    generateStudentInvoiceSummaryPdf(year, term, document, drafts, musiciansById, total, false)
                }
            }
            documents.filter { it.musician != null }.map { mapper.run { it.toDto() } }
        }

    suspend fun finalizeTeacherInvoiceRequests(year: Int, termId: UUID): List<GeneratedDocument> =
        panacheCoroutine.withTransaction {
            lifecycleService.requireDocumentsMutable(getSettings(year))
            prepareTeacherInvoiceRequestsInTransaction(year, termId)
            val term = getTerm(year, termId)
            val draftsByTeacher = getTermBillingInSession(year, termId).teacherInvoiceRequests.associateBy { it.teacherId }
            val documents = generatedDocumentRepository
                .listByTermAndType(termId, GeneratedDocumentType.TEACHER_INVOICE_REQUEST)
                .awaitSuspending()
            documents.filter { it.status == GeneratedDocumentStatus.DRAFT }.forEach { document ->
                document.teacher?.id?.let { teacherId ->
                    draftsByTeacher[teacherId]?.let { generateTeacherInvoiceRequestPdf(year, term, document, it, false) }
                }
            }
            documents.map { mapper.run { it.toDto() } }
        }

    private fun generateStudentInvoicePdf(
        year: Int,
        term: TermEntity,
        document: GeneratedDocumentEntity,
        draft: StudentInvoiceDraft,
        isDraft: Boolean,
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
                isDraft = isDraft,
            ),
        )
        document.metadata = immutableSnapshot(
            year = year,
            term = term,
            documentKind = "STUDENT_INVOICE",
            owner = mapOf(
                "id" to musician.id,
                "firstName" to musician.firstName,
                "lastName" to musician.lastName,
                "email" to musician.email,
            ),
            lines = draft,
        )
        document.status = if (isDraft) GeneratedDocumentStatus.DRAFT else GeneratedDocumentStatus.GENERATED
    }

    private fun generateStudentInvoiceSummaryPdf(
        year: Int,
        term: TermEntity,
        document: GeneratedDocumentEntity,
        drafts: List<StudentInvoiceDraft>,
        musiciansById: Map<UUID, MusicianEntity>,
        total: BigDecimal,
        isDraft: Boolean,
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
                isDraft = isDraft,
            ),
        )
        document.metadata = immutableSnapshot(
            year = year,
            term = term,
            documentKind = "STUDENT_INVOICE_SUMMARY",
            owner = mapOf("scope" to "ALL_STUDENTS"),
            lines = mapOf(
                "drafts" to drafts,
                "musicians" to musiciansById.mapValues { (_, musician) ->
                    mapOf("firstName" to musician.firstName, "lastName" to musician.lastName, "email" to musician.email)
                },
                "total" to total,
            ),
        )
        document.status = if (isDraft) GeneratedDocumentStatus.DRAFT else GeneratedDocumentStatus.GENERATED
    }

    private fun generateTeacherInvoiceRequestPdf(
        year: Int,
        term: TermEntity,
        document: GeneratedDocumentEntity,
        draft: TeacherInvoiceRequestDraft,
        isDraft: Boolean,
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
                isDraft = isDraft,
            ),
        )
        document.metadata = immutableSnapshot(
            year = year,
            term = term,
            documentKind = "TEACHER_INVOICE_REQUEST",
            owner = mapOf(
                "id" to teacher.id,
                "firstName" to teacher.firstName,
                "lastName" to teacher.lastName,
                "instrument" to teacher.instrument,
            ),
            lines = draft,
        )
        document.status = if (isDraft) GeneratedDocumentStatus.DRAFT else GeneratedDocumentStatus.GENERATED
    }

    private fun immutableSnapshot(
        year: Int,
        term: TermEntity,
        documentKind: String,
        owner: Any,
        lines: Any,
    ) = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
        mapOf(
            "schemaVersion" to 1,
            "capturedAt" to java.time.Instant.now().toString(),
            "documentKind" to documentKind,
            "year" to year,
            "term" to mapOf(
                "id" to term.id,
                "name" to term.name,
                "startWeek" to term.startWeek,
                "endWeek" to term.endWeek,
                "displayOrder" to term.displayOrder,
            ),
            "rates" to mapOf(
                "currency" to "EUR",
                "teacherHourlyRate" to term.settings.teacherHourlyRate,
                "groupMembershipFee" to term.settings.groupMembershipFee,
                "individualCourseHours" to term.settings.individualCourseHours,
                "workshopHours" to term.settings.workshopHours,
                "rounding" to "HALF_UP",
                "moneyScale" to 2,
            ),
            "owner" to owner,
            "lines" to lines,
        ),
    )

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

    suspend fun updateStatus(year: Int, update: AccountingYearStatusUpdate): Settings =
        panacheCoroutine.withTransaction {
            val entity = getSettings(year)
            val target = AccountingYearStatusEntity.valueOf(update.status.name)
            lifecycleService.validateTransition(entity.status, target)
            if (target == AccountingYearStatusEntity.CLOSED) {
                val draftCount = generatedDocumentRepository
                    .countByYearAndStatus(year, GeneratedDocumentStatus.DRAFT)
                    .awaitSuspending()
                if (draftCount > 0) {
                    throw ClientErrorException(
                        "Accounting year $year cannot be closed while $draftCount document drafts remain.",
                        Response.Status.CONFLICT,
                    )
                }
            }
            entity.status = target
            when (target) {
                AccountingYearStatusEntity.OPEN -> {
                    entity.reviewedAt = null
                    entity.closedAt = null
                }
                AccountingYearStatusEntity.REVIEWED -> {
                    entity.reviewedAt = java.time.Instant.now()
                    entity.closedAt = null
                }
                AccountingYearStatusEntity.CLOSED -> entity.closedAt = java.time.Instant.now()
            }
            auditService.record("ACCOUNTING_YEAR", entity.id, "STATUS_CHANGED", mapOf("year" to year, "status" to target.name))
            accountingYearSettingsRepository.flush()
            mapper.run { entity.toDto() }.copy(version = entity.version + 1)
        }

    private suspend fun updateSettingsInTransaction(year: Int, settings: Settings): Settings {
        if (settings.year != year) {
            throw BadRequestException("Path year $year does not match payload year ${settings.year}.")
        }

        val existingEntity = accountingYearSettingsRepository.findByYearWithTerms(year).awaitSuspending()
        val entity = existingEntity
            ?: AccountingYearSettingsEntity().also {
                it.year = year
                accountingYearSettingsRepository.persist(it).awaitSuspending()
            }
        if (existingEntity != null) OptimisticLocking.requireVersion(settings.version, entity.version, "Accounting year $year settings")
        lifecycleService.requireStructureMutable(entity)

        entity.teacherHourlyRate = settings.teacherHourlyRate
        entity.groupMembershipFee = settings.groupMembershipFee
        entity.individualCourseHours = settings.individualCourseHours ?: entity.individualCourseHours
        entity.workshopHours = settings.workshopHours ?: entity.workshopHours
        validateDurations(entity.individualCourseHours, entity.workshopHours)
        entity.schoolHolidayWeeks = mapOf("weeks" to settings.schoolHolidayWeeks.sorted())

        val existingTermsById = entity.terms.mapNotNull { term -> term.id?.let { it to term } }.toMap()
        val requestedTermIds = settings.terms.mapNotNull { it.id }.toSet()
        existingTermsById.keys.filterNot { it in requestedTermIds }.forEach { removedTermId ->
            val attendanceCount = attendanceRepository.count("term.id", removedTermId).awaitSuspending()
            val documentCount = generatedDocumentRepository.count("term.id", removedTermId).awaitSuspending()
            if (attendanceCount > 0 || documentCount > 0) {
                throw jakarta.ws.rs.ClientErrorException(
                    "Term $removedTermId cannot be removed because it has attendance or generated documents.",
                    jakarta.ws.rs.core.Response.Status.CONFLICT,
                )
            }
        }
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

        auditService.record("ACCOUNTING_YEAR", entity.id, "SETTINGS_UPDATED", mapOf("year" to year))
        accountingYearSettingsRepository.flush()

        return mapper.run { entity.toDto() }.copy(version = if (existingEntity == null) entity.version else entity.version + 1)
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

    private fun BigDecimal.toMoney() = BillingCalculator.run { toMoney() }

    private fun BigDecimal.toHours() = BillingCalculator.run { toHours() }

    private fun validateDurations(individual: BigDecimal, workshop: BigDecimal) {
        if (individual <= BigDecimal.ZERO || individual > BigDecimal("24.00") ||
            workshop <= BigDecimal.ZERO || workshop > BigDecimal("24.00")) {
            throw BadRequestException("Billing durations must be greater than 0 and at most 24 hours.")
        }
    }


}
