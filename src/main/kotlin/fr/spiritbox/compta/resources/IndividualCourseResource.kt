package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.IndividualCoursesApi
import fr.spiritbox.compta.openapi.model.IndividualCourse
import fr.spiritbox.compta.services.IndividualCourseService
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@Tag(name = "Individual courses")
class IndividualCourseResource(
    private val individualCourseService: IndividualCourseService,
) : IndividualCoursesApi {
    override suspend fun createIndividualCourse(individualCourse: IndividualCourse): Response =
        Response.status(Response.Status.CREATED)
            .entity(individualCourseService.createIndividualCourse(individualCourse))
            .build()

    override suspend fun deleteIndividualCourse(id: UUID): Response =
        individualCourseService.deleteIndividualCourse(id).let { Response.noContent().build() }

    override suspend fun listIndividualCourses(): Response =
        Response.ok(individualCourseService.listIndividualCourses()).build()

    override suspend fun updateIndividualCourse(
        id: UUID,
        individualCourse: IndividualCourse
    ): Response =
        Response.ok(individualCourseService.updateIndividualCourse(id, individualCourse)).build()

}
