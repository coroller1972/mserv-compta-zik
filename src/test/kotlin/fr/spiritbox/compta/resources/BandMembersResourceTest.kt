package fr.spiritbox.compta.resources

import fr.spiritbox.compta.security.IdentityStub
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.Test
import java.util.UUID

/** La réponse d'une modification de groupe reflète les membres enregistrés, pas ceux d'avant la requête. */
@QuarkusTest
@QuarkusTestResource(IdentityStub::class)
class BandMembersResourceTest {
    @Inject lateinit var database: AgroalDataSource

    private val year = 2093
    private val musician = UUID.randomUUID()
    private val token = IdentityStub.token(permissions = listOf("MUSICIENS_READ", "GROUPS_READ", "GROUPS_WRITE"))

    @Test
    fun `adding or removing a member is returned by the first request`() {
        database.connection.use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("insert into accounting_year_settings(id,year,teacher_hourly_rate,group_membership_fee) values('${UUID.randomUUID()}',$year,54,30)")
                sql.execute("insert into musician(id,first_name,last_name) values('$musician','Léa','Martin')")
            }
        }
        val bandId = given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"year":$year,"name":"Groupe","type":"INDEPENDENT","weekday":"Mardi","memberIds":[]}""")
            .post("/bands")
            .then().statusCode(201).extract().path<String>("id")

        fun update(memberIds: String) = given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"year":$year,"name":"Groupe","type":"INDEPENDENT","weekday":"Mardi","memberIds":$memberIds}""")
            .put("/bands/$bandId")
            .then().statusCode(200)

        fun replaceMembers(memberIds: String) = given().header("Authorization", "Bearer $token").contentType("application/json")
            .body("""{"memberIds":$memberIds}""")
            .put("/bands/$bandId/members")
            .then().statusCode(200)

        update("""["$musician"]""").body("memberIds", contains(musician.toString()))
        update("[]").body("memberIds", empty<Any>())
        replaceMembers("""["$musician"]""").body("memberIds", contains(musician.toString()))
        replaceMembers("[]").body("memberIds", empty<Any>())
    }
}
