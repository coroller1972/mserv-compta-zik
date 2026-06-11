package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(
    name = "band",
    indexes = [
        Index(name = "idx_band_type", columnList = "type"),
        Index(name = "idx_band_teacher", columnList = "teacher_id"),
    ],
)
open class BandEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @Column(name = "name", nullable = false, length = 160)
    open var name: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    open var type: BandType = BandType.INDEPENDENT

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    open var teacher: TeacherEntity? = null

    @Column(name = "weekday", length = 30)
    open var weekday: String? = null

    @OneToMany(mappedBy = "band")
    open var members: MutableList<BandMemberEntity> = mutableListOf()
}
