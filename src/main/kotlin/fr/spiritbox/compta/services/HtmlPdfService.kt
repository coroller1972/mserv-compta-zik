package fr.spiritbox.compta.services

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

@ApplicationScoped
class HtmlPdfService(
    @ConfigProperty(name = "compta.documents.storage-dir", defaultValue = "documents")
    private val storageDirectory: String,
) {
    fun writeHtmlPdf(relativePath: String, html: String): String {
        val safeRelativePath = relativePath.replace(Regex("""(^/|[\\])"""), "_")
        val storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize()
        val outputPath = storageRoot.resolve(safeRelativePath).normalize()
        require(outputPath.startsWith(storageRoot)) { "Invalid document path." }

        Files.createDirectories(outputPath.parent)
        Files.write(outputPath, render(html))
        return safeRelativePath
    }

    private fun render(html: String): ByteArray {
        val output = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(html, null)
            .toStream(output)
            .run()
        return output.toByteArray()
    }
}
