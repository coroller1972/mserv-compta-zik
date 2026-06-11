package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.AttendanceApi
import fr.spiritbox.compta.openapi.model.Attendance
import fr.spiritbox.compta.services.AttendanceService
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Attendance")
class AttendanceResource(
    private val attendanceService: AttendanceService,
) : AttendanceApi {
    override suspend fun upsertAttendance(attendance: Attendance): Response {
        return Response.ok(attendanceService.upsertAttendance(attendance)).build()
    }

}
