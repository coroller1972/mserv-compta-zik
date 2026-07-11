package fr.spiritbox.compta.domain

enum class BandType {
    INDEPENDENT,
    WORKSHOP,
}

enum class AttendanceEntityType {
    INDIVIDUAL_COURSE,
    WORKSHOP,
}

enum class AttendanceStatus {
    PRESENT,
    ABSENT,
    CANCELLED,
}

enum class GeneratedDocumentType {
    STUDENT_INVOICE,
    TEACHER_INVOICE_REQUEST,
    CREDIT_NOTE,
}

enum class GeneratedDocumentStatus {
    DRAFT,
    GENERATED,
    SENT,
    CANCELLED,
    CREDITED,
}

enum class AccountingYearStatus {
    OPEN,
    REVIEWED,
    CLOSED,
}

enum class ExpenseCategory {
    EQUIPMENT,
    INSTRUMENT_REPAIR,
    MAINTENANCE,
    ROOM_RENTAL,
    CONCERT,
    OTHER,
}
