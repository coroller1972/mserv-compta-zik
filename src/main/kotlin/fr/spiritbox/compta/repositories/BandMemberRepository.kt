package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.BandMemberEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.*

@ApplicationScoped
class BandMemberRepository : PanacheRepositoryBase<BandMemberEntity, UUID> {
}