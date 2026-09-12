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

Configurer `AUTH_IDENTITY_URL` avec l’URL interne du service d’identité, incluant `/api/auth` (par exemple `http://mserv-identity:8083/api/auth` dans Compose). En développement, la valeur est `http://localhost:8083/api/auth`. Le backend appelle `/session` pour chaque requête avec le Bearer de l’utilisateur ; il ne requiert plus de copie du secret JWT d’identité. Compose et le manifeste OpenShift contiennent cette configuration.

Le client est réactif, sans cache ni redirection, avec des délais de connexion et lecture de 2 et 3 secondes. Une session révoquée ou un compte désactivé est refusé ; changement de mot de passe et enrôlement MFA obligatoires bloquent les accès métier. Une identité indisponible produit un 503. Le service d’identité doit donc être joignable avant d’utiliser l’API métier.

Les tests `BusinessAuthorizationTest` exercent les endpoints avec PostgreSQL et un serveur HTTP d’identité simulé ; `BusinessSessionGateTest` dans le dépôt identité valide le contrat réel `/session` avec MFA obligatoire.

## Sauvegardes complètes

L'import JSON crée désormais une archive ZIP complète avant tout remplacement. Les routes `/api/data/backups` et `/api/data/restore` permettent aussi de créer, télécharger et restaurer ces archives avec la permission `IMPORT_EXPORT`. Voir [le contrat de sauvegarde](../docs/api-contract.md#sauvegarde-complète-et-restauration) pour le format, les confirmations et les limites. Les comptes du service d'identité sont exclus.

Le volume `compta.documents.storage-dir` doit être persistant et disposer d'espace pour les PDF et les archives. Copier les ZIP hors du serveur ; aucune rétention automatique n'efface les sauvegardes. La restauration utilise de nouveaux chemins pour préserver les fichiers existants en cas d'échec. Configurer le proxy pour autoriser les corps binaires jusqu'à 64 Mio ; Quarkus applique cette limite HTTP, et l'archive a des limites d'expansion supplémentaires.

`./gradlew test --tests '*BackupResourceTest'` valide sur PostgreSQL isolé le cycle sauvegarde/import/restauration, les PDF octet pour octet, les liens et snapshots de facturation, l'audit, les verrous concurrents, les permissions et le rollback en cas d'archive invalide.
