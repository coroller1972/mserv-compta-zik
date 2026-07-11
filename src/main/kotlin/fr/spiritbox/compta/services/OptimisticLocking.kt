package fr.spiritbox.compta.services

import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response

object OptimisticLocking {
    fun requireVersion(expected: Long?, current: Long, resource: String) {
        if (expected == null || expected != current) {
            throw ClientErrorException(
                "$resource was modified by another user. Reload it before saving.",
                Response.Status.CONFLICT,
            )
        }
    }
}
