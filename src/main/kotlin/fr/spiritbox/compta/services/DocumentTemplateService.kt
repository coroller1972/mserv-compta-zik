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
    private val logoDataUri by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/pdf-assets/compta-zik-logo.jpg")) {
            "PDF logo resource is missing."
        }.use { it.readBytes() }
        "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    fun creditNote(documentNumber: String, originalNumber: String, reason: String, total: BigDecimal) = page(
        title = "Avoir",
        documentNumber = documentNumber,
        subtitle = "Correction du document $originalNumber",
        recipientLabel = "Document d'origine",
        recipient = originalNumber,
        total = total,
        body = """
            <section class="message-card">
              <span class="label">Motif de la correction</span>
              <strong>${reason.escapeHtml()}</strong>
              <p>Cet avoir annule le montant du document d'origine.</p>
            </section>
        """.trimIndent(),
        isDraft = false,
    )

    fun studentInvoice(
        documentNumber: String,
        year: Int,
        term: TermEntity,
        musician: MusicianEntity,
        draft: StudentInvoiceDraft,
        isDraft: Boolean,
    ) = page(
        title = "Facture élève",
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
        isDraft = isDraft,
    )

    fun studentInvoiceSummary(
        documentNumber: String,
        year: Int,
        term: TermEntity,
        drafts: List<StudentInvoiceDraft>,
        musiciansById: Map<UUID, MusicianEntity>,
        total: BigDecimal,
        isDraft: Boolean,
    ) = page(
        title = "Synthèse factures élèves",
        documentNumber = documentNumber,
        subtitle = "${term.name} $year",
        recipientLabel = "Periode",
        recipient = "${term.name} $year",
        total = total,
        body = """
            <p class="notice">Seuls les cours donnent droit au Bonus Lien Social.</p>
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
        isDraft = isDraft,
    )

    fun teacherInvoiceRequest(
        documentNumber: String,
        year: Int,
        term: TermEntity,
        teacher: TeacherEntity,
        draft: TeacherInvoiceRequestDraft,
        isDraft: Boolean,
        installmentNumber: Int? = null,
        periodStartWeek: Int? = null,
        periodEndWeek: Int? = null,
    ) = page(
        title = "Demande de facture",
        documentNumber = documentNumber,
        subtitle = buildString {
            append("${term.name} $year")
            if (installmentNumber != null) append(" · Situation n°$installmentNumber")
            if (periodStartWeek != null && periodEndWeek != null) append(" · Semaines $periodStartWeek à $periodEndWeek")
        },
        recipientLabel = "Prestataire",
        recipient = teacher.fullName(),
        total = draft.totalAmount,
        body = """
            <table>
              <thead>
                <tr>
                  <th>Semaine</th>
                  <th>Début de semaine</th>
                  <th class="num">Heures</th>
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
                      <td class="num">${line.hours}</td>
                      <td class="num">${money(line.amount)}</td>
                    </tr>
                    """.trimIndent()
            }
        }
              </tbody>
            </table>
            ${
                if (draft.adjustments.orEmpty().isEmpty()) "" else """
                  <h2>Régularisations</h2>
                  <table>
                    <thead><tr><th>Document d'origine</th><th>Motif</th><th class="num">Montant</th></tr></thead>
                    <tbody>${draft.adjustments.orEmpty().joinToString("\n") { adjustment -> """
                      <tr><td>${(adjustment.sourceDocumentNumber ?: adjustment.sourceDocumentId.toString()).escapeHtml()}</td><td>${adjustment.reason.escapeHtml()}</td><td class="num">${money(adjustment.amount)}</td></tr>
                    """.trimIndent() }}</tbody>
                  </table>
                """.trimIndent()
            }
        """.trimIndent(),
        isDraft = isDraft,
    )

    private fun page(
        title: String,
        documentNumber: String,
        subtitle: String,
        recipientLabel: String,
        recipient: String,
        total: BigDecimal,
        body: String,
        isDraft: Boolean,
    ) = """
        <html xmlns="http://www.w3.org/1999/xhtml" lang="fr">
          <head>
            <meta charset="utf-8" />
            <style>
              @page { size: A4; margin: 16mm 16mm 18mm; }
              * { box-sizing: border-box; }
              html, body { background: #ffffff; }
              body { color: #172027; font-family: Helvetica, Arial, sans-serif; font-size: 11px; line-height: 1.5; }
              .header { background: #09151b; border-bottom: 5px solid #f2ae31; color: #ffffff; margin-bottom: 22px; padding: 17px 19px; }
              .header-layout { display: table; width: 100%; }
              .logo-cell { display: table-cell; vertical-align: middle; width: 64px; }
              .logo { background: #fffefa; border: 1px solid rgba(255, 255, 255, .18); border-radius: 7px; display: block; height: 52px; width: 52px; }
              .title-cell { display: table-cell; padding-left: 14px; vertical-align: middle; }
              .brand { color: #72d0b8; font-size: 9px; font-weight: 700; letter-spacing: .16em; text-transform: uppercase; }
              h1 { color: #ffffff; font-size: 25px; font-weight: 700; letter-spacing: -.025em; line-height: 1.1; margin: 5px 0 3px; }
              .subtitle { color: #bdc9cc; font-size: 11px; }
              .document-chip { color: #f2ae31; display: table-cell; font-size: 9px; font-weight: 700; letter-spacing: .08em; text-align: right; text-transform: uppercase; vertical-align: top; white-space: nowrap; }
              .meta { border-collapse: separate; border-spacing: 8px 0; display: table; margin: 0 -8px 24px; width: calc(100% + 16px); }
              .meta-card { background: #f3f1ec; border-left: 3px solid #176b5d; display: table-cell; padding: 13px 14px; width: 50%; }
              .meta-card + .meta-card { border-left-color: #f2ae31; }
              .label { color: #69747d; display: block; font-size: 8px; font-weight: 700; letter-spacing: .1em; margin-bottom: 5px; text-transform: uppercase; }
              .value { color: #172027; font-size: 12px; font-weight: 700; }
              .notice { background: #fff8e8; border-left: 3px solid #f2ae31; color: #654c19; margin: 0 0 14px; padding: 9px 11px; }
              .message-card { background: #f3f1ec; border-left: 3px solid #f2ae31; margin: 10px 0 18px; padding: 15px; }
              .message-card strong { color: #172027; display: block; font-size: 13px; }
              .message-card p { color: #69747d; margin: 7px 0 0; }
              table { -fs-table-paginate: paginate; border-collapse: separate; border-spacing: 0; margin-top: 10px; width: 100%; }
              thead { display: table-header-group; }
              tbody { display: table-row-group; }
              tr { break-inside: avoid; page-break-inside: avoid; }
              th { background: #123f49; border-top: 3px solid #f2ae31; color: #ffffff; font-size: 8px; letter-spacing: .06em; padding: 9px 10px; text-align: left; text-transform: uppercase; }
              td { border-bottom: 1px solid #dfe3e1; padding: 9px 10px; }
              tr:nth-child(even) td { background: #f8f7f3; }
              .num { text-align: right; }
              .total { background: #09151b; border-top: 4px solid #f2ae31; margin-top: 22px; padding: 14px 17px; text-align: right; }
              .total span { color: #aeb9bd; display: block; font-size: 8px; font-weight: 700; letter-spacing: .12em; text-transform: uppercase; }
              .total strong { color: #72d0b8; font-size: 23px; font-weight: 700; }
              .footer { border-top: 1px solid #dfe3e1; color: #879196; display: table; font-size: 8px; margin-top: 19px; padding-top: 8px; width: 100%; }
              .footer-brand { color: #176b5d; display: table-cell; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
              .footer-copy { display: table-cell; text-align: right; }
              .draft-banner { background: #fff8e8; border: 1px solid #f2ae31; color: #8a5b08; font-size: 10px; font-weight: 700; letter-spacing: .11em; margin: -10px 0 18px; padding: 7px 10px; text-align: center; }
            </style>
          </head>
          <body>
            <section class="header">
              <div class="header-layout">
                <div class="logo-cell"><img class="logo" src="$logoDataUri" alt="Compta Zik" /></div>
                <div class="title-cell">
                  <div class="brand">Compta Zik · Section musique</div>
                  <h1>${title.escapeHtml()}</h1>
                  <div class="subtitle">${subtitle.escapeHtml()}</div>
                </div>
                <div class="document-chip">Document comptable</div>
              </div>
            </section>
            ${if (isDraft) "<div class=\"draft-banner\">BROUILLON - NON VALIDÉ</div>" else ""}
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
            <section class="footer">
              <span class="footer-brand">Compta Zik</span>
              <span class="footer-copy">Document généré automatiquement par la section musique.</span>
            </section>
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
