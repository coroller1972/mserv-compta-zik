package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(
    name = "individual_course",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_individual_course_musician", columnNames = ["musician_id"]),
    ],
    indexes = [
        Index(name = "idx_individual_course_teacher", columnList = "teacher_id"),
        Index(name = "idx_individual_course_musician", columnList = "musician_id"),
    ],
)
open class IndividualCourseEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "musician_id", nullable = false)
    open lateinit var musician: MusicianEntity

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    open lateinit var teacher: TeacherEntity

    @Column(name = "instrument", nullable = false, length = 120)
    open var instrument: String = ""

    @Column(name = "weekday", nullable = false, length = 30)
    open var weekday: String = ""

    @Column(name = "start_time", nullable = false)
    open var startTime: LocalTime = LocalTime.MIDNIGHT

    @Column(name = "shared_slot", nullable = false)
    open var sharedSlot: Boolean = false

    @Column(name = "active", nullable = false)
    open var active: Boolean = true
}
