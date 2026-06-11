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
    name = "musician",
    indexes = [
        Index(name = "idx_musician_name", columnList = "last_name, first_name"),
        Index(name = "idx_musician_email", columnList = "email"),
    ],
)
open class MusicianEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @Column(name = "first_name", nullable = false, length = 120)
    open var firstName: String = ""

    @Column(name = "last_name", nullable = false, length = 120)
    open var lastName: String = ""

    @Column(name = "email", length = 255)
    open var email: String? = null

    @Column(name = "active", nullable = false)
    open var active: Boolean = true
}
