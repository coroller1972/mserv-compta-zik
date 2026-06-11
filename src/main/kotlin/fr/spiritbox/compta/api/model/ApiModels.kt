package fr.spiritbox.compta.api.model

import com.fasterxml.jackson.annotation.JsonInclude
import org.eclipse.microprofile.openapi.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalTime
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "AccountingYearSnapshot")
data class AccountingYearSnapshotDto(
    val settings: SettingsDto,
    val teachers: List<TeacherDto> = emptyList(),
    val musicians: List<MusicianDto> = emptyList(),
    val bands: List<BandDto> = emptyList(),
    val individualCourses: List<IndividualCourseDto> = emptyList(),
    val attendance: List<AttendanceDto> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Settings")
data class SettingsDto(
    val year: Int,
    val teacherHourlyRate: BigDecimal,
    val groupMembershipFee: BigDecimal,
    val schoolHolidayWeeks: List<Int> = emptyList(),
    val terms: List<TermDto> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Term")
data class TermDto(
    val id: UUID? = null,
    val name: String,
    val startWeek: Int,
    val endWeek: Int,
    val displayOrder: Int = 0,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Teacher")
data class TeacherDto(
    val id: UUID? = null,
    val firstName: String,
    val lastName: String,
    val instrument: String,
    val active: Boolean = true,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Musician")
data class MusicianDto(
    val id: UUID? = null,
    val firstName: String,
    val lastName: String,
    val email: String? = null,
    val active: Boolean = true,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Band")
data class BandDto(
    val id: UUID? = null,
    val name: String,
    val type: BandTypeDto,
    val teacherId: UUID? = null,
    val weekday: String? = null,
    val memberIds: List<UUID> = emptyList(),
)

enum class BandTypeDto {
    INDEPENDENT,
    WORKSHOP,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "IndividualCourse")
data class IndividualCourseDto(
    val id: UUID? = null,
    val musicianId: UUID,
    val teacherId: UUID,
    val instrument: String,
    val weekday: String,
    val startTime: LocalTime,
    val active: Boolean = true,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Attendance")
data class AttendanceDto(
    val id: UUID? = null,
    val termId: UUID,
    val week: Int,
    val entityType: AttendanceEntityTypeDto,
    val entityId: UUID,
    val present: Boolean,
)

enum class AttendanceEntityTypeDto {
    INDIVIDUAL_COURSE,
    WORKSHOP,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "BillingSummary")
data class BillingSummaryDto(
    val termId: UUID,
    val studentInvoices: List<StudentInvoiceDraftDto> = emptyList(),
    val teacherInvoiceRequests: List<TeacherInvoiceRequestDraftDto> = emptyList(),
    val totals: BillingTotalsDto,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "StudentInvoiceDraft")
data class StudentInvoiceDraftDto(
    val musicianId: UUID,
    val individualCourseCount: Int,
    val individualCourseAmount: BigDecimal,
    val groupMembershipAmount: BigDecimal,
    val totalAmount: BigDecimal,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "TeacherInvoiceRequestDraft")
data class TeacherInvoiceRequestDraftDto(
    val teacherId: UUID,
    val weeklyLines: List<TeacherInvoiceWeeklyLineDto> = emptyList(),
    val totalHours: BigDecimal,
    val totalAmount: BigDecimal,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "TeacherInvoiceWeeklyLine")
data class TeacherInvoiceWeeklyLineDto(
    val week: Int,
    val date: String,
    val hours: BigDecimal,
    val amount: BigDecimal,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "BillingTotals")
data class BillingTotalsDto(
    val studentBilling: BigDecimal,
    val groupFees: BigDecimal,
    val teacherDue: BigDecimal,
    val subsidy: BigDecimal,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "BandMembers")
data class BandMembersDto(
    val memberIds: List<UUID> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "GeneratedDocument")
data class GeneratedDocumentDto(
    val id: UUID? = null,
    val termId: UUID,
    val type: GeneratedDocumentTypeDto,
    val status: GeneratedDocumentStatusDto,
    val musicianId: UUID? = null,
    val teacherId: UUID? = null,
    val documentNumber: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val totalAmount: BigDecimal,
)

enum class GeneratedDocumentTypeDto {
    STUDENT_INVOICE,
    TEACHER_INVOICE_REQUEST,
}

enum class GeneratedDocumentStatusDto {
    DRAFT,
    GENERATED,
    SENT,
    CANCELLED,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ErrorResponse")
data class ErrorResponseDto(
    val message: String,
)
