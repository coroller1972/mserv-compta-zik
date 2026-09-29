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
        join fetch course.settings
        join fetch course.musician
        join fetch course.teacher
        """.trimIndent(),
    ).list()

    fun listWithRelationsByYear(year: Int) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.settings settings
        join fetch course.musician
        join fetch course.teacher
        where settings.year = ?1
        """.trimIndent(),
        year,
    ).list()

    fun findByIdWithRelations(id: UUID) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.settings
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

    fun listOccupyingBySlot(year: Int, weekday: String, startTime: LocalTime) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.settings settings
        join fetch course.musician musician
        join fetch course.teacher
        where settings.year = ?1
        and course.weekday = ?2
        and course.startTime = ?3
        and course.active = true
        and musician.active = true
        """.trimIndent(),
        year,
        weekday,
        startTime,
    ).list()

    /** Cours qui occupent la salle un jour donné (actifs, élève actif). */
    fun listOccupyingByDay(year: Int, weekday: String) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.settings settings
        join fetch course.musician musician
        join fetch course.teacher
        where settings.year = ?1
        and course.weekday = ?2
        and course.active = true
        and musician.active = true
        """.trimIndent(),
        year,
        weekday,
    ).list()

    fun listByMusician(musicianId: UUID) = find(
        """
        select course
        from IndividualCourseEntity course
        join fetch course.settings
        join fetch course.musician
        join fetch course.teacher
        where course.musician.id = ?1
        """.trimIndent(),
        musicianId,
    ).list()
}
