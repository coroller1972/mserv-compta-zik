package fr.spiritbox.compta.services

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

@ApplicationScoped
class HtmlPdfService(
    @ConfigProperty(name = "compta.documents.storage-dir", defaultValue = "documents")
    private val storageDirectory: String,
) {
    private val fontBytes by lazy {
        PdfTheme.fonts.associateWith { font ->
            checkNotNull(javaClass.getResourceAsStream(font.resource)) { "PDF font ${font.resource} is missing." }
                .use { it.readBytes() }
        }
    }

    fun writeHtmlPdf(relativePath: String, html: String): String {
        val safeRelativePath = relativePath.replace(Regex("""(^/|[\\])"""), "_")
        val storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize()
        val outputPath = storageRoot.resolve(safeRelativePath).normalize()
        require(outputPath.startsWith(storageRoot)) { "Invalid document path." }

        Files.createDirectories(outputPath.parent)
        Files.write(outputPath, render(html))
        return safeRelativePath
    }

    fun render(html: String): ByteArray {
        val output = ByteArrayOutputStream()
        val builder = PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(html, null)
        fontBytes.forEach { (font, bytes) ->
            builder.useFont({ ByteArrayInputStream(bytes) }, font.family, font.weight, font.style, true)
        }
        builder.toStream(output).run()
        return output.toByteArray()
    }
}
