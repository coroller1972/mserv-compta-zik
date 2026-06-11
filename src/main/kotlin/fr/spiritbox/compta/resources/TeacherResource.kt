package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.TeachersApi
import fr.spiritbox.compta.openapi.model.Teacher
import fr.spiritbox.compta.services.TeacherService
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@Tag(name = "Teachers")
class TeacherResource(
    private val teacherService: TeacherService,
) : TeachersApi {
    override suspend fun createTeacher(teacher: Teacher): Response =
        Response.status(Response.Status.CREATED)
            .entity(teacherService.createTeacher(teacher))
            .build()

    override suspend fun deleteTeacher(id: UUID): Response =
        teacherService.deleteTeacher(id).let { Response.noContent().build() }

    override suspend fun listTeachers(): Response =
        Response.ok(teacherService.listTeachers()).build()

    override suspend fun updateTeacher(
        id: UUID,
        teacher: Teacher
    ): Response =
        Response.ok(teacherService.updateTeacher(id, teacher)).build()

}
