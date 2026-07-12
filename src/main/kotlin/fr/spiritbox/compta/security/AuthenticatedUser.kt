package fr.spiritbox.compta.security

import jakarta.enterprise.context.RequestScoped

@RequestScoped
class AuthenticatedUser {
    var userId: String = "system"
    var displayName: String = "Système"
}
