package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.IndividualCourseEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalTime
import java.util.*

@ApplicationScoped
class IndividualCourseRepository : PanacheRepositoryBase<IndividualCourseEntity, UUID> {
    fun listWithRelations() = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.musician
        join fetch course.teacher
        """.trimIndent(),
    ).list()

    fun findByIdWithRelations(id: UUID) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.musician
        join fetch course.teacher
        where course.id = ?1
        """.trimIndent(),
        id,
    ).firstResult()

    fun listBySlot(weekday: String, startTime: LocalTime) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.musician musician
        join fetch course.teacher
        where course.weekday = ?1
        and course.startTime = ?2
        """.trimIndent(),
        weekday,
        startTime,
    ).list()

    fun listOccupyingBySlot(weekday: String, startTime: LocalTime) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.musician musician
        join fetch course.teacher
        where course.weekday = ?1
        and course.startTime = ?2
        and course.active = true
        and musician.active = true
        """.trimIndent(),
        weekday,
        startTime,
    ).list()

    fun listByMusician(musicianId: UUID) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.musician
        join fetch course.teacher
        where course.musician.id = ?1
        """.trimIndent(),
        musicianId,
    ).list()
}
