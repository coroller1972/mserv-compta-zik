package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(
    name = "teacher",
    indexes = [
        Index(name = "idx_teacher_name", columnList = "last_name, first_name"),
    ],
)
open class TeacherEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @Column(name = "first_name", nullable = false, length = 120)
    open var firstName: String = ""

    @Column(name = "last_name", nullable = false, length = 120)
    open var lastName: String = ""

    @Column(name = "instrument", nullable = false, length = 180)
    open var instrument: String = ""

    @Column(name = "active", nullable = false)
    open var active: Boolean = true
}
