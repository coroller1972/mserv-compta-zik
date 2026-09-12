package fr.spiritbox.compta.security

import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(IdentityStub::class)
class AccountingYearReadTest {
    @Inject
    lateinit var dataSource: AgroalDataSource

    private fun request() = given().header("Authorization", "Bearer ${IdentityStub.token(admin = true)}").contentType("application/json")

    @Test
    fun `reading existing years preserves zero rates and never writes even after closure`() {
        val cases = listOf(2089 to (0 to 0), 2090 to (0 to 47), 2091 to (61 to 0))
        for ((year, amounts) in cases) {
            val original = request().get("/accounting-years/$year/snapshot").then().statusCode(200)
                .body("settings.teacherHourlyRate", equalTo(54.0f))
                .body("settings.groupMembershipFee", equalTo(30.0f)).extract().response()
            val version = original.path<Number>("settings.version").toLong()
            request().body(mapOf("year" to year, "version" to version,
                "teacherHourlyRate" to amounts.first, "groupMembershipFee" to amounts.second))
                .put("/accounting-years/$year/settings").then().statusCode(200)

            for (status in listOf("OPEN", "REVIEWED", "CLOSED")) {
                if (status != "OPEN") request().body(mapOf("status" to status))
                    .put("/accounting-years/$year/status").then().statusCode(200)
                val before = persistedState(year)
                repeat(2) {
                    request().get("/accounting-years/$year/snapshot").then().statusCode(200)
                        .body("settings.teacherHourlyRate", equalTo(amounts.first.toFloat()))
                        .body("settings.groupMembershipFee", equalTo(amounts.second.toFloat()))
                        .body("settings.status", equalTo(status))
                }
                assertEquals(before, persistedState(year), "GET changed persisted settings for $year/$status")
            }
        }
    }

    private fun persistedState(year: Int): List<Any?> = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT teacher_hourly_rate, group_membership_fee, version, status, updated_at FROM accounting_year_settings WHERE year = ?").use { statement ->
            statement.setInt(1, year)
            statement.executeQuery().use { result ->
                check(result.next())
                (1..5).map(result::getObject)
            }
        }
    }
}
