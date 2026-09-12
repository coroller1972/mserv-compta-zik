package fr.spiritbox.compta.security

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(IdentityStub::class)
class BusinessAuthorizationTest {
    private val admin get() = IdentityStub.token(admin = true)
    private fun request(token: String) = given().header("Authorization", "Bearer $token").contentType("application/json")

    @AfterEach
    fun resetIdentity() {
        IdentityStub.failures.clear()
        IdentityStub.livePermissions.clear()
    }

    @Test
    fun `live identity restrictions reject every business request and fail closed`() {
        val token = IdentityStub.token(admin = true, subject = "restricted")
        request(token).get("/accounting-years/2086/snapshot").then().statusCode(200)
        for ((status, code) in listOf(401 to "TOKEN_EXPIRED", 401 to "CREDENTIAL_EXPIRED", 403 to "PASSWORD_CHANGE_REQUIRED", 403 to "MFA_ENROLLMENT_REQUIRED")) {
            IdentityStub.failures["restricted"] = status to code
            request(token).get("/accounting-years/2086/snapshot").then().statusCode(status).body("code", equalTo(code))
            request(token).body("""{"year":2086,"version":0,"teacherHourlyRate":99}""")
                .put("/accounting-years/2086/settings").then().statusCode(status).body("code", equalTo(code))
        }
        IdentityStub.failures["restricted"] = 500 to "DATABASE_DETAILS_MUST_NOT_LEAK"
        request(token).get("/accounting-years/2086/snapshot").then().statusCode(503).body("code", equalTo("IDENTITY_UNAVAILABLE"))
        request("invalid-token").get("/accounting-years/2086/snapshot").then().statusCode(401)
        given().get("/accounting-years/2086/snapshot").then().statusCode(401)
    }

    @Test
    fun `permission changes take effect before JWT expiry`() {
        val token = IdentityStub.token(admin = true, subject = "downgraded")
        request(token).get("/teachers").then().statusCode(200)
        IdentityStub.livePermissions["downgraded"] = listOf("PRESENCE_READ")
        request(token).get("/teachers").then().statusCode(403)
        request(token).get("/accounting-years/2086/snapshot").then().statusCode(200).body("expenses", hasSize<Any>(0))
    }

    @Test
    fun `settings changes require each field permission and preserve omitted fields`() {
        val original = request(admin).get("/accounting-years/2087/snapshot").then().statusCode(200).extract().response()
        val version = original.path<Number>("settings.version").toLong()
        val rate = original.path<Number>("settings.teacherHourlyRate").toDouble()
        val holiday = IdentityStub.token(listOf("CONFIG_HOLIDAYS"))
        for (field in listOf("teacherHourlyRate", "groupMembershipFee", "individualCourseHours", "workshopHours")) {
            request(holiday).body("""{"year":2087,"version":$version,"$field":99}""")
                .put("/accounting-years/2087/settings").then().statusCode(403)
        }
        request(holiday).body("""{"year":2087,"version":$version,"terms":[]}""")
            .put("/accounting-years/2087/settings").then().statusCode(403)
        request(holiday).body("""{"year":2087,"version":$version,"schoolHolidayWeeks":[10,11]}""")
            .put("/accounting-years/2087/settings").then().statusCode(200)
            .body("schoolHolidayWeeks", equalTo(listOf(10, 11))).body("teacherHourlyRate", nullValue())
        val after = request(admin).get("/accounting-years/2087/snapshot").then().statusCode(200).extract().response()
        assertEquals(rate, after.path<Number>("settings.teacherHourlyRate").toDouble())
        assertEquals(original.path<List<Any>>("settings.terms"), after.path<List<Any>>("settings.terms"))
        val currentVersion = after.path<Number>("settings.version").toLong()
        val financial = IdentityStub.token(listOf("CONFIG_FINANCIALS"))
        request(financial).body("""{"year":2087,"version":$currentVersion,"schoolHolidayWeeks":[]}""")
            .put("/accounting-years/2087/settings").then().statusCode(403)
        request(financial).body("""{"year":2087,"version":$currentVersion,"teacherHourlyRate":62.50}""")
            .put("/accounting-years/2087/settings").then().statusCode(200).body("teacherHourlyRate", equalTo(62.5f))
        request(holiday).body("""{"year":2087,"version":$version,"schoolHolidayWeeks":[]}""")
            .put("/accounting-years/2087/settings").then().statusCode(409)
        val financialSettings = request(admin).get("/accounting-years/2087/snapshot").then().statusCode(200).extract().response()
        val terms = financialSettings.path<List<Map<String, Any>>>("settings.terms").map { it + ("name" to "Updated ${it["name"]}") }
        request(IdentityStub.token(listOf("CONFIG_TERMS"))).body(mapOf(
            "year" to 2087, "version" to financialSettings.path<Number>("settings.version"), "terms" to terms,
        )).put("/accounting-years/2087/settings").then().statusCode(200)
            .body("terms[0].name", equalTo("Updated ${financialSettings.path<String>("settings.terms[0].name")}"))
            .body("teacherHourlyRate", nullValue())
            .body("schoolHolidayWeeks", equalTo(listOf(10, 11)))
        request(admin).get("/accounting-years/2087/snapshot").then().statusCode(200)
            .body("settings.teacherHourlyRate", equalTo(62.5f))
    }

    @Test
    fun `snapshot scopes expenses financial rates contacts and archived people`() {
        val term = request(admin).get("/accounting-years/2088/snapshot").then().statusCode(200).extract().path<String>("settings.terms[0].id")
        val active = request(admin).body("""{"firstName":"Visible","lastName":"Audit","email":"private@example.test","active":true}""")
            .post("/musicians").then().statusCode(201).extract().path<String>("id")
        val archived = request(admin).body("""{"firstName":"Archived","lastName":"Audit","email":"archive@example.test","active":false}""")
            .post("/musicians").then().statusCode(201).extract().path<String>("id")
        val teacher = request(admin).body("""{"firstName":"Teacher","lastName":"Audit","instrument":"Piano","active":true}""")
            .post("/teachers").then().statusCode(201).extract().path<String>("id")
        val course = request(admin).body("""{"year":2088,"musicianId":"$active","teacherId":"$teacher","instrument":"Piano","weekday":"Lundi","startTime":"17:00","sharedSlot":false,"active":true}""")
            .post("/individual-courses").then().statusCode(201).extract().path<String>("id")
        request(admin).body("""{"termId":"$term","week":2,"entityType":"INDIVIDUAL_COURSE","entityId":"$course","present":true}""")
            .put("/attendance").then().statusCode(200)
        request(admin).body("""{"year":2088,"date":"2088-01-01","category":"OTHER","label":"Private expense","amount":123}""")
            .post("/accounting-years/2088/expenses").then().statusCode(201)
        val presence = request(IdentityStub.token(listOf("PRESENCE_READ"))).get("/accounting-years/2088/snapshot")
            .then().statusCode(200).body("expenses", hasSize<Any>(0)).body("settings.teacherHourlyRate", nullValue())
            .body("settings.groupMembershipFee", nullValue()).body("musicians.find { it.id == '$active' }.email", nullValue())
            .body("musicians.find { it.id == '$active' }.firstName", equalTo("Visible"))
            .body("teachers.find { it.id == '$teacher' }.firstName", equalTo("Teacher"))
            .body("individualCourses[0].id", equalTo(course)).body("attendance[0].entityId", equalTo(course))
            .extract().response()
        assertFalse(presence.path<List<String>>("musicians.id").contains(archived))
        request(IdentityStub.token(listOf("MUSICIENS_READ"))).get("/accounting-years/2088/snapshot")
            .then().statusCode(200).body("musicians.find { it.id == '$active' }.email", equalTo("private@example.test"))
            .body("attendance", hasSize<Any>(0)).body("expenses", hasSize<Any>(0))
        val archiveToken = IdentityStub.token(listOf("MUSICIENS_ARCHIVED"))
        request(archiveToken).get("/accounting-years/2088/snapshot").then().statusCode(200)
            .body("musicians.find { it.id == '$archived' }.email", equalTo("archive@example.test"))
        request(IdentityStub.token(listOf("EXPENSES_READ"))).get("/accounting-years/2088/snapshot")
            .then().statusCode(200).body("expenses[0].label", equalTo("Private expense"))
            .body("musicians", hasSize<Any>(0)).body("teachers", hasSize<Any>(0)).body("bands", hasSize<Any>(0))
        request(IdentityStub.token(listOf("BILLING_READ"))).get("/accounting-years/2088/snapshot")
            .then().statusCode(200).body("settings.teacherHourlyRate", equalTo(54.0f)).body("expenses", hasSize<Any>(0))
        val regularList = request(IdentityStub.token(listOf("MUSICIENS_READ"))).get("/musicians").then().statusCode(200).extract().path<List<String>>("id")
        assertFalse(regularList.contains(archived))
        request(IdentityStub.token()).get("/accounting-years/2088/snapshot").then().statusCode(403)
        request(admin).get("/accounting-years/2088/snapshot").then().statusCode(200)
            .body("musicians.find { it.id == '$archived' }.email", equalTo("archive@example.test"))
    }
}
