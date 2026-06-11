package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.openapi.model.Teacher
import fr.spiritbox.compta.repositories.TeacherRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.util.UUID

@ApplicationScoped
class TeacherService(
    private val teacherRepository: TeacherRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
) {
    suspend fun createTeacher(teacher: Teacher): Teacher = panacheCoroutine.withTransaction {
        if (teacher.id != null) {
            throw BadRequestException("Teacher id must not be provided when creating a teacher.")
        }

        val entity = TeacherEntity()
        applyPayload(entity, teacher)
        teacherRepository.persist(entity).awaitSuspending()

        mapper.run { entity.toDto() }
    }

    suspend fun deleteTeacher(id: UUID): Unit = panacheCoroutine.withTransaction {
        val deleted = teacherRepository.delete("id", id).awaitSuspending()
        if (deleted == 0L) {
            throw NotFoundException("Teacher $id was not found.")
        }
    }

    suspend fun listTeachers(): List<Teacher> = panacheCoroutine.withSession {
        teacherRepository.find("order by lastName, firstName").list()
            .awaitSuspending()
            .map { mapper.run { it.toDto() } }
    }

    suspend fun updateTeacher(id: UUID, teacher: Teacher): Teacher = panacheCoroutine.withTransaction {
        if (teacher.id != null && teacher.id != id) {
            throw BadRequestException("Path teacher id $id does not match payload id ${teacher.id}.")
        }

        val entity = getTeacher(id)
        applyPayload(entity, teacher)

        mapper.run { entity.toDto() }
    }

    private fun applyPayload(entity: TeacherEntity, teacher: Teacher) {
        entity.firstName = teacher.firstName
        entity.lastName = teacher.lastName
        entity.instrument = teacher.instrument
        entity.active = teacher.active
    }

    private suspend fun getTeacher(id: UUID): TeacherEntity =
        teacherRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Teacher $id was not found.")

}
