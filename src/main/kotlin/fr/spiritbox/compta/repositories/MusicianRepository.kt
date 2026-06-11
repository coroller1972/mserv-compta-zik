package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.MusicianEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.*

@ApplicationScoped
class MusicianRepository : PanacheRepositoryBase<MusicianEntity, UUID> {
}