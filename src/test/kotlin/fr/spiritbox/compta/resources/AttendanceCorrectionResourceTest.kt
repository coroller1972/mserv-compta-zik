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
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.util.UUID

/** Correction de présences déjà rémunérées : régularisation du professeur et recalcul de la facture élève. */
@QuarkusTest
@QuarkusTestResource(IdentityStub::class)
class AttendanceCorrectionResourceTest {
    @Inject lateinit var database: AgroalDataSource

    private val year = 2091
    private val termId = UUID.randomUUID()
    private val teacherId = UUID.randomUUID()
    private val musicianId = UUID.randomUUID()
    private val admin = IdentityStub.token(admin = true, subject = "correction-test")

    @Test
    fun `a sent teacher request is regularized and the student billing follows the corrected attendance`() {
        database.connection.use { connection ->
            connection.createStatement().use { sql ->
                val settingsId = UUID.randomUUID()
                sql.execute("insert into accounting_year_settings(id,year,teacher_hourly_rate,group_membership_fee) values('$settingsId',$year,40,30)")
                sql.execute("insert into term(id,settings_id,name,start_week,end_week,display_order) values('$termId','$settingsId','T1',2,14,1)")
                sql.execute("insert into teacher(id,first_name,last_name,instrument) values('$teacherId','Prof','Correction','Guitare')")
                sql.execute("insert into musician(id,first_name,last_name) values('$musicianId','Léa','Réclamation')")
            }
        }
        val courseId = given().auth().oauth2(admin).contentType("application/json")
            .body("""{"year":$year,"musicianId":"$musicianId","teacherId":"$teacherId","instrument":"Guitare","weekday":"Mardi","startTime":"11:30","sharedSlot":false,"active":true}""")
            .post("/individual-courses").then().statusCode(201).extract().path<String>("id")
        (2..9).forEach { week ->
            given().auth().oauth2(admin).contentType("application/json")
                .body("""{"termId":"$termId","week":$week,"entityType":"INDIVIDUAL_COURSE","entityId":"$courseId","present":true,"status":"PRESENT"}""")
                .put("/attendance").then().statusCode(200)
        }

        // Situation n°1 (S2 à S5, 4 cours de 0,5 h à 40 €) validée puis envoyée.
        val first = prepare(5).statusCode(202).extract()
        finalize(first.path("id"), first.path<Number>("version").toLong()).statusCode(200)
        given().auth().oauth2(admin).post("/documents/${first.path<String>("id")}/sent").then().statusCode(200)

        attendance()
            .body("find { it.week == 3 }.billingLocked", equalTo(true))
            .body("find { it.week == 3 }.billingDocumentStatus", equalTo("SENT"))
        billing().body("studentInvoices.find { it.musicianId == '$musicianId' }.individualCourseCount", equalTo(8))

        // Simulation : la régularisation est calculée, rien n'est enregistré.
        correct(listOf(3, 4), "ABSENT", dryRun = true).statusCode(200)
            .body("dryRun", equalTo(true))
            .body("adjustments", hasSize<Any>(1))
            .body("adjustments[0].amount", equalTo(-40.0f))
        attendance().body("find { it.week == 3 }.status", equalTo("PRESENT")).body("find { it.week == 3 }.billingLocked", equalTo(true))

        val stale = versions(listOf(3, 4))
        correct(listOf(3, 4), "ABSENT").statusCode(200)
            .body("dryRun", equalTo(false))
            .body("adjustments[0].amount", equalTo(-40.0f))
            .body("adjustments[0].reason", containsString("Léa Réclamation"))
            .body("adjustments[0].reason", containsString("S3, S4"))
            .body("attendance.status", equalTo(listOf("ABSENT", "ABSENT")))
        attendance().body("find { it.week == 4 }.status", equalTo("ABSENT")).body("find { it.week == 4 }.billingLocked", equalTo(false))
        billing()
            .body("studentInvoices.find { it.musicianId == '$musicianId' }.individualCourseCount", equalTo(6))
            .body("teacherInvoiceRequests.find { it.teacherId == '$teacherId' }.pendingAdjustmentAmount", equalTo(-40.0f))

        // Version périmée, puis présence déjà corrigée, puis présence pas encore facturée.
        correct(listOf(3, 4), "ABSENT", versions = stale).statusCode(409)
        correct(listOf(3), "CANCELLED").statusCode(400).body(containsString("Only a present session"))
        correct(listOf(6), "ABSENT").statusCode(400).body(containsString("edit the attendance directly"))

        // Situation n°2 (S6 à S9 = 80 €, moins la régularisation de 40 €) seulement validée : il faut l'annuler.
        val second = prepare(9).statusCode(202).body("totalAmount", equalTo(40.0f)).extract()
        finalize(second.path("id"), second.path<Number>("version").toLong()).statusCode(200)
        correct(listOf(7), "ABSENT").statusCode(409).body(containsString("cancel it"))

        // BILLING_PRINT seul ne suffit pas : modifier une présence exige PRESENCE_WRITE.
        val billingOnly = IdentityStub.token(permissions = listOf("BILLING_PRINT", "PRESENCE_READ"), subject = "billing-only")
        given().auth().oauth2(billingOnly).contentType("application/json")
            .body(correctionBody(versions(listOf(2)), "ABSENT", false))
            .post("/attendance/post-billing-corrections").then().statusCode(403)
    }

    private fun prepare(endWeek: Int): ValidatableResponse = given().auth().oauth2(admin).contentType("application/json")
        .body("""{"endWeek":$endWeek}""")
        .post("/accounting-years/$year/terms/$termId/teacher-invoice-requests/$teacherId/prepare").then()

    private fun finalize(documentId: String, version: Long): ValidatableResponse = given().auth().oauth2(admin).contentType("application/json")
        .body("""{"documentId":"$documentId","version":$version}""")
        .post("/accounting-years/$year/terms/$termId/teacher-invoice-requests/$teacherId/finalize").then()

    private fun attendance(): ValidatableResponse = given().auth().oauth2(admin)
        .get("/accounting-years/$year/terms/$termId/attendance").then().statusCode(200)

    private fun billing(): ValidatableResponse = given().auth().oauth2(admin)
        .get("/accounting-years/$year/terms/$termId/billing").then().statusCode(200)

    private fun versions(weeks: List<Int>): Map<String, Long> {
        val response = attendance().extract().response()
        return weeks.associate { week ->
            response.path<String>("find { it.week == $week }.id") to response.path<Number>("find { it.week == $week }.version").toLong()
        }
    }

    private fun correctionBody(versions: Map<String, Long>, status: String, dryRun: Boolean) =
        """{"attendance":[${versions.entries.joinToString(",") { """{"id":"${it.key}","version":${it.value}}""" }}],"status":"$status","reason":"Réclamation de l'élève","dryRun":$dryRun}"""

    private fun correct(weeks: List<Int>, status: String, dryRun: Boolean = false, versions: Map<String, Long>? = null): ValidatableResponse =
        given().auth().oauth2(admin).contentType("application/json")
            .body(correctionBody(versions ?: versions(weeks), status, dryRun))
            .post("/attendance/post-billing-corrections").then()
}
