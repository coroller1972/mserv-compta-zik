package fr.spiritbox.compta.services

import fr.spiritbox.compta.domain.MusicianEntity
import fr.spiritbox.compta.domain.TeacherEntity
import fr.spiritbox.compta.domain.TermEntity
import fr.spiritbox.compta.openapi.model.StudentInvoiceDraft
import fr.spiritbox.compta.openapi.model.TeacherInvoiceRequestDraft
import fr.spiritbox.compta.services.PdfTheme.BRASS
import fr.spiritbox.compta.services.PdfTheme.BRASS_TINT
import fr.spiritbox.compta.services.PdfTheme.BRASS_TINT_INK
import fr.spiritbox.compta.services.PdfTheme.CARD_ALT
import fr.spiritbox.compta.services.PdfTheme.FONT_DISPLAY
import fr.spiritbox.compta.services.PdfTheme.FONT_MONO
import fr.spiritbox.compta.services.PdfTheme.FONT_UI
import fr.spiritbox.compta.services.PdfTheme.INK
import fr.spiritbox.compta.services.PdfTheme.INK_FAINT
import fr.spiritbox.compta.services.PdfTheme.INK_MUTED
import fr.spiritbox.compta.services.PdfTheme.INK_SOFT
import fr.spiritbox.compta.services.PdfTheme.LINE
import fr.spiritbox.compta.services.PdfTheme.LINE_STRONG
import fr.spiritbox.compta.services.PdfTheme.WORDMARK_ACCENT
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.*

@ApplicationScoped
class DocumentTemplateService {
    private val moneyFormatter = NumberFormat.getCurrencyInstance(Locale.FRANCE)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val hoursFormatter = NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = 2 }
    private val markDataUri by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/pdf-assets/compta-zik-mark.png")) {
            "PDF logo mark resource is missing."
        }.use { it.readBytes() }
        "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
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
        subtitle = "${term.name} · $year",
        recipientLabel = "Élève",
        recipient = musician.fullName(),
        total = draft.totalAmount,
        body = """
            <table>
              <thead>
                <tr>
                  <th>Désignation</th>
                  <th class="num">Quantité</th>
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
        subtitle = "${term.name} · $year",
        recipientLabel = "Période",
        recipient = "${term.name} $year",
        total = total,
        body = """
            <p class="notice">Seuls les cours donnent droit au Bonus Lien Social.</p>
            <table>
              <thead>
                <tr>
                  <th>Élève</th>
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
            append("${term.name} · $year")
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
                      <td class="mono">S${line.week}</td>
                      <td class="mono">${line.date.format(dateFormatter).escapeHtml()}</td>
                      <td class="num">${hours(line.hours)}</td>
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
                      <tr><td class="mono">${(adjustment.sourceDocumentNumber ?: adjustment.sourceDocumentId.toString()).escapeHtml()}</td><td>${adjustment.reason.escapeHtml()}</td><td class="num">${money(adjustment.amount)}</td></tr>
                    """.trimIndent() }}</tbody>
                  </table>
                """.trimIndent()
            }
        """.trimIndent(),
        isDraft = isDraft,
    )

    /** Gabarit commun — DA « Partition » (compta-zik-frontend/docs/design/partition.md), toujours en clair. */
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
              @page {
                size: A4;
                margin: 16mm 16mm 20mm;
                @bottom-left {
                  content: "Compta Zik · document généré automatiquement par la section musique";
                  color: $INK_MUTED; font-family: '$FONT_MONO'; font-size: 7.5px; vertical-align: top; padding-top: 6mm;
                }
                @bottom-right {
                  content: "Page " counter(page) " / " counter(pages);
                  color: $INK_MUTED; font-family: '$FONT_MONO'; font-size: 7.5px; vertical-align: top; padding-top: 6mm;
                }
              }
              * { box-sizing: border-box; }
              html, body { background: #ffffff; }
              body { color: $INK; font-family: '$FONT_UI'; font-size: 11px; line-height: 1.5; margin: 0; }
              .masthead { border-bottom: 1px solid $LINE; display: table; margin-bottom: 22px; padding-bottom: 14px; width: 100%; }
              .brand-cell, .doc-cell { display: table-cell; vertical-align: middle; }
              .mark { border-radius: 8px; display: inline-block; height: 30px; margin-right: 9px; vertical-align: middle; width: 30px; }
              .wordmark { color: $INK; font-family: '$FONT_DISPLAY'; font-size: 22px; line-height: 1; vertical-align: middle; }
              .wordmark em { color: $WORDMARK_ACCENT; font-style: italic; }
              .doc-cell { text-align: right; }
              .eyebrow { color: $INK_MUTED; display: block; font-family: '$FONT_MONO'; font-size: 9px; }
              .doc-number { color: $INK; display: block; font-family: '$FONT_MONO'; font-size: 12px; font-weight: 500; margin-top: 2px; }
              .title-block { margin-bottom: 18px; }
              h1 { color: $INK; font-family: '$FONT_DISPLAY'; font-size: 38px; font-weight: 400; line-height: 1; margin: 4px 0 0; }
              h2 { color: $INK; font-size: 13px; font-weight: 700; margin: 22px 0 0; }
              .draft-banner { background: $BRASS_TINT; border: 1.5px dashed $BRASS; border-radius: 10px; color: $BRASS_TINT_INK; font-size: 9px; font-weight: 700; letter-spacing: .12em; margin: 0 0 18px; padding: 8px 12px; text-align: center; text-transform: uppercase; }
              .meta { display: table; margin-bottom: 22px; table-layout: fixed; width: 100%; }
              .meta-cell { display: table-cell; vertical-align: top; width: 50%; }
              .meta-gap { display: table-cell; width: 12px; }
              .meta-card { background: $CARD_ALT; border: 1px solid $LINE; border-radius: 12px; padding: 12px 14px; }
              .label { color: $INK_FAINT; display: block; font-size: 8px; font-weight: 700; letter-spacing: .08em; margin-bottom: 4px; text-transform: uppercase; }
              .value { color: $INK; font-size: 13px; font-weight: 700; }
              .meta-card.document .value { font-family: '$FONT_MONO'; font-weight: 500; }
              .notice { background: $BRASS_TINT; border-radius: 10px; color: $BRASS_TINT_INK; margin: 0 0 14px; padding: 9px 12px; }
              .message-card { background: $CARD_ALT; border: 1px solid $LINE; border-radius: 12px; margin: 4px 0 18px; padding: 14px 16px; }
              .message-card strong { color: $INK; display: block; font-size: 13px; }
              .message-card p { color: $INK_MUTED; margin: 6px 0 0; }
              table { -fs-table-paginate: paginate; border-collapse: collapse; margin-top: 8px; width: 100%; }
              thead { display: table-header-group; }
              tbody { display: table-row-group; }
              tr { break-inside: avoid; page-break-inside: avoid; }
              th { border-bottom: 1px solid $LINE_STRONG; color: $INK_FAINT; font-size: 8px; font-weight: 700; letter-spacing: .08em; padding: 8px 10px; text-align: left; text-transform: uppercase; }
              td { border-bottom: 1px solid $LINE; color: $INK; padding: 9px 10px; }
              .num { text-align: right; white-space: nowrap; }
              .mono { color: $INK_SOFT; font-family: '$FONT_MONO'; font-size: 10px; }
              .total { background: $CARD_ALT; border: 1px solid $LINE; border-radius: 12px; margin-top: 22px; padding: 12px 18px; }
              .total-layout { display: table; width: 100%; }
              .total-label, .total-amount { display: table-cell; vertical-align: middle; }
              .total-label { color: $INK_FAINT; font-size: 9px; font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }
              .total-amount { color: $INK; font-family: '$FONT_DISPLAY'; font-size: 32px; line-height: 1; text-align: right; }
            </style>
          </head>
          <body>
            <section class="masthead">
              <div class="brand-cell">
                <img class="mark" src="$markDataUri" alt="" />
                <span class="wordmark">Compta <em>Zik</em></span>
              </div>
              <div class="doc-cell">
                <span class="eyebrow">Document comptable</span>
                <span class="doc-number">${documentNumber.escapeHtml()}</span>
              </div>
            </section>
            <section class="title-block">
              <span class="eyebrow">${subtitle.escapeHtml()}</span>
              <h1>${title.escapeHtml()}</h1>
            </section>
            ${if (isDraft) "<div class=\"draft-banner\">Brouillon · non validé</div>" else ""}
            <section class="meta">
              <div class="meta-cell">
                <div class="meta-card document">
                  <span class="label">Document</span>
                  <span class="value">${documentNumber.escapeHtml()}</span>
                </div>
              </div>
              <div class="meta-gap"></div>
              <div class="meta-cell">
                <div class="meta-card">
                  <span class="label">${recipientLabel.escapeHtml()}</span>
                  <span class="value">${recipient.escapeHtml()}</span>
                </div>
              </div>
            </section>
            $body
            <section class="total">
              <div class="total-layout">
                <span class="total-label">Total</span>
                <span class="total-amount">${money(total)}</span>
              </div>
            </section>
          </body>
        </html>
    """.trimIndent()

    // Les polices embarquées n'ont pas l'espace fine insécable (U+202F) que Java place entre les milliers :
    // on la remplace par une espace insécable ordinaire, présente partout.
    private fun money(value: BigDecimal) = moneyFormatter.format(value).replace('\u202F', '\u00A0')

    private fun hours(value: BigDecimal) = "${hoursFormatter.format(value).replace('\u202F', '\u00A0')}\u00A0h"

    private fun MusicianEntity.fullName() = "$firstName $lastName".trim()

    private fun TeacherEntity.fullName() = "$firstName $lastName".trim()

    private fun String.escapeHtml() =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
