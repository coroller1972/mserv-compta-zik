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
import fr.spiritbox.compta.domain.AttendanceEntity
import fr.spiritbox.compta.domain.AttendanceStatus
import fr.spiritbox.compta.domain.TeacherInvoiceAttendanceAllocationEntity
import fr.spiritbox.compta.openapi.model.AccountingYearSnapshot
import fr.spiritbox.compta.openapi.model.AccountingYearStatusUpdate
import fr.spiritbox.compta.openapi.model.BillingSummary
import fr.spiritbox.compta.openapi.model.BillingTotals
import fr.spiritbox.compta.openapi.model.GeneratedDocument
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.openapi.model.StudentInvoiceDraft
import fr.spiritbox.compta.openapi.model.TeacherInvoiceRequestDraft
import fr.spiritbox.compta.openapi.model.TeacherInvoiceWeeklyLine
import fr.spiritbox.compta.openapi.model.TeacherInvoicePrepareRequest
import fr.spiritbox.compta.openapi.model.TeacherInvoiceFinalizeRequest
import fr.spiritbox.compta.openapi.model.TeacherBillingAdjustment
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
import fr.spiritbox.compta.repositories.TeacherInvoiceAttendanceAllocationRepository
import fr.spiritbox.compta.repositories.TeacherBillingAdjustmentRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
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
    private val allocationRepository: TeacherInvoiceAttendanceAllocationRepository,
    private val adjustmentRepository: TeacherBillingAdjustmentRepository,
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

        val attendanceIds = presentAttendance.mapNotNull { it.id }
        val activeAllocations = if (attendanceIds.isEmpty()) emptyList() else allocationRepository
            .listActiveByAttendanceIds(attendanceIds).awaitSuspending()
        val allocatedAttendanceIds = activeAllocations.mapNotNull { it.attendance.id }.toSet()
        val teacherDocuments = generatedDocumentRepository
            .listByTermAndType(termId, GeneratedDocumentType.TEACHER_INVOICE_REQUEST).awaitSuspending()
        val legacyInstallmentsBlocked = teacherDocuments.any {
            it.status in setOf(GeneratedDocumentStatus.GENERATED, GeneratedDocumentStatus.SENT, GeneratedDocumentStatus.CREDITED) &&
                it.periodEndWeek == null
        }

        val teacherRequests = teachers.mapNotNull { teacher ->
            val teacherId = teacher.id ?: return@mapNotNull null
            val hoursBySession = mutableMapOf<Pair<Int, java.time.LocalDate>, BigDecimal>()

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
                .entries
                .groupBy { it.key.first }
                .toSortedMap()
                .map { (week, sessions) ->
                    val hours = sessions.fold(BigDecimal.ZERO) { total, session -> total.add(session.value) }
                    val amount = sessions.fold(BigDecimal.ZERO) { total, session ->
                        total.add(session.value.multiply(settings.teacherHourlyRate).toMoney())
                    }
                    TeacherInvoiceWeeklyLine(
                        week = week,
                        date = SessionDateCalculator.scheduledDate(year, week, "Lundi"),
                        hours = hours.toHours(),
                        amount = amount.toMoney(),
                    )
                }
            val pendingAdjustments = adjustmentRepository.listPendingByTeacher(teacherId).awaitSuspending()
            if (weeklyLines.isEmpty() && pendingAdjustments.isEmpty()) {
                null
            } else {
                val totalHours = weeklyLines.fold(BigDecimal.ZERO) { total, line -> total.add(line.hours) }.toHours()
                val totalAmount = weeklyLines.fold(BigDecimal.ZERO) { total, line -> total.add(line.amount) }.toMoney()

                val issuedAmount = teacherDocuments.filter {
                    it.teacher?.id == teacherId && it.periodEndWeek != null &&
                        it.status in setOf(GeneratedDocumentStatus.GENERATED, GeneratedDocumentStatus.SENT)
                }.fold(BigDecimal.ZERO) { sum, document -> sum.add(document.totalAmount) }.toMoney()
                val pendingAdjustmentAmount = pendingAdjustments.fold(BigDecimal.ZERO) { sum, adjustment -> sum.add(adjustment.amount) }.toMoney()
                val unallocatedAmount = teacherAttendanceAmount(
                    teacherId, presentAttendance.filter { it.id !in allocatedAttendanceIds }, individualCourses, bands, settings
                )

                TeacherInvoiceRequestDraft(
                    teacherId = teacherId,
                    weeklyLines = weeklyLines,
                    totalHours = totalHours,
                    totalAmount = totalAmount,
                    accruedAmount = totalAmount,
                    issuedAmount = issuedAmount,
                    pendingAdjustmentAmount = pendingAdjustmentAmount,
                    remainingAmount = unallocatedAmount.add(pendingAdjustmentAmount).toMoney(),
                    installmentsAvailable = !legacyInstallmentsBlocked,
                    adjustments = pendingAdjustments.map { it.toDto() },
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
        val attendance = attendanceRepository.listByAccountingYearAndTerm(year, termId).awaitSuspending()
        val ids = attendance.mapNotNull { it.id }
        val locks = if (ids.isEmpty()) emptyMap() else allocationRepository.listActiveByAttendanceIds(ids)
            .awaitSuspending().associateBy { it.attendance.id }
        attendance.map { entity ->
            val allocation = locks[entity.id]
            mapper.run { entity.toDto() }.copy(
                billingLocked = allocation != null,
                billingDocumentId = allocation?.document?.id,
                billingDocumentNumber = allocation?.document?.documentNumber,
            )
        }
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

    suspend fun prepareTeacherInvoiceRequest(year: Int, termId: UUID, teacherId: UUID, request: TeacherInvoicePrepareRequest): GeneratedDocument =
        panacheCoroutine.withTransaction {
            lifecycleService.requireDocumentsMutable(getSettings(year))
            val document = prepareTeacherInvoiceRequestInTransaction(year, termId, teacherId, request.endWeek)
            generatedDocumentRepository.flush()
            mapper.run { document.toDto() }.copy(version = document.version + 1)
        }

    private suspend fun prepareTeacherInvoiceRequestInTransaction(
        year: Int,
        termId: UUID,
        teacherId: UUID,
        endWeek: Int,
    ): GeneratedDocumentEntity {
        val term = getTerm(year, termId)
        if (endWeek !in term.startWeek..term.endWeek) throw BadRequestException("Week $endWeek is outside term ${term.startWeek}-${term.endWeek}.")
        val documents = generatedDocumentRepository
            .listByTermAndType(termId, GeneratedDocumentType.TEACHER_INVOICE_REQUEST)
            .awaitSuspending()
        if (documents.any { it.periodEndWeek == null && it.status in setOf(GeneratedDocumentStatus.GENERATED, GeneratedDocumentStatus.SENT, GeneratedDocumentStatus.CREDITED) }) {
            conflict("This term already contains historical teacher requests. Installments are available from the next term.")
        }
        val settings = getSettings(year)
        val courses = individualCourseRepository.listWithRelationsByYear(year).awaitSuspending()
        val bands = bandRepository.listWithMembersByYear(year).awaitSuspending()
        val attendance = attendanceRepository.listByAccountingYearAndTerm(year, termId).awaitSuspending()
            .filter { it.status == AttendanceStatus.PRESENT && it.week <= endWeek && attendanceBelongsToTeacher(it, teacherId, courses, bands) }
        val ids = attendance.mapNotNull { it.id }
        val allocated = if (ids.isEmpty()) emptySet() else allocationRepository.listActiveByAttendanceIds(ids)
            .awaitSuspending().mapNotNull { it.attendance.id }.toSet()
        val selected = attendance.filter { it.id !in allocated }
        val adjustments = adjustmentRepository.listPendingByTeacher(teacherId).awaitSuspending()
        val draft = teacherDraft(teacherId, selected, courses, bands, settings, adjustments.map { it.toDto() })
        if (draft.totalAmount <= BigDecimal.ZERO) throw BadRequestException("A teacher request with a zero or negative total cannot be prepared.")

        val existingDocument = documents.firstOrNull { it.teacher?.id == teacherId && it.status == GeneratedDocumentStatus.DRAFT }
        val document = existingDocument ?: GeneratedDocumentEntity().apply {
            this.term = term
            this.type = GeneratedDocumentType.TEACHER_INVOICE_REQUEST
            this.status = GeneratedDocumentStatus.DRAFT
            this.teacher = teacherRepository.findById(teacherId).awaitSuspending()
        }
        val priorNumbers = documents.filter { it.teacher?.id == teacherId && it.installmentNumber != null }.mapNotNull { it.installmentNumber }
        document.installmentNumber = (priorNumbers.maxOrNull() ?: 0) + 1
        document.periodStartWeek = selected.minOfOrNull { it.week } ?: endWeek
        document.periodEndWeek = endWeek
        document.documentNumber = buildDocumentNumber("DFP", year, term, teacherId) + "-${document.installmentNumber.toString().padStart(2, '0')}"
        document.totalAmount = draft.totalAmount
        generateTeacherInvoiceRequestPdf(year, term, document, draft, true)
        document.metadata = teacherInstallmentSnapshot(year, term, document, draft, selected, adjustments.map { it.toDto() })
        if (document.id == null) {
            generatedDocumentRepository.persist(document).awaitSuspending()
        }
        return document
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

    suspend fun finalizeTeacherInvoiceRequest(year: Int, termId: UUID, teacherId: UUID, request: TeacherInvoiceFinalizeRequest): GeneratedDocument =
        panacheCoroutine.withTransaction {
            lifecycleService.requireDocumentsMutable(getSettings(year))
            val document = generatedDocumentRepository.findByIdWithRelations(request.documentId).awaitSuspending()
                ?: throw NotFoundException("Draft ${request.documentId} was not found.")
            if (document.term.id != termId || document.teacher?.id != teacherId || document.status != GeneratedDocumentStatus.DRAFT) conflict("The selected draft is no longer current.")
            OptimisticLocking.requireVersion(request.version, document.version, "Teacher invoice request")
            val snapshot = document.metadata ?: conflict("The draft has no accounting snapshot.")
            val attendanceNodes = snapshot.path("attendance")
            val attendanceIds = attendanceNodes.map { UUID.fromString(it.path("id").asText()) }
            val current = if (attendanceIds.isEmpty()) emptyList() else attendanceRepository.find("id in ?1", attendanceIds).list().awaitSuspending()
            val expected = attendanceNodes.associate { UUID.fromString(it.path("id").asText()) to it.path("version").asLong() }
            if (current.size != expected.size || current.any { it.status != AttendanceStatus.PRESENT || expected[it.id] != it.version }) conflict("Attendance changed since preview. Regenerate the request before validating it.")
            if (attendanceIds.isNotEmpty() && allocationRepository.listActiveByAttendanceIds(attendanceIds).awaitSuspending().isNotEmpty()) conflict("Some attendance has already been included in another request.")
            val adjustmentIds = snapshot.path("adjustments").map { UUID.fromString(it.path("id").asText()) }
            val adjustments = adjustmentIds.map { id -> adjustmentRepository.findByIdWithRelations(id).awaitSuspending() ?: conflict("An adjustment changed since preview.") }
            if (adjustments.any { it.appliedDocument != null }) conflict("An adjustment changed since preview.")
            val term = getTerm(year, termId)
            val draft = objectMapper.treeToValue(snapshot.path("draft"), TeacherInvoiceRequestDraft::class.java)
            val currentFingerprint = teacherInstallmentSnapshot(year, term, document, draft, current, adjustments.map { it.toDto() }).path("fingerprint").asText()
            if (snapshot.path("fingerprint").asText() != currentFingerprint) conflict("Accounting data changed since preview. Regenerate the request before validating it.")
            generateTeacherInvoiceRequestPdf(year, term, document, draft, false)
            document.metadata = snapshot
            current.forEach { attendanceEntity -> allocationRepository.persist(TeacherInvoiceAttendanceAllocationEntity().apply { this.document = document; attendance = attendanceEntity }).awaitSuspending() }
            adjustments.forEach { it.appliedDocument = document; it.appliedAt = java.time.Instant.now() }
            auditService.record("DOCUMENT", document.id, "TEACHER_INSTALLMENT_VALIDATED", mapOf("number" to document.documentNumber, "attendanceCount" to current.size))
            generatedDocumentRepository.flush()
            mapper.run { document.toDto() }
        }

    private fun teacherInstallmentSnapshot(year: Int, term: TermEntity, document: GeneratedDocumentEntity, draft: TeacherInvoiceRequestDraft, attendance: List<AttendanceEntity>, adjustments: List<TeacherBillingAdjustment>): com.fasterxml.jackson.databind.JsonNode {
        val attendanceLines = attendance.sortedWith(compareBy<AttendanceEntity> { it.week }.thenBy { it.id.toString() }).map {
            val hours = if (it.entityType == AttendanceEntityType.INDIVIDUAL_COURSE) term.settings.individualCourseHours else term.settings.workshopHours
            mapOf("id" to it.id, "version" to it.version, "week" to it.week, "sessionDate" to it.sessionDate, "entityType" to it.entityType, "entityId" to it.entityId,
                "hours" to hours.toHours(), "amount" to hours.multiply(term.settings.teacherHourlyRate).toMoney())
        }
        val sortedAdjustments = adjustments.sortedBy { it.id.toString() }
        val fingerprintPayload = mapOf("attendance" to attendanceLines, "adjustments" to sortedAdjustments)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(objectMapper.writeValueAsBytes(fingerprintPayload)).joinToString("") { "%02x".format(it) }
        return objectMapper.valueToTree(mapOf(
            "schemaVersion" to 2, "capturedAt" to java.time.Instant.now().toString(), "year" to year,
            "termId" to term.id, "installmentNumber" to document.installmentNumber, "periodStartWeek" to document.periodStartWeek,
            "periodEndWeek" to document.periodEndWeek, "fingerprint" to fingerprint, "draft" to draft,
            "attendance" to attendanceLines,
            "adjustments" to sortedAdjustments,
        ))
    }

    private fun attendanceBelongsToTeacher(entry: AttendanceEntity, teacherId: UUID, courses: List<IndividualCourseEntity>, bands: List<BandEntity>): Boolean = when (entry.entityType) {
        AttendanceEntityType.INDIVIDUAL_COURSE -> courses.any { it.id == entry.entityId && it.teacher.id == teacherId }
        AttendanceEntityType.WORKSHOP -> bands.any { it.id == entry.entityId && it.type == BandType.WORKSHOP && it.teacher?.id == teacherId }
    }

    private fun teacherAttendanceAmount(teacherId: UUID, attendance: List<AttendanceEntity>, courses: List<IndividualCourseEntity>, bands: List<BandEntity>, settings: AccountingYearSettingsEntity) =
        attendance.filter { attendanceBelongsToTeacher(it, teacherId, courses, bands) }.fold(BigDecimal.ZERO) { sum, entry ->
            sum.add((if (entry.entityType == AttendanceEntityType.INDIVIDUAL_COURSE) settings.individualCourseHours else settings.workshopHours).multiply(settings.teacherHourlyRate).toMoney())
        }.toMoney()

    private fun teacherDraft(teacherId: UUID, attendance: List<AttendanceEntity>, courses: List<IndividualCourseEntity>, bands: List<BandEntity>, settings: AccountingYearSettingsEntity, adjustments: List<TeacherBillingAdjustment>): TeacherInvoiceRequestDraft {
        val weekly = attendance.groupBy { it.week }.toSortedMap().map { (week, entries) ->
            val hours = entries.fold(BigDecimal.ZERO) { sum, entry -> sum.add(if (entry.entityType == AttendanceEntityType.INDIVIDUAL_COURSE) settings.individualCourseHours else settings.workshopHours) }.toHours()
            TeacherInvoiceWeeklyLine(week, SessionDateCalculator.scheduledDate(settings.year, week, "Lundi"), hours, hours.multiply(settings.teacherHourlyRate).toMoney())
        }
        val totalHours = weekly.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.hours) }.toHours()
        val attendanceAmount = weekly.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.amount) }.toMoney()
        val adjustmentAmount = adjustments.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.amount) }.toMoney()
        val total = attendanceAmount.add(adjustmentAmount).toMoney()
        return TeacherInvoiceRequestDraft(teacherId, weekly, totalHours, total, attendanceAmount, BigDecimal.ZERO, adjustmentAmount, total, true, adjustments)
    }

    private fun fr.spiritbox.compta.domain.TeacherBillingAdjustmentEntity.toDto() = TeacherBillingAdjustment(
        id = requireNotNull(id), teacherId = requireNotNull(teacher.id), sourceDocumentId = requireNotNull(sourceDocument.id),
        sourceDocumentNumber = sourceDocument.documentNumber, appliedDocumentId = appliedDocument?.id, amount = amount, reason = reason,
    )

    private fun conflict(message: String): Nothing = throw ClientErrorException(message, Response.Status.CONFLICT)

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
                installmentNumber = document.installmentNumber,
                periodStartWeek = document.periodStartWeek,
                periodEndWeek = document.periodEndWeek,
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
