package fr.spiritbox.compta.resources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import fr.spiritbox.compta.security.IdentityStub
import jakarta.inject.Inject
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(IdentityStub::class)
class StudentInvoicesSentTest {
    @Inject lateinit var database: AgroalDataSource
    @Inject lateinit var mapper: ObjectMapper

    @Test
    fun `batch marks only validated individual student invoices and remains idempotent`() {
        val settings = UUID.randomUUID()
        val term = UUID.randomUUID()
        val otherTerm = UUID.randomUUID()
        val musician = UUID.randomUUID()
        val teacher = UUID.randomUUID()
        val fixtures = listOf(
            Fixture(status = "GENERATED"), Fixture(status = "GENERATED"),
            Fixture(status = "DRAFT"), Fixture(status = "SENT"),
            Fixture(status = "CANCELLED"), Fixture(status = "CREDITED"),
            Fixture(status = "GENERATED", summary = true),
            Fixture(status = "GENERATED", type = "TEACHER_INVOICE_REQUEST"),
            Fixture(status = "GENERATED", type = "CREDIT_NOTE"),
            Fixture(status = "GENERATED", otherTerm = true),
        )
        val root = Path.of("/tmp/compta-zik-test-documents/student-sent-test")
        Files.createDirectories(root)
        database.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { sql ->
                sql.execute("insert into accounting_year_settings(id,year,teacher_hourly_rate,group_membership_fee) values('$settings',2096,54,30)")
                sql.execute("insert into term(id,settings_id,name,start_week,end_week) values('$term','$settings','T1',1,12),('$otherTerm','$settings','T2',13,24)")
                sql.execute("insert into musician(id,first_name,last_name) values('$musician','Élève','Test')")
                sql.execute("insert into teacher(id,first_name,last_name,instrument) values('$teacher','Professeur','Test','Piano')")
                fixtures.forEach { fixture ->
                    Files.writeString(root.resolve("${fixture.id}.pdf"), "%PDF-1.4\nFixture ${fixture.id}\n%%EOF")
                    val owner = if (fixture.summary || fixture.type == "TEACHER_INVOICE_REQUEST") "null" else "'$musician'"
                    val provider = if (fixture.type == "TEACHER_INVOICE_REQUEST") "'$teacher'" else "null"
                    val sentAt = if (fixture.status == "SENT") "'2026-01-01T12:00:00Z'" else "null"
                    sql.execute("""
                        insert into generated_document(id,term_id,type,status,musician_id,teacher_id,document_number,
                        file_name,content_type,storage_path,total_amount,metadata,sent_at,version)
                        values('${fixture.id}','${if (fixture.otherTerm) otherTerm else term}','${fixture.type}','${fixture.status}',
                        $owner,$provider,'TEST-${fixture.id}','${fixture.id}.pdf','application/pdf',
                        'student-sent-test/${fixture.id}.pdf',12.34,'{"frozen":true,"amount":12.34}',$sentAt,3)
                    """.trimIndent())
                }
            }
            connection.commit()
        }
        val endpoint = "/accounting-years/2096/terms/$term/student-invoices/sent"
        val before = fixtures.associate { it.id to row(it.id) }
        val readonly = IdentityStub.token(permissions = listOf("BILLING_READ"))
        given().header("Authorization", "Bearer $readonly").post(endpoint).then().statusCode(403)
        assertEquals(before, fixtures.associate { it.id to row(it.id) })
        val token = IdentityStub.token(permissions = listOf("BILLING_PRINT"))
        given().header("Authorization", "Bearer $token")
            .post("/accounting-years/2095/terms/$term/student-invoices/sent").then().statusCode(404)

        given().header("Authorization", "Bearer $token").post(endpoint).then().statusCode(200)
            .body("size()", equalTo(2)).body("status", everyItem(equalTo("SENT")))
            .body("version", everyItem(equalTo(4))).body("sentAt", everyItem(notNullValue()))
            .body("id", containsInAnyOrder(fixtures[0].id.toString(), fixtures[1].id.toString()))
        val after = fixtures.associate { it.id to row(it.id) }
        fixtures.drop(2).forEach { assertEquals(before[it.id], after[it.id], "Unrelated documents must stay unchanged") }
        fixtures.take(2).forEach { fixture ->
            val original = before.getValue(fixture.id)
            val updated = after.getValue(fixture.id)
            assertEquals("SENT", updated.path("status").asText())
            assertEquals(4, updated.path("version").asInt())
            assertFalse(updated.path("sent_at").isNull)
            original.fieldNames().forEach { field ->
                if (field !in setOf("status", "sent_at", "updated_at", "version")) assertEquals(original.path(field), updated.path(field), field)
            }
            assertEquals(1, auditCount(fixture.id))
        }
        assertEquals(after.getValue(fixtures[0].id).path("sent_at"), after.getValue(fixtures[1].id).path("sent_at"))
        given().header("Authorization", "Bearer $token").post(endpoint).then().statusCode(200).body("size()", equalTo(0))
        assertEquals(after, fixtures.associate { it.id to row(it.id) })
        fixtures.take(2).forEach { assertEquals(1, auditCount(it.id)) }
        fixtures.drop(2).forEach { assertEquals(0, auditCount(it.id)) }

        // Closing the year also blocks a batch that would otherwise have an eligible invoice.
        database.connection.use { connection ->
            connection.createStatement().use { it.execute("update accounting_year_settings set status='CLOSED' where id='$settings'") }
        }
        given().header("Authorization", "Bearer $token")
            .post("/accounting-years/2096/terms/$otherTerm/student-invoices/sent").then().statusCode(409)
        assertEquals(after, fixtures.associate { it.id to row(it.id) })
    }

    private fun row(id: UUID): JsonNode = database.connection.use { connection ->
        connection.prepareStatement("select to_jsonb(d)::text from generated_document d where id=?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { result -> assertTrue(result.next()); mapper.readTree(result.getString(1)) }
        }
    }

    private fun auditCount(id: UUID): Int = database.connection.use { connection ->
        connection.prepareStatement("select count(*) from audit_event where entity_id=? and action='SENT'").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private data class Fixture(
        val id: UUID = UUID.randomUUID(), val status: String, val type: String = "STUDENT_INVOICE",
        val summary: Boolean = false, val otherTerm: Boolean = false,
    )
}
