package fr.spiritbox.compta.security

import jakarta.enterprise.context.RequestScoped
import jakarta.ws.rs.ForbiddenException

@RequestScoped
class AuthenticatedUser {
    var userId: String = "system"
    var displayName: String = "Système"
    var roles: Set<String> = emptySet()
    var permissions: Set<String> = emptySet()

    fun can(permission: String): Boolean = "ADMINISTRATOR" in roles || permission in permissions

    fun canAny(vararg permissions: String): Boolean = permissions.any(::can)

    fun requirePermission(permission: String) {
        if (!can(permission)) throw ForbiddenException("Permission $permission required.")
    }
}
