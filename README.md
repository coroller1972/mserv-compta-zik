# mserv-compta

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./gradlew quarkusDev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Database migrations

Database schema migrations are managed by Flyway and run automatically at application startup.

Migration files live in:

```text
src/main/resources/db/migration
```

Use Flyway naming for future changes, for example:

```text
V2__add_invoice_payment_status.sql
```

Hibernate validates the mapped schema at startup and no longer recreates tables in dev mode.

## Packaging and running the application

The application can be packaged using:

```shell script
./gradlew build
```

It produces the `quarkus-run.jar` file in the `build/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `build/quarkus-app/lib/` directory.

The application is now runnable using `java -jar build/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./gradlew build -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar build/*-runner.jar`.

## Container image

The container image is built by GitHub Actions from
`src/main/docker/Dockerfile.jvm.staged` and pushed to:

```text
ghcr.io/coroller1972/mserv-compta-zik:latest
```

This keeps the Gradle download out of the ROSA build path. ROSA only pulls the
already built runtime image.

## Deploying on OpenShift / ROSA

From the target namespace, apply the manifest:

```shell script
oc apply -f deploy.yml
oc rollout status deployment/mserv-compta-zik
```

The build uses Java 25, matching the Gradle configuration in `build.gradle.kts`.
Do not deploy this service with a generic Java 17 S2I/runtime image unless the
project is first downgraded to Java 17.

## Creating a native executable

You can create a native executable using:

```shell script
./gradlew build -Dquarkus.native.enabled=true
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./build/mserv-compta-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/gradle-tooling>.

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): Build RESTful web services and APIs using Jakarta REST (formerly JAX-RS)
- SmallRye OpenAPI ([guide](https://quarkus.io/guides/openapi-swaggerui)): Generate OpenAPI schemas and serve Swagger UI for REST API documentation
- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- Kotlin ([guide](https://quarkus.io/guides/kotlin)): Write your services in Kotlin
- Reactive PostgreSQL client ([guide](https://quarkus.io/guides/reactive-sql-clients)): Connect to the PostgreSQL database using the reactive pattern

## Provided Code

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)


## Validation des sessions métier

Pour la pile complète, voir [la configuration ES256](../docs/es256-configuration.md) et [le `.env.example` à la racine](../.env.example). Le fichier de déploiement de ce service s'appelle `deploy.yml` ; les variables de signature et les fichiers de clés sont réservés à Identity.

Le backend vérifie la signature **ES256** des JWT avec SmallRye JWT et les clés publiques publiées par Identity sur `GET /api/auth/.well-known/jwks.json`. Il contrôle `kid`, `typ=JWT`, l’émetteur, l’audience, l’expiration et les dates `iat`/`nbf`, et exige les claims du contrat Identity (`iss`, `sub`, `aud`, `exp`, `iat`, `nbf`, `jti`, `sid`, `av`, `roles`, `permissions`). Il refuse HS256 et les tokens non signés, sans tolérance d’expiration. Aucun secret JWT ni clé privée n’est nécessaire dans ce service.

| Variable | Valeur attendue |
| --- | --- |
| `AUTH_IDENTITY_URL` | URL de confiance incluant `/api/auth`, par exemple `http://mserv-identity:8083/api/auth` dans Compose. Utiliser HTTPS hors d’un réseau interne protégé. |
| `AUTH_JWT_ISSUER` | Exactement l’émetteur configuré dans Identity. Obligatoire en production. |
| `AUTH_JWT_AUDIENCE` | Exactement l’audience configurée dans Identity. Obligatoire en production. |

En développement, les valeurs par défaut sont `http://localhost:8083/api/auth`, `identity-auth` et `identity-api`. Les deux dernières peuvent être surchargées par les variables d’environnement. Voir [`.env.example`](.env.example). Compose transmet les variables et OpenShift utilise le même émetteur que le Secret `mserv-identity-jwt`, avec l’audience `identity-api` du manifeste Identity ; adapter les deux déploiements ensemble si nécessaire.

Le téléchargement JWKS utilise le client HTTP réactif, sans redirection ni URL issue du JWT (`jku`, `x5u`, clés embarquées, etc.). Seules des clés publiques EC P-256, identifiées par un `kid` unique et déclarées `alg=ES256`/`use=sig`, sont acceptées. Les clés sont mises en cache pendant 300 secondes ; un `kid` inconnu déclenche un rafraîchissement, limité à une tentative toutes les 30 secondes. Les requêtes concurrentes partagent le téléchargement en cours. Un JWKS indisponible ou invalide lors d’un chargement obligatoire produit un 503, sans réutiliser un cache expiré.

Après validation cryptographique, le backend appelle `/session` pour chaque requête avec le Bearer de l’utilisateur et vérifie que l’utilisateur retourné correspond au `sub` signé. Les rôles et permissions utilisés restent ceux de cette réponse à jour, sans cache de session.

Les appels Identity utilisent des délais de connexion et lecture de 2 et 3 secondes. Une session révoquée ou un compte désactivé est refusé ; changement de mot de passe et enrôlement MFA obligatoires bloquent les accès métier. Une identité indisponible produit un 503. Le service d’identité doit donc être joignable avant d’utiliser l’API métier.

Avant de déployer cette version, utiliser Identity avec sa migration ES256, publier le JWKS et basculer son émission en ES256. La configuration Compose monte ses clés publiques et privées uniquement dans Identity. Les anciens access tokens HS256 sont refusés ; les utilisateurs peuvent obtenir un token ES256 via leur refresh ou une reconnexion. Ne pas distribuer la clé privée d’Identity à Compta.

Pour une rotation, publier d’abord l’ancienne et la nouvelle clé publique sur toutes les instances Identity, attendre au moins 300 secondes, puis changer la clé de signature. Conserver l’ancienne clé publique jusqu’à expiration des tokens précédents. Après un redémarrage d’Identity en mode dev avec des clés éphémères, un token portant le nouveau `kid` déclenche le rafraîchissement (sous réserve de la limite de 30 secondes).

`IdentityJwtVerifierTest` vérifie les signatures, les claims, le refus de HS256, les clés invalides, la rotation et les téléchargements concurrents. `BusinessAuthorizationTest` exerce les endpoints avec PostgreSQL et un serveur HTTP d’identité simulé émettant de vrais JWT ES256 ; il contrôle aussi les révocations, les changements de permissions, le refus local avant `/session` et la cohérence de l’utilisateur retourné. Les parcours Identity sont couverts par `AuthResourceTest` et `JwtKeysResourceTest` dans son dépôt.

```sh
./gradlew test --tests '*IdentityJwtVerifierTest' --tests '*BusinessAuthorizationTest'
./gradlew build
```

Le parseur est utilisé explicitement afin de conserver le filtre métier et ses erreurs `application/problem+json`, conformément à la prise en charge de [SmallRye JWT sans mécanisme HTTP automatique](https://quarkus.io/guides/security-jwt/#how-to-add-smallrye-jwt-directly).

## Sauvegardes complètes

L'import JSON crée désormais une archive ZIP complète avant tout remplacement. Les routes `/api/data/backups` et `/api/data/restore` permettent aussi de créer, télécharger et restaurer ces archives avec la permission `IMPORT_EXPORT`. Voir [le contrat de sauvegarde](../docs/api-contract.md#sauvegarde-complète-et-restauration) pour le format, les confirmations et les limites. Les comptes du service d'identité sont exclus.

Le volume `compta.documents.storage-dir` doit être persistant et disposer d'espace pour les PDF et les archives. Copier les ZIP hors du serveur ; aucune rétention automatique n'efface les sauvegardes. La restauration utilise de nouveaux chemins pour préserver les fichiers existants en cas d'échec. Configurer le proxy pour autoriser les corps binaires jusqu'à 64 Mio ; Quarkus applique cette limite HTTP, et l'archive a des limites d'expansion supplémentaires.

`./gradlew test --tests '*BackupResourceTest'` valide sur PostgreSQL isolé le cycle sauvegarde/import/restauration, les PDF octet pour octet, les liens et snapshots de facturation, l'audit, les verrous concurrents, les permissions et le rollback en cas d'archive invalide.
