package fr.spiritbox.compta

import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.AttendanceEntity
import fr.spiritbox.compta.domain.BandEntity
import fr.spiritbox.compta.domain.GeneratedDocumentEntity
import fr.spiritbox.compta.domain.IndividualCourseEntity
import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.AccountingYearSnapshot
import fr.spiritbox.compta.openapi.model.Attendance
import fr.spiritbox.compta.openapi.model.AttendanceEntityType
import fr.spiritbox.compta.openapi.model.Band
import fr.spiritbox.compta.openapi.model.BandType
import fr.spiritbox.compta.openapi.model.GeneratedDocument
import fr.spiritbox.compta.openapi.model.GeneratedDocumentStatus
import fr.spiritbox.compta.openapi.model.GeneratedDocumentType
import fr.spiritbox.compta.openapi.model.IndividualCourse
import fr.spiritbox.compta.openapi.model.Musician
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.openapi.model.Teacher
import fr.spiritbox.compta.openapi.model.Term
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class Mapper {
    fun toSnapshotDto(
        settings: AccountingYearSettingsEntity,
        teachers: List<TeacherEntity>,
        musicians: List<MusicianEntity>,
        bands: List<BandEntity>,
        individualCourses: List<IndividualCourseEntity>,
        attendance: List<AttendanceEntity>,
    ) = AccountingYearSnapshot(
        settings = settings.toDto(),
        teachers = teachers.map { it.toDto() },
        musicians = musicians.map { it.toDto() },
        bands = bands.map { it.toDto() },
        individualCourses = individualCourses.map { it.toDto() },
        attendance = attendance.map { it.toDto() },
    )

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
        year = year,
        teacherHourlyRate = teacherHourlyRate,
        groupMembershipFee = groupMembershipFee,
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
        name = name,
        type = BandType.valueOf(type.name),
        teacherId = teacher?.id,
        weekday = weekday,
        memberIds = members.mapNotNull { it.musician.id },
    )

    fun IndividualCourseEntity.toDto() = IndividualCourse(
        id = id,
        musicianId = requireNotNull(musician.id) { "Cannot map IndividualCourseEntity without persisted musician id." },
        teacherId = requireNotNull(teacher.id) { "Cannot map IndividualCourseEntity without persisted teacher id." },
        instrument = instrument,
        weekday = weekday,
        startTime = startTime.toString(),
        sharedSlot = sharedSlot,
        active = active,
    )

    fun AttendanceEntity.toDto() = Attendance(
        id = id,
        termId = requireNotNull(term.id) { "Cannot map AttendanceEntity without persisted term id." },
        week = week,
        entityType = AttendanceEntityType.valueOf(entityType.name),
        entityId = requireNotNull(entityId) { "Cannot map AttendanceEntity without target entity id." },
        present = present,
    )

    fun GeneratedDocumentEntity.toDto() = GeneratedDocument(
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
    )
     

}
