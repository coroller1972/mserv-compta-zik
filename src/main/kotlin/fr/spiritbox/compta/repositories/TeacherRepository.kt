package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.TeacherEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.*

@ApplicationScoped
class TeacherRepository : PanacheRepositoryBase<TeacherEntity, UUID> {
}