package fr.spiritbox.compta.resources

import fr.spiritbox.compta.security.IdentityStub
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Une seule salle : cours, groupes et ateliers ne se chevauchent pas (plages 11:30-14:00 et 16:30-20:00). */
@QuarkusTest
@QuarkusTestResource(IdentityStub::class)
class ScheduleResourceTest {
    @Inject lateinit var database: AgroalDataSource

    private val year = 2094
    private val teacher = UUID.randomUUID()
    private val firstMusician = UUID.randomUUID()
    private val secondMusician = UUID.randomUUID()
    private val token = IdentityStub.token(
        permissions = listOf("MUSICIENS_READ", "MUSICIENS_WRITE", "GROUPS_READ", "GROUPS_WRITE", "CONFIG_FINANCIALS"),
    )

    @Test
    fun `courses groups and workshops share one room without overlapping`() {
        database.connection.use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("insert into accounting_year_settings(id,year,teacher_hourly_rate,group_membership_fee) values('${UUID.randomUUID()}',$year,54,30)")
                sql.execute("insert into teacher(id,first_name,last_name,instrument) values('$teacher','Prof','Planning','Guitare')")
                sql.execute("insert into musician(id,first_name,last_name) values('$firstMusician','Léa','Martin'),('$secondMusician','Hugo','Bernard')")
            }
        }

        course(firstMusician, "17:00").statusCode(201)

        band("Groupe trop tôt", "INDEPENDENT", "16:30").statusCode(400).body(containsString("overlaps"))
        band("Groupe du mardi", "INDEPENDENT", "17:30").statusCode(201).body("startTime", equalTo("17:30"))
        band("Atelier trop tard", "WORKSHOP", "19:00").statusCode(400).body(containsString("ends after 20:00"))
        band("Atelier sur le groupe", "WORKSHOP", "18:30").statusCode(400).body(containsString("Groupe du mardi"))
        band("Atelier hors grille", "WORKSHOP", "17:15").statusCode(400)
        band("Groupe à placer", "INDEPENDENT", null).statusCode(201).body("startTime", nullValue())

        course(secondMusician, "18:00").statusCode(400).body(containsString("Groupe du mardi"))
        course(secondMusician, "17:00", sharedSlot = true).statusCode(201)

        val settings = given().header("Authorization", "Bearer $token").get("/accounting-years/$year/snapshot")
            .then().statusCode(200)
            .body("settings.groupHours", equalTo(1.5f))
            .body("bands.startTime", hasItem("17:30"))
            .extract().path<Any>("settings.version").toString()

        // Un groupe de 3 h (17:30-20:30) ne tiendrait plus dans la plage du soir.
        given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"year":$year,"version":$settings,"groupHours":3.00}""")
            .put("/accounting-years/$year/settings")
            .then().statusCode(409).body(containsString("do not fit"))
        given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"year":$year,"version":$settings,"groupHours":2.50}""")
            .put("/accounting-years/$year/settings")
            .then().statusCode(200).body("groupHours", equalTo(2.5f))
    }

    private fun course(musician: UUID, startTime: String, sharedSlot: Boolean = false): ValidatableResponse =
        given().header("Authorization", "Bearer $token").contentType("application/json")
            .body(
                """{"year":$year,"musicianId":"$musician","teacherId":"$teacher","instrument":"Guitare",
                   "weekday":"Mardi","startTime":"$startTime","sharedSlot":$sharedSlot,"active":true}""",
            )
            .post("/individual-courses")
            .then()

    private fun band(name: String, type: String, startTime: String?): ValidatableResponse =
        given().header("Authorization", "Bearer $token").contentType("application/json")
            .body(
                """{"year":$year,"name":"$name","type":"$type","teacherId":"$teacher","weekday":"Mardi",
                   "startTime":${startTime?.let { "\"$it\"" } ?: "null"},"memberIds":[]}""",
            )
            .post("/bands")
            .then()
}
