package fr.spiritbox.compta.services

import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.StudentInvoiceDraft
import fr.spiritbox.compta.openapi.model.TeacherBillingAdjustment
import fr.spiritbox.compta.openapi.model.TeacherInvoiceRequestDraft
import fr.spiritbox.compta.openapi.model.TeacherInvoiceWeeklyLine
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/** Rend chaque gabarit « Partition » en PDF ; les exemples sont déposés dans build/pdf-preview pour relecture. */
class DocumentPdfRenderingTest {
    private val templates = DocumentTemplateService()
    private val pdf = HtmlPdfService(storageDirectory = "build/pdf-preview")

    private val term = TermEntity().apply { name = "T3"; startWeek = 36; endWeek = 51 }
    private val musician = MusicianEntity().apply { id = UUID.randomUUID(); firstName = "Léa"; lastName = "Martin" }
    private val teacher = TeacherEntity().apply { id = UUID.randomUUID(); firstName = "Camille"; lastName = "Durand"; instrument = "Guitare" }

    @Test
    fun `draft student invoice keeps French accents and the draft banner`() {
        val draft = StudentInvoiceDraft(musician.id!!, 12, BigDecimal("270.00"), BigDecimal("30.00"), BigDecimal("300.00"))
        val text = render("facture-eleve-brouillon", templates.studentInvoice("FE-2026-031", 2026, term, musician, draft, isDraft = true))

        // Les libellés sont en capitales (text-transform) : le texte extrait l'est aussi.
        listOf("Facture élève", "ÉLÈVE", "Léa Martin", "DÉSIGNATION", "QUANTITÉ", "BROUILLON", "VALIDÉ", "300,00\u00A0€", "Page 1 / 1")
            .forEach { assertTrue(it in text, "« $it » absent du PDF") }
    }

    @Test
    fun `thousands use a no-break space the embedded fonts can draw`() {
        val drafts = List(3) { StudentInvoiceDraft(UUID.randomUUID(), 4, BigDecimal("4115.17"), BigDecimal.ZERO, BigDecimal("4115.17")) }
        val text = render(
            "synthese-eleves",
            templates.studentInvoiceSummary("SE-2026-003", 2026, term, drafts, emptyMap(), BigDecimal("12345.50"), isDraft = false),
        )

        assertTrue("12\u00A0345,50\u00A0€" in text, "montant total mal formaté")
        assertFalse('\u202F' in text, "espace fine insécable absente des polices")
        assertFalse("BROUILLON" in text)
    }

    @Test
    fun `long teacher request paginates with numbered pages`() {
        val lines = (1..40).map { index ->
            TeacherInvoiceWeeklyLine(index, LocalDate.of(2026, 1, 5).plusWeeks(index - 1L), BigDecimal("2.50"), BigDecimal("135.00"))
        }
        val adjustment = TeacherBillingAdjustment(UUID.randomUUID(), teacher.id!!, UUID.randomUUID(), BigDecimal("-27.00"), "Séance annulée non décomptée", "DP-2026-014")
        val draft = TeacherInvoiceRequestDraft(teacher.id!!, lines, BigDecimal("100.00"), BigDecimal("5373.00"), adjustments = listOf(adjustment))
        val text = render(
            "demande-prestataire",
            templates.teacherInvoiceRequest("DP-2026-015", 2026, term, teacher, draft, isDraft = false, installmentNumber = 2, periodStartWeek = 36, periodEndWeek = 51),
        )

        listOf("Demande de facture", "Situation n°2", "Régularisations", "Séance annulée non décomptée", "2,5\u00A0h", "Page 1 / 2", "Page 2 / 2")
            .forEach { assertTrue(it in text, "« $it » absent du PDF") }
    }

    @Test
    fun `credit note embeds the Partition fonts`() {
        val bytes = pdf.render(templates.creditNote("AV-2026-002", "FE-2026-020", "Erreur de cotisation", BigDecimal("-15.00")))
        Files.createDirectories(Path.of("build/pdf-preview"))
        Files.write(Path.of("build/pdf-preview/avoir.pdf"), bytes)

        PDDocument.load(bytes).use { document ->
            val fonts = document.pages.flatMap { page -> page.resources.fontNames.map { page.resources.getFont(it).name } }.toSet()
            listOf("InstrumentSerif", "HankenGrotesk", "IBMPlexMono").forEach { family ->
                assertTrue(fonts.any { family in it }, "police $family non embarquée : $fonts")
            }
            assertEquals(1, document.numberOfPages)
            assertTrue("MOTIF DE LA CORRECTION" in PDFTextStripper().getText(document))
        }
    }

    private fun render(name: String, html: String): String {
        val relativePath = pdf.writeHtmlPdf("$name.pdf", html)
        return PDDocument.load(Path.of("build/pdf-preview", relativePath).toFile()).use { PDFTextStripper().getText(it) }
    }
}
