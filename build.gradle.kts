plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "dev.raggateway"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// RAG eval harness (t13): its own source set so it never ships in the application jar.
sourceSets {
    create("eval") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}
val evalImplementation by configurations.getting { extendsFrom(configurations.implementation.get()) }
val evalRuntimeOnly by configurations.getting { extendsFrom(configurations.runtimeOnly.get()) }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.resilience4j.bom))
    annotationProcessor(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    // HTTP edge, actuator, metrics
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Storage: plain JDBC (no JPA), Flyway migrations, pgvector type, Redis (Lettuce)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(libs.pgvector)

    // Providers: official Claude SDK (the Ollama adapter uses the JDK HttpClient)
    implementation(libs.anthropic.java)

    // Resilience: only these three modules (DECISIONS.md, C6 / D-07)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.micrometer)

    // API-key lookup cache, output validation
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation(libs.json.schema.validator)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.wiremock)
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Mockito attaches its agent at runtime on JDK 21; allow it explicitly.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Same JVM limits as the laptop (CLAUDE.md overrides).
tasks.bootRun {
    jvmArgs("-Xmx512m")
}

// t13: ./gradlew eval --args="..."  (the main class is written in t13)
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Runs the RAG eval harness (t13)."
    classpath = sourceSets["eval"].runtimeClasspath
    mainClass = "dev.raggateway.eval.EvalMain"
}
