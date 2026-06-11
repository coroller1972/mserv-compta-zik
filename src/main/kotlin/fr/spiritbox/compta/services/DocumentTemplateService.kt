package fr.spiritbox.compta.services

import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.StudentInvoiceDraft
import fr.spiritbox.compta.openapi.model.TeacherInvoiceRequestDraft
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.*

@ApplicationScoped
class DocumentTemplateService {
    private val moneyFormatter = NumberFormat.getCurrencyInstance(Locale.FRANCE)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun studentInvoice(
        documentNumber: String,
        year: Int,
        term: TermEntity,
        musician: MusicianEntity,
        draft: StudentInvoiceDraft,
    ) = page(
        title = "Facture eleve",
        documentNumber = documentNumber,
        subtitle = "${term.name} $year",
        recipientLabel = "Eleve",
        recipient = musician.fullName(),
        total = draft.totalAmount,
        body = """
            <table>
              <thead>
                <tr>
                  <th>Designation</th>
                  <th class="num">Quantite</th>
                  <th class="num">Montant</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>Cours individuels</td>
                  <td class="num">${draft.individualCourseCount}</td>
                  <td class="num">${money(draft.individualCourseAmount)}</td>
                </tr>
                <tr>
                  <td>Cotisation groupe annuelle</td>
                  <td class="num">${if (draft.groupMembershipAmount > BigDecimal.ZERO) "1" else "0"}</td>
                  <td class="num">${money(draft.groupMembershipAmount)}</td>
                </tr>
              </tbody>
            </table>
        """.trimIndent(),
    )

    fun studentInvoiceSummary(
        documentNumber: String,
        year: Int,
        term: TermEntity,
        drafts: List<StudentInvoiceDraft>,
        musiciansById: Map<UUID, MusicianEntity>,
        total: BigDecimal,
    ) = page(
        title = "Synthese factures eleves",
        documentNumber = documentNumber,
        subtitle = "${term.name} $year",
        recipientLabel = "Periode",
        recipient = "${term.name} $year",
        total = total,
        body = """
            <p>Seuls les cours donnent le droit au Bonus Lien Social</p>
            <table>
              <thead>
                <tr>
                  <th>Eleve</th>
                  <th class="num">Cours</th>
                  <th class="num">Montant cours</th>
                  <th class="num">Cotisation</th>
                </tr>
              </thead>
              <tbody>
                ${
            drafts.joinToString("\n") { draft ->
                val musician = musiciansById[draft.musicianId]
                val name = musician?.fullName() ?: draft.musicianId.toString()
                """
                    <tr>
                      <td>${name.escapeHtml()}</td>
                      <td class="num">${draft.individualCourseCount}</td>
                      <td class="num">${money(draft.individualCourseAmount)}</td>
                      <td class="num">${money(draft.groupMembershipAmount)}</td>
                    </tr>
                    """.trimIndent()
            }
        }
              </tbody>
            </table>
        """.trimIndent(),
    )

    fun teacherInvoiceRequest(
        documentNumber: String,
        year: Int,
        term: TermEntity,
        teacher: TeacherEntity,
        draft: TeacherInvoiceRequestDraft,
    ) = page(
        title = "Demande de facture professeur",
        documentNumber = documentNumber,
        subtitle = "${term.name} $year",
        recipientLabel = "Professeur",
        recipient = teacher.fullName(),
        total = draft.totalAmount,
        body = """
            <table>
              <thead>
                <tr>
                  <th>Semaine</th>
                  <th>Date</th>
                  <th class="num">Montant</th>
                </tr>
              </thead>
              <tbody>
                ${
            draft.weeklyLines.joinToString("\n") { line ->
                """
                    <tr>
                      <td>S${line.week}</td>
                      <td>${line.date.format(dateFormatter).escapeHtml()}</td>
                      <td class="num">${money(line.amount)}</td>
                    </tr>
                    """.trimIndent()
            }
        }
              </tbody>
            </table>
        """.trimIndent(),
    )

    private fun page(
        title: String,
        documentNumber: String,
        subtitle: String,
        recipientLabel: String,
        recipient: String,
        total: BigDecimal,
        body: String,
    ) = """
        <html xmlns="http://www.w3.org/1999/xhtml" lang="fr">
          <head>
            <meta charset="utf-8" />
            <style>
              @page { size: A4; margin: 22mm 18mm; }
              body { color: #172033; font-family: Arial, sans-serif; font-size: 12px; line-height: 1.45; }
              .header { border-bottom: 3px solid #0f766e; padding-bottom: 18px; margin-bottom: 28px; }
              .brand { color: #0f766e; font-size: 13px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
              h1 { font-size: 28px; margin: 7px 0 4px; }
              .subtitle { color: #667085; font-size: 14px; }
              .meta { display: table; width: 100%; margin: 0 0 26px; }
              .meta-card { background: #f6f8fb; border: 1px solid #d9e2ec; display: table-cell; padding: 14px; width: 50%; }
              .meta-card + .meta-card { border-left: 0; }
              .label { color: #667085; display: block; font-size: 10px; font-weight: 700; margin-bottom: 5px; text-transform: uppercase; }
              .value { font-size: 14px; font-weight: 700; }
              table { -fs-table-paginate: paginate; border-collapse: separate; border-spacing: 0; margin-top: 12px; width: 100%; }
              thead { display: table-header-group; }
              tbody { display: table-row-group; }
              tr { break-inside: avoid; page-break-inside: avoid; }
              th { background: #0f766e; color: white; font-size: 11px; padding: 9px; text-align: left; text-transform: uppercase; }
              td { border-bottom: 1px solid #e4e7ec; padding: 10px 9px; }
              tr:nth-child(even) td { background: #f9fafb; }
              .num { text-align: right; }
              .total { margin-top: 24px; text-align: right; }
              .total span { color: #667085; display: block; font-size: 11px; font-weight: 700; text-transform: uppercase; }
              .total strong { color: #0f766e; font-size: 24px; }
              .hours-total { color: #667085; margin-top: 14px; text-align: right; }
              .footer { border-top: 1px solid #e4e7ec; color: #98a2b3; font-size: 10px; margin-top: 22px; padding-top: 8px; width: 100%; }
            </style>
          </head>
          <body>
            <section class="header">
              <div class="brand">Compta musique</div>
              <h1>${title.escapeHtml()}</h1>
              <div class="subtitle">${subtitle.escapeHtml()}</div>
            </section>
            <section class="meta">
              <div class="meta-card">
                <span class="label">Document</span>
                <span class="value">${documentNumber.escapeHtml()}</span>
              </div>
              <div class="meta-card">
                <span class="label">${recipientLabel.escapeHtml()}</span>
                <span class="value">${recipient.escapeHtml()}</span>
              </div>
            </section>
            $body
            <section class="total">
              <span>Total</span>
              <strong>${money(total)}</strong>
            </section>
            <section class="footer">Document genere automatiquement par Compta musique.</section>
          </body>
        </html>
    """.trimIndent()

    private fun money(value: BigDecimal) = moneyFormatter.format(value)

    private fun MusicianEntity.fullName() = "$firstName $lastName".trim()

    private fun TeacherEntity.fullName() = "$firstName $lastName".trim()

    private fun String.escapeHtml() =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
