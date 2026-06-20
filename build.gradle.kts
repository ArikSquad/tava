plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "eu.mikart.tava"
version = providers.gradleProperty("releaseVersion").orElse("0.2.0-SNAPSHOT").get()

repositories {
    mavenCentral()
}

dependencies {
    api(libs.slf4j.api)
    api(libs.mongodb.driver.sync)

    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mysql.connector)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mongodb)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "Tava"
                description = "A compact Java 21 database toolkit for SQL and MongoDB."
                url = "https://github.com/ArikSquad/tava"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/ArikSquad/tava.git"
                    url = "https://github.com/ArikSquad/tava"
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ArikSquad/tava")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY")
    val password = providers.environmentVariable("SIGNING_PASSWORD")
    if (key.isPresent) {
        useInMemoryPgpKeys(key.get(), password.orNull)
        sign(publishing.publications)
    }
}
