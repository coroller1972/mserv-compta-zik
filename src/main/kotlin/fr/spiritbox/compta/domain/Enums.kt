package fr.spiritbox.compta.domain

enum class BandType {
    INDEPENDENT,
    WORKSHOP,
}

enum class AttendanceEntityType {
    INDIVIDUAL_COURSE,
    WORKSHOP,
}

enum class GeneratedDocumentType {
    STUDENT_INVOICE,
    TEACHER_INVOICE_REQUEST,
}

enum class GeneratedDocumentStatus {
    DRAFT,
    GENERATED,
    SENT,
    CANCELLED,
}
