package fr.spiritbox.compta

import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.AttendanceEntity
import fr.spiritbox.compta.domain.BandEntity
import fr.spiritbox.compta.domain.ExpenseEntity
import fr.spiritbox.compta.domain.GeneratedDocumentEntity
import fr.spiritbox.compta.domain.IndividualCourseEntity
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.AccountingYearSnapshot
import fr.spiritbox.compta.openapi.model.AccountingYearStatus
import fr.spiritbox.compta.openapi.model.Attendance
import fr.spiritbox.compta.openapi.model.AttendanceEntityType
import fr.spiritbox.compta.openapi.model.Band
import fr.spiritbox.compta.openapi.model.BandType
import fr.spiritbox.compta.openapi.model.Expense
import fr.spiritbox.compta.openapi.model.ExpenseCategory
import fr.spiritbox.compta.openapi.model.GeneratedDocument
import fr.spiritbox.compta.openapi.model.GeneratedDocumentStatus
import fr.spiritbox.compta.openapi.model.GeneratedDocumentType
import fr.spiritbox.compta.openapi.model.IndividualCourse
import fr.spiritbox.compta.openapi.model.Musician
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.openapi.model.Teacher
import fr.spiritbox.compta.openapi.model.Term
import fr.spiritbox.compta.security.SnapshotAccessPolicy
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class Mapper(private val snapshotAccess: SnapshotAccessPolicy) {
    fun toSnapshotDto(
        settings: AccountingYearSettingsEntity,
        teachers: List<TeacherEntity>,
        musicians: List<MusicianEntity>,
        bands: List<BandEntity>,
        individualCourses: List<IndividualCourseEntity>,
        attendance: List<AttendanceEntity>,
        expenses: List<ExpenseEntity>,
    ) = AccountingYearSnapshot(
        settings = snapshotAccess.settingsView(settings.toDto()),
        teachers = teachers.map { it.toDto() },
        musicians = musicians.map { it.toDto() },
        bands = bands.map { it.toDto() },
        individualCourses = individualCourses.map { it.toDto() },
        attendance = attendance.map { it.toDto() },
        expenses = expenses.map { it.toDto() },
    ).let(snapshotAccess::filter)

    fun TeacherEntity.toDto() = Teacher(
        id = id,
        firstName = firstName,
        lastName = lastName,
        instrument = instrument,
        active = active,
    )

    fun MusicianEntity.toDto() = Musician(
        id = id,
        firstName = firstName,
        lastName = lastName,
        email = email,
        active = active,
    )

    fun AccountingYearSettingsEntity.toDto() = Settings(
        version = version,
        year = year,
        status = AccountingYearStatus.valueOf(status.name),
        reviewedAt = reviewedAt?.atOffset(java.time.ZoneOffset.UTC),
        closedAt = closedAt?.atOffset(java.time.ZoneOffset.UTC),
        teacherHourlyRate = teacherHourlyRate,
        groupMembershipFee = groupMembershipFee,
        individualCourseHours = individualCourseHours,
        workshopHours = workshopHours,
        groupHours = groupHours,
        schoolHolidayWeeks = schoolHolidayWeeks["weeks"].orEmpty().sorted(),
        terms = terms
            .sortedWith(compareBy<TermEntity> { it.displayOrder }.thenBy { it.startWeek })
            .map { it.toDto() },
    )

    fun TermEntity.toDto() = Term(
        id = id,
        name = name,
        startWeek = startWeek,
        endWeek = endWeek,
        displayOrder = displayOrder,
    )

    fun BandEntity.toDto() = Band(
        id = id,
        year = settings.year,
        name = name,
        type = BandType.valueOf(type.name),
        teacherId = teacher?.id,
        weekday = weekday,
        startTime = startTime?.toString(),
        memberIds = members.mapNotNull { it.musician.id },
    )

    fun IndividualCourseEntity.toDto() = IndividualCourse(
        id = id,
        year = settings.year,
        musicianId = requireNotNull(musician.id) { "Cannot map IndividualCourseEntity without persisted musician id." },
        teacherId = requireNotNull(teacher.id) { "Cannot map IndividualCourseEntity without persisted teacher id." },
        instrument = instrument,
        weekday = weekday,
        startTime = startTime.toString(),
        sharedSlot = sharedSlot,
        active = active,
    )

    fun AttendanceEntity.toDto() = Attendance(
        version = version,
        id = id,
        termId = requireNotNull(term.id) { "Cannot map AttendanceEntity without persisted term id." },
        week = week,
        sessionDate = sessionDate,
        entityType = AttendanceEntityType.valueOf(entityType.name),
        entityId = requireNotNull(entityId) { "Cannot map AttendanceEntity without target entity id." },
        present = present,
        status = fr.spiritbox.compta.openapi.model.AttendanceStatus.valueOf(status.name),
    )

    fun ExpenseEntity.toDto() = Expense(
        version = version,
        id = id,
        year = year,
        date = date,
        category = ExpenseCategory.valueOf(category.name),
        label = label,
        amount = amount,
        notes = notes,
    )

    fun GeneratedDocumentEntity.toDto() = GeneratedDocument(
        version = version,
        id = id,
        termId = requireNotNull(term.id) { "Cannot map GeneratedDocumentEntity without persisted term id." },
        type = GeneratedDocumentType.valueOf(type.name),
        status = GeneratedDocumentStatus.valueOf(status.name),
        musicianId = musician?.id,
        teacherId = teacher?.id,
        documentNumber = documentNumber,
        fileName = fileName,
        contentType = contentType,
        totalAmount = totalAmount,
        parentDocumentId = parentDocument?.id,
        correctionReason = correctionReason,
        sentAt = sentAt?.atOffset(java.time.ZoneOffset.UTC),
        cancelledAt = cancelledAt?.atOffset(java.time.ZoneOffset.UTC),
        creditedAt = creditedAt?.atOffset(java.time.ZoneOffset.UTC),
        periodStartWeek = periodStartWeek,
        periodEndWeek = periodEndWeek,
        installmentNumber = installmentNumber,
    )
     

}
