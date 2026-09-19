package fr.spiritbox.compta.resources

import fr.spiritbox.compta.security.IdentityStub
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.agroal.api.AgroalDataSource
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

@QuarkusTest
@io.quarkus.test.common.QuarkusTestResource(fr.spiritbox.compta.security.IdentityStub::class)
class BillingResourceTest {
    @Inject
    lateinit var dataSource: AgroalDataSource

    @Test
    fun `billing endpoint returns canonical lines and totals`() {
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(referenceDataTransfer)
            .post("/data/import/analyze")
            .then()
            .statusCode(200)
            .body("valid", equalTo(true))
            .body("errors", hasSize<Any>(0))
            .body("incoming.musicians", equalTo(1))
            .body("confirmationValue", equalTo("REPLACE_ALL_DATA"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(referenceDataTransfer)
            .post("/data/import")
            .then()
            .statusCode(400)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .header("X-Import-Confirmation", "REPLACE_ALL_DATA")
            .contentType("application/json")
            .body(referenceDataTransfer)
            .post("/data/import")
            .then()
            .statusCode(200)

        val invalidTransfer = referenceDataTransfer.replace("\"week\": 2", "\"week\": 1")
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(invalidTransfer)
            .post("/data/import/analyze")
            .then()
            .statusCode(200)
            .body("valid", equalTo(false))
            .body("current.musicians", equalTo(1))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .header("X-Import-Confirmation", "REPLACE_ALL_DATA")
            .contentType("application/json")
            .body(invalidTransfer)
            .post("/data/import")
            .then()
            .statusCode(400)

        val importedSnapshot = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/snapshot")
            .then()
            .statusCode(200)
            .extract()
            .response()
        val importedTermId = importedSnapshot.path<String>("settings.terms[0].id")
        val importedTeacherId = importedSnapshot.path<String>("teachers[0].id")
        val importedMusicianId = importedSnapshot.path<String>("musicians[0].id")
        val importedCourseId = importedSnapshot.path<String>("individualCourses[0].id")
        var importedAttendanceVersion = importedSnapshot.path<Number>("attendance.find { it.week == 3 }.version").toLong()
        assertEquals(0.5f, importedSnapshot.path<Float>("settings.individualCourseHours"))
        assertEquals(1.25f, importedSnapshot.path<Float>("settings.workshopHours"))
        val importedIndependentBandId = importedSnapshot.path<String>(
            "bands.find { it.name == 'Groupe autonome' }.id",
        )

        val createdAttendanceVersion = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"termId":"$importedTermId","week":4,"entityType":"INDIVIDUAL_COURSE","entityId":"$importedCourseId","present":true}""")
            .put("/attendance")
            .then()
            .statusCode(200)
            .body("version", equalTo(0))
            .extract()
            .path<Number>("version").toLong()

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"version":$createdAttendanceVersion,"termId":"$importedTermId","week":4,"entityType":"INDIVIDUAL_COURSE","entityId":"$importedCourseId","present":false,"status":"ABSENT"}""")
            .put("/attendance")
            .then()
            .statusCode(200)
            .body("version", equalTo(1))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .header("X-Import-Confirmation", "REPLACE_ALL_DATA")
            .contentType("application/json")
            .body("""{"termId":"$importedTermId","week":1,"entityType":"INDIVIDUAL_COURSE","entityId":"$importedCourseId","present":true}""")
            .put("/attendance")
            .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("code", equalTo("VALIDATION_ERROR"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"termId":"$importedTermId","week":3,"entityType":"WORKSHOP","entityId":"$importedIndependentBandId","present":true}""")
            .put("/attendance")
            .then()
            .statusCode(400)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"termId":"$importedTermId","week":3,"entityType":"INDIVIDUAL_COURSE","entityId":"00000000-0000-0000-0000-000000000099","present":true}""")
            .put("/attendance")
            .then()
            .statusCode(404)

        val staleAttendanceVersion = importedAttendanceVersion
        val cancelledAttendance = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"version":$importedAttendanceVersion,"termId":"$importedTermId","week":3,"entityType":"INDIVIDUAL_COURSE","entityId":"$importedCourseId","present":false,"status":"CANCELLED"}""")
            .put("/attendance")
            .then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"))
            .body("present", equalTo(false))
            .extract().response()
        importedAttendanceVersion = cancelledAttendance.path<Number>("version").toLong()

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"version":$staleAttendanceVersion,"termId":"$importedTermId","week":3,"entityType":"INDIVIDUAL_COURSE","entityId":"$importedCourseId","present":false}""")
            .put("/attendance")
            .then()
            .statusCode(409)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/audit-events?limit=20")
            .then()
            .statusCode(200)
            .body("action", hasItem("UPDATED"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(
                """{
                  "year":2099,
                  "date":"2099-02-15",
                  "category":"EQUIPMENT",
                  "label":"Cables audio",
                  "amount":42.50,
                  "notes":"Test de regression audit append-only"
                }""",
            )
            .post("/accounting-years/2099/expenses")
            .then()
            .statusCode(201)
            .body("year", equalTo(2099))
            .body("category", equalTo("EQUIPMENT"))
            .body("label", equalTo("Cables audio"))
            .body("amount", equalTo(42.5f))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/audit-events?limit=20")
            .then()
            .statusCode(200)
            .body("find { it.entityType == 'EXPENSE' && it.action == 'CREATED' }.details.year", equalTo(2099))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/terms/$importedTermId/billing")
            .then()
            .statusCode(200)
            .body("currency", equalTo("EUR"))
            .body("studentInvoices", hasSize<Any>(1))
            .body("studentInvoices[0].individualCourseCount", equalTo(1))
            .body("studentInvoices[0].individualCourseAmount", equalTo(27.0f))
            .body("studentInvoices[0].groupMembershipAmount", equalTo(30.0f))
            .body("teacherInvoiceRequests", hasSize<Any>(1))
            .body("teacherInvoiceRequests[0].weeklyLines", hasSize<Any>(1))
            .body("teacherInvoiceRequests[0].weeklyLines[0].week", equalTo(2))
            .body("teacherInvoiceRequests[0].weeklyLines[0].date", equalTo("2099-01-05"))
            .body("teacherInvoiceRequests[0].weeklyLines[0].hours", equalTo(1.75f))
            .body("teacherInvoiceRequests[0].weeklyLines[0].amount", equalTo(94.5f))
            .body("teacherInvoiceRequests[0].totalHours", equalTo(1.75f))
            .body("teacherInvoiceRequests[0].totalAmount", equalTo(94.5f))
            .body("totals.studentBilling", equalTo(27.0f))
            .body("totals.groupFees", equalTo(30.0f))
            .body("totals.teacherDue", equalTo(94.5f))
            .body("totals.subsidy", equalTo(51.0f))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"status":"REVIEWED"}""")
            .put("/accounting-years/2099/status")
            .then()
            .statusCode(200)
            .body("status", equalTo("REVIEWED"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(
                """{
                  "id":"$importedIndependentBandId",
                  "year":2099,
                  "name":"Modification interdite",
                  "type":"INDEPENDENT",
                  "memberIds":["$importedMusicianId"]
                }""",
            )
            .put("/bands/$importedIndependentBandId")
            .then()
            .statusCode(409)

        val firstDocument = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .post("/accounting-years/2099/terms/$importedTermId/student-invoices")
            .then()
            .statusCode(202)
            .body("$", hasSize<Any>(1))
            .body("[0].status", equalTo("DRAFT"))
            .extract()
            .response()
        val immutableDocumentId = firstDocument.path<String>("[0].id")
        val immutableDocumentTotal = firstDocument.path<Float>("[0].totalAmount")

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"status":"OPEN"}""")
            .put("/accounting-years/2099/status")
            .then()
            .statusCode(200)
            .body("status", equalTo("OPEN"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(
                """{
                  "termId":"$importedTermId",
                  "version":$importedAttendanceVersion,
                  "week":3,
                  "entityType":"INDIVIDUAL_COURSE",
                  "entityId":"$importedCourseId",
                  "present":true
                }""",
            )
            .put("/attendance")
            .then()
            .statusCode(200)

        val preparedAgain = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .post("/accounting-years/2099/terms/$importedTermId/student-invoices")
            .then()
            .statusCode(202)
            .extract()
            .response()
        assertEquals(immutableDocumentId, preparedAgain.path<String>("[0].id"))
        assertNotEquals(immutableDocumentTotal, preparedAgain.path<Float>("[0].totalAmount"))
        val finalizedTotal = preparedAgain.path<Float>("[0].totalAmount")

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .post("/accounting-years/2099/terms/$importedTermId/student-invoices/finalize")
            .then()
            .statusCode(200)
            .body("[0].id", equalTo(immutableDocumentId))
            .body("[0].status", equalTo("GENERATED"))
            .body("[0].totalAmount", equalTo(finalizedTotal))

        val snapshotJson = dataSource.connection.use { connection ->
            connection.prepareStatement("select metadata::text from generated_document where id = ?::uuid").use { statement ->
                statement.setString(1, immutableDocumentId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }
        assertTrue(snapshotJson.contains("\"schemaVersion\": 1"))
        assertTrue(snapshotJson.contains("\"teacherHourlyRate\""))
        assertTrue(snapshotJson.contains("\"STUDENT_INVOICE\""))

        assertThrows(java.sql.SQLException::class.java) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("update generated_document set total_amount = total_amount + 1 where id = ?::uuid").use { statement ->
                    statement.setString(1, immutableDocumentId)
                    statement.executeUpdate()
                }
            }
        }

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .post("/documents/$immutableDocumentId/sent")
            .then()
            .statusCode(200)
            .body("status", equalTo("SENT"))

        val correction = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"reason":"Présence corrigée après envoi"}""")
            .post("/documents/$immutableDocumentId/correct")
            .then()
            .statusCode(200)
            .body("original.status", equalTo("CREDITED"))
            .body("creditNote.type", equalTo("CREDIT_NOTE"))
            .body("creditNote.status", equalTo("GENERATED"))
            .body("replacement.status", equalTo("DRAFT"))
            .extract().response()
        val replacementId = correction.path<String>("replacement.id")
        assertEquals(immutableDocumentId, correction.path<String>("replacement.parentDocumentId"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .post("/accounting-years/2099/terms/$importedTermId/student-invoices/finalize")
            .then()
            .statusCode(200)
            .body("find { it.id == '$replacementId' }.status", equalTo("GENERATED"))

        val copiedSnapshot = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .post("/accounting-years/2100/copy-configuration-from/2099")
            .then()
            .statusCode(201)
            .body("bands", hasSize<Any>(2))
            .body("bands.year", everyItem(equalTo(2100)))
            .body("individualCourses", hasSize<Any>(1))
            .body("individualCourses.year", everyItem(equalTo(2100)))
            .extract()
            .response()

        val copiedIndependentBandId = copiedSnapshot.path<String>(
            "bands.find { it.name == 'Groupe autonome' }.id",
        )
        val copiedMemberId = copiedSnapshot.path<String>("musicians[0].id")
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(
                """{
                  "id":"$copiedIndependentBandId",
                  "year":2100,
                  "name":"Groupe autonome 2100",
                  "type":"INDEPENDENT",
                  "memberIds":["$copiedMemberId"]
                }""",
            )
            .put("/bands/$copiedIndependentBandId")
            .then()
            .statusCode(200)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/snapshot")
            .then()
            .statusCode(200)
            .body("bands.name", hasItem("Groupe autonome"))
            .body("bands.name", not(hasItem("Groupe autonome 2100")))

        val secondTeacherId = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"firstName":"Deuxieme","lastName":"Prestataire","instrument":"Violon","active":true}""")
            .post("/teachers")
            .then()
            .statusCode(201)
            .extract().path<String>("id")

        val secondMusicianId = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"firstName":"Deuxieme","lastName":"Eleve","email":"deuxieme@example.test","active":true}""")
            .post("/musicians")
            .then()
            .statusCode(201)
            .extract().path<String>("id")

        val secondCourseId = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(
                """{
                  "year":2099,
                  "musicianId":"$secondMusicianId",
                  "teacherId":"$secondTeacherId",
                  "instrument":"Violon",
                  "weekday":"Mercredi",
                  "startTime":"18:00:00",
                  "sharedSlot":false,
                  "active":true
                }""",
            )
            .post("/individual-courses")
            .then()
            .statusCode(201)
            .extract().path<String>("id")

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"termId":"$importedTermId","week":2,"entityType":"INDIVIDUAL_COURSE","entityId":"$secondCourseId","present":true}""")
            .put("/attendance")
            .then()
            .statusCode(200)

        val firstDraft = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"endWeek":4}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$importedTeacherId/prepare")
            .then()
            .statusCode(202)
            .body("teacherId", equalTo(importedTeacherId))
            .body("status", equalTo("DRAFT"))
            .extract()

        val secondDraft = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"endWeek":4}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$secondTeacherId/prepare")
            .then()
            .statusCode(202)
            .body("teacherId", equalTo(secondTeacherId))
            .body("status", equalTo("DRAFT"))
            .extract()

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"documentId":"${firstDraft.path<String>("id")}","version":${firstDraft.path<Long>("version")}}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$importedTeacherId/finalize")
            .then()
            .statusCode(200)
            .body("teacherId", equalTo(importedTeacherId))
            .body("status", equalTo("GENERATED"))

        val lockedAttendance = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/terms/$importedTermId/attendance")
            .then().statusCode(200)
            .body("find { it.entityId == '$importedCourseId' && it.week == 3 }.billingLocked", equalTo(true))
            .extract().response()
        val lockedVersion = lockedAttendance.path<Number>("find { it.entityId == '$importedCourseId' && it.week == 3 }.version").toLong()
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"version":$lockedVersion,"termId":"$importedTermId","week":3,"entityType":"INDIVIDUAL_COURSE","entityId":"$importedCourseId","present":false,"status":"ABSENT"}""")
            .put("/attendance")
            .then().statusCode(409).body("detail", containsString("DFP-2099"))

        val weekFourVersion = lockedAttendance.path<Number>("find { it.entityId == '$importedCourseId' && it.week == 4 }.version").toLong()
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"version":$weekFourVersion,"termId":"$importedTermId","week":4,"entityType":"INDIVIDUAL_COURSE","entityId":"$importedCourseId","present":true,"status":"PRESENT"}""")
            .put("/attendance").then().statusCode(200)

        val secondInstallment = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json").body("""{"endWeek":4}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$importedTeacherId/prepare")
            .then().statusCode(202).body("installmentNumber", equalTo(2)).extract()
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"documentId":"${secondInstallment.path<String>("id")}","version":${secondInstallment.path<Long>("version")}}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$importedTeacherId/finalize")
            .then().statusCode(200).body("installmentNumber", equalTo(2)).body("status", equalTo("GENERATED"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/terms/$importedTermId/billing")
            .then().statusCode(200)
            .body("teacherInvoiceRequests.find { it.teacherId == '$importedTeacherId' }.issuedAmount", equalTo(148.5f))
            .body("teacherInvoiceRequests.find { it.teacherId == '$importedTeacherId' }.remainingAmount", equalTo(0.0f))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json").body("""{"reason":"Situation créée pour tester la libération"}""")
            .post("/documents/${secondInstallment.path<String>("id")}/cancel")
            .then().statusCode(200).body("status", equalTo("CANCELLED"))
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/terms/$importedTermId/attendance")
            .then().statusCode(200)
            .body("find { it.entityId == '$importedCourseId' && it.week == 4 }.billingLocked", equalTo(false))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .post("/documents/${firstDraft.path<String>("id")}/sent")
            .then().statusCode(200).body("status", equalTo("SENT"))
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json").body("""{"amount":10.00,"reason":"Rattrapage exceptionnel"}""")
            .post("/documents/${firstDraft.path<String>("id")}/teacher-adjustments")
            .then().statusCode(201).body("amount", equalTo(10.0f))

        val thirdInstallment = given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json").body("""{"endWeek":4}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$importedTeacherId/prepare")
            .then().statusCode(202).body("installmentNumber", equalTo(3)).body("totalAmount", equalTo(37.0f)).extract()
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"documentId":"${thirdInstallment.path<String>("id")}","version":${thirdInstallment.path<Long>("version")}}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$importedTeacherId/finalize")
            .then().statusCode(200).body("installmentNumber", equalTo(3)).body("totalAmount", equalTo(37.0f))
        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/terms/$importedTermId/billing")
            .then().statusCode(200)
            .body("teacherInvoiceRequests.find { it.teacherId == '$importedTeacherId' }.issuedAmount", equalTo(158.5f))
            .body("teacherInvoiceRequests.find { it.teacherId == '$importedTeacherId' }.pendingAdjustmentAmount", equalTo(0.0f))
            .body("teacherInvoiceRequests.find { it.teacherId == '$importedTeacherId' }.remainingAmount", equalTo(0.0f))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2099/terms/$importedTermId/documents")
            .then()
            .statusCode(200)
            .body("findAll { it.teacherId == '$importedTeacherId' }.status", hasItems("SENT", "CANCELLED", "GENERATED"))
            .body("find { it.teacherId == '$secondTeacherId' }.status", equalTo("DRAFT"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"status":"CLOSED"}""")
            .put("/accounting-years/2099/status")
            .then()
            .statusCode(409)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"documentId":"${secondDraft.path<String>("id")}","version":${secondDraft.path<Long>("version")}}""")
            .post("/accounting-years/2099/terms/$importedTermId/teacher-invoice-requests/$secondTeacherId/finalize")
            .then()
            .statusCode(200)
            .body("teacherId", equalTo(secondTeacherId))
            .body("status", equalTo("GENERATED"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"status":"REVIEWED"}""")
            .put("/accounting-years/2099/status")
            .then()
            .statusCode(200)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"status":"CLOSED"}""")
            .put("/accounting-years/2099/status")
            .then()
            .statusCode(200)
            .body("status", equalTo("CLOSED"))

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body(
                """{
                  "termId":"$importedTermId",
                  "week":3,
                  "entityType":"INDIVIDUAL_COURSE",
                  "entityId":"$importedCourseId",
                  "present":false
                }""",
            )
            .put("/attendance")
            .then()
            .statusCode(409)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .contentType("application/json")
            .body("""{"status":"OPEN"}""")
            .put("/accounting-years/2099/status")
            .then()
            .statusCode(409)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .header("X-Import-Confirmation", "REPLACE_ALL_DATA")
            .contentType("application/json")
            .body(legacyDataTransfer)
            .post("/data/import")
            .then()
            .statusCode(200)

        given()
            .header("Authorization", "Bearer ${administratorToken()}")
            .get("/accounting-years/2026/snapshot")
            .then()
            .statusCode(200)
            .body("bands.year", everyItem(equalTo(2026)))
            .body("individualCourses.year", everyItem(equalTo(2026)))
    }

    private fun administratorToken(): String = IdentityStub.token(admin = true, subject = "billing-test")

    private companion object {
        val referenceDataTransfer = """
            {
              "formatVersion": 2,
              "exportedAt": "2099-01-01T00:00:00Z",
              "settings": [{
                "year": 2099,
                "teacherHourlyRate": 54.00,
                "groupMembershipFee": 30.00,
                "schoolHolidayWeeks": [],
                "terms": [{
                  "id": "10000000-0000-0000-0000-000000000001",
                  "name": "Trimestre de reference",
                  "startWeek": 2,
                  "endWeek": 14,
                  "displayOrder": 1
                }]
              }],
              "teachers": [{
                "id": "20000000-0000-0000-0000-000000000001",
                "firstName": "Prof",
                "lastName": "Reference",
                "instrument": "Piano",
                "active": true
              }],
              "musicians": [{
                "id": "30000000-0000-0000-0000-000000000001",
                "firstName": "Eleve",
                "lastName": "Reference",
                "email": "eleve@example.test",
                "active": true
              }],
              "bands": [{
                "id": "40000000-0000-0000-0000-000000000001",
                "year": 2099,
                "name": "Atelier",
                "type": "WORKSHOP",
                "teacherId": "20000000-0000-0000-0000-000000000001",
                "weekday": "Mercredi",
                "memberIds": ["30000000-0000-0000-0000-000000000001"]
              }, {
                "id": "40000000-0000-0000-0000-000000000002",
                "year": 2099,
                "name": "Groupe autonome",
                "type": "INDEPENDENT",
                "memberIds": ["30000000-0000-0000-0000-000000000001"]
              }],
              "individualCourses": [{
                "id": "50000000-0000-0000-0000-000000000001",
                "year": 2099,
                "musicianId": "30000000-0000-0000-0000-000000000001",
                "teacherId": "20000000-0000-0000-0000-000000000001",
                "instrument": "Piano",
                "weekday": "Mardi",
                "startTime": "17:00",
                "sharedSlot": false,
                "active": true
              }],
              "attendance": [{
                "termId": "10000000-0000-0000-0000-000000000001",
                "week": 2,
                "entityType": "INDIVIDUAL_COURSE",
                "entityId": "50000000-0000-0000-0000-000000000001",
                "present": true
              }, {
                "termId": "10000000-0000-0000-0000-000000000001",
                "week": 3,
                "entityType": "INDIVIDUAL_COURSE",
                "entityId": "50000000-0000-0000-0000-000000000001",
                "present": false
              }, {
                "termId": "10000000-0000-0000-0000-000000000001",
                "week": 2,
                "entityType": "WORKSHOP",
                "entityId": "40000000-0000-0000-0000-000000000001",
                "present": true
              }],
              "expenses": []
            }
        """.trimIndent()

        val legacyDataTransfer = """
            {
              "formatVersion": 1,
              "exportedAt": "2026-01-01T00:00:00Z",
              "settings": [{
                "year": 2026,
                "teacherHourlyRate": 54.00,
                "groupMembershipFee": 30.00,
                "schoolHolidayWeeks": [],
                "terms": [{
                  "id": "61000000-0000-0000-0000-000000000001",
                  "name": "Trimestre historique",
                  "startWeek": 2,
                  "endWeek": 14,
                  "displayOrder": 1
                }]
              }],
              "teachers": [{
                "id": "62000000-0000-0000-0000-000000000001",
                "firstName": "Prof",
                "lastName": "Historique",
                "instrument": "Guitare",
                "active": true
              }],
              "musicians": [{
                "id": "63000000-0000-0000-0000-000000000001",
                "firstName": "Eleve",
                "lastName": "Historique",
                "active": true
              }],
              "bands": [{
                "id": "64000000-0000-0000-0000-000000000001",
                "name": "Groupe historique",
                "type": "INDEPENDENT",
                "memberIds": ["63000000-0000-0000-0000-000000000001"]
              }],
              "individualCourses": [{
                "id": "65000000-0000-0000-0000-000000000001",
                "musicianId": "63000000-0000-0000-0000-000000000001",
                "teacherId": "62000000-0000-0000-0000-000000000001",
                "instrument": "Guitare",
                "weekday": "Lundi",
                "startTime": "17:00",
                "sharedSlot": false,
                "active": true
              }],
              "attendance": [],
              "expenses": []
            }
        """.trimIndent()
    }
}
