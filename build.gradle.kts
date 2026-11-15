plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    id("io.quarkus")
    id("org.openapi.generator") version "7.22.0"
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    // Explicit JWT verification preserves the session gate and its problem+json errors.
    implementation("io.smallrye:smallrye-jwt")
    implementation("io.quarkus:quarkus-rest-kotlin")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.smallrye.reactive:mutiny-kotlin")
    implementation("io.vertx:vertx-lang-kotlin-coroutines")
    implementation("io.quarkus:quarkus-hibernate-reactive-panache-kotlin")
    implementation("io.quarkus:quarkus-reactive-pg-client")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("io.quarkus:quarkus-arc")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
}

// Security patches missing from the Quarkus platform (checked 2026-11-15 against 3.38.3,
// and still missing from 3.40.1 for Vert.x): Vert.x < 4.5.35, Netty < 4.1.138 and
// jackson-databind 2.22.0 carry CVEs scored 7 or more. Each rule only raises the patch
// level of the platform's own line; remove it once quarkusPlatformVersion ships these.
// Same rules as mserv-identity.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val version = requested.version ?: return@eachDependency
        when {
            requested.group == "io.vertx" && version.startsWith("4.5.") ->
                useVersion("4.5.36").also { because("Vert.x CVE-2026-102460 and related, fixed in 4.5.35") }
            requested.group == "io.netty" && version.startsWith("4.1.") ->
                useVersion("4.1.140.Final").also { because("Netty CVE-2026-89044 and related, fixed in 4.1.138") }
            requested.group.startsWith("com.fasterxml.jackson") &&
                requested.name != "jackson-annotations" &&
                version.startsWith("2.22.") ->
                useVersion("2.22.4").also { because("jackson-databind CVE-2026-54515 in 2.22.0") }
        }
    }
}

group = "fr.spiritbox"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
        javaParameters = true
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }

    sourceSets {
        main {
            kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/kotlin"))
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("openApiGenerate"))
}

openApiGenerate {
    generatorName.set("kotlin-server")
    inputSpec.set("$rootDir/src/main/openapi/compta-zik.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    // Raw ZIP request bodies are bounded byte arrays, handled by Quarkus REST.
    typeMappings.set(mapOf("binary" to "kotlin.ByteArray", "file" to "kotlin.ByteArray"))
    apiPackage.set("fr.spiritbox.compta.openapi.api")
    modelPackage.set("fr.spiritbox.compta.openapi.model")
    globalProperties.set(
        mapOf(
            "models" to "",
            "apis" to "",
            "supportingFiles" to "false",
        ),
    )
    configOptions.set(
        mapOf(
            "library" to "jaxrs-spec",
            "interfaceOnly" to "true",
            "useCoroutines" to "true",
            "returnResponse" to "true",
            "useJakartaEe" to "true",
            "useTags" to "true",
            "enumPropertyNaming" to "UPPERCASE",
        ),
    )
}
