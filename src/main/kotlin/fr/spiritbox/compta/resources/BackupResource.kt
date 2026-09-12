package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.BackupsApi
import fr.spiritbox.compta.services.BackupService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@ApplicationScoped
@Tag(name = "Backups")
class BackupResource(private val backups: BackupService) : BackupsApi {
    override suspend fun createBackup(): Response = Response.status(201).entity(backups.create()).build()

    override suspend fun listBackups(): Response = Response.ok(backups.list()).build()

    override suspend fun downloadBackup(fileName: String): Response =
        Response.ok(backups.download(fileName).toFile())
            .header("Content-Disposition", "attachment; filename=\"$fileName\"")
            .header("Cache-Control", "no-store").build()

    override suspend fun analyzeBackup(body: ByteArray): Response = Response.ok(backups.analyze(body)).build()

    override suspend fun restoreBackup(xRestoreConfirmation: String, body: ByteArray): Response =
        Response.ok(backups.restore(body, xRestoreConfirmation)).build()
}
