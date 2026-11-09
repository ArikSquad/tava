import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    base
    id("jacoco")
    id("com.diffplug.spotless") version "8.11.0"
}

group = "eu.mikart.tava"
version = "1.2.2"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(21)
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release = 21
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
        }
        project.tasks.named("check").configure {
            dependsOn(project.tasks.named("javadoc"))
        }
        dependencies {
            add("compileOnly", rootProject.libs.jetbrains.annotations)
            add("testCompileOnly", rootProject.libs.jetbrains.annotations)
        }

        project.pluginManager.apply("jacoco")

        project.tasks.withType<Test>().configureEach {
            extensions.configure<JacocoTaskExtension> {
                destinationFile = layout.buildDirectory.file("jacoco/${name}.exec").get().asFile
            }
        }

        project.tasks.named<JacocoReport>("jacocoTestReport").configure {
            group = "Reporting"
            description = "Generate JaCoCo coverage report for this project"
            val sourceSet = project.extensions.getByType<JavaPluginExtension>().sourceSets["main"]
            reports {
                html.required.set(true)
                xml.required.set(true)
                csv.required.set(false)
            }
            dependsOn(project.tasks.withType<Test>())
            project.tasks.withType<Test>().forEach { testTask ->
                executionData(testTask)
            }
            sourceDirectories.setFrom(sourceSet.allSource.srcDirs)
            classDirectories.setFrom(files(sourceSet.output.classesDirs))
        }
    }
    plugins.withId("java-library") {
        dependencies {
            add("compileOnlyApi", rootProject.libs.jetbrains.annotations)
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven("https://repo.codemc.io/repository/ArikSquad/") {
                    val mavenUsername =
                        providers.environmentVariable("JENKINS_USERNAME")
                            .orElse(providers.gradleProperty("mavenUsername"))
                    val mavenPassword =
                        providers.environmentVariable("JENKINS_PASSWORD")
                            .orElse(providers.gradleProperty("mavenPassword"))

                    if (mavenUsername.isPresent && mavenPassword.isPresent) {
                        credentials {
                            username = mavenUsername.get()
                            password = mavenPassword.get()
                        }
                    }
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name = project.name
                    description = "Tava modular database toolkit"
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
    }
}

tasks.register("jacocoTestReport") {
    group = "Reporting"
    description = "Generate JaCoCo reports for all Java subprojects"
    dependsOn(project.subprojects
        .filter { it.pluginManager.hasPlugin("java") }
        .map { it.tasks.named<JacocoReport>("jacocoTestReport") })
}

tasks.register<JacocoReport>("jacocoRootReport") {
    group = "Reporting"
    description = "Generate aggregated JaCoCo coverage report for all Java subprojects"
    val javaSubprojects = project.subprojects.filter { it.pluginManager.hasPlugin("java") }
    val javaSourceSets = javaSubprojects.map { subproject ->
        subproject to subproject.extensions.getByType<JavaPluginExtension>().sourceSets["main"]
    }
    dependsOn(javaSubprojects.map { it.tasks.named<Test>("test") })

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(javaSourceSets.flatMap { (_, sourceSet) -> sourceSet.allSource.srcDirs })
    classDirectories.setFrom(files(javaSourceSets.map { (_, sourceSet) -> sourceSet.output.classesDirs }))
    javaSubprojects.forEach { project ->
        project.tasks.withType<Test>().forEach { testTask ->
            executionData(testTask)
        }
    }
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    group = "Verification"
    description = "Verify aggregate coverage meets minimum thresholds"
    val javaSubprojects = project.subprojects.filter { it.pluginManager.hasPlugin("java") }
    val javaSourceSets = javaSubprojects.map { subproject ->
        subproject to subproject.extensions.getByType<JavaPluginExtension>().sourceSets["main"]
    }
    dependsOn("jacocoRootReport")

    classDirectories.setFrom(files(javaSourceSets.map { (_, sourceSet) -> sourceSet.output.classesDirs }))
    javaSubprojects.forEach { project ->
        project.tasks.withType<Test>().forEach { testTask ->
            executionData(testTask)
        }
    }

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

spotless {
    java {
        target("**/src/**/*.java")
        shortenFullyQualifiedTypes()
        importOrder()
        removeUnusedImports()
        palantirJavaFormat("2.103.0").formatJavadoc(true)
    }
}

val javaSourceFiles = fileTree(rootDir) {
    include("**/src/**/*.java")
    exclude("**/build/**", "**/.gradle/**")
}

tasks.register("checkInlineFullyQualifiedTypes") {
    group = "verification"
    description = "Rejects inline fully qualified Java class references"
    inputs.files(javaSourceFiles)

    doLast {
        // Ignore text-block examples unless their triple-quote delimiters are escaped.
        val textBlock = "(?s:(?<!\\\\)(?:\\\\\\\\)*\"{3}.*?(?<!\\\\)(?:\\\\\\\\)*\"{3})"
        val commentsAndLiterals = Regex(
            "$textBlock|(?s:/\\*.*?\\*/)|//[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
        )
        val declarations = Regex("(?m)^[ \\t]*(?:package|import)\\b(?s:.*?);")
        val annotations = Regex("@[A-Z_$][\\w$]*(?:\\([^)]*\\))?\\s+")
        val qualifiedType = Regex(
            "\\b(?:[a-z_$][\\w$]*\\s*\\.\\s*)+"
                + "(?:@[A-Z_$][\\w$]*(?:\\([^)]*\\))?\\s+)?[A-Z_$][\\w$]*"
        )
        // These names collide with imported types, so Java requires qualification at the use site.
        val identityCollision = setOf("eu.mikart.tava.data.Identity")
        val sqlDateCollision = setOf("java.sql.Date")
        val allowedCollisions = mapOf(
            "tava-core/src/main/java/eu/mikart/tava/RecordMapper.java" to identityCollision,
            "tava-sqlite/src/test/java/eu/mikart/tava/sqlite/SqliteMemoryTest.java" to identityCollision,
            "tava-core/src/main/java/eu/mikart/tava/transfer/ResumableTransfer.java" to sqlDateCollision,
            "tava-core/src/main/java/eu/mikart/tava/transfer/TransferRecords.java" to sqlDateCollision,
            "tava-mongodb/src/main/java/eu/mikart/tava/mongodb/MongoAdapter.java" to sqlDateCollision,
        )

        fun blankTextPreservingLines(value: String): String =
            value.map { character -> if (character == '\n') '\n' else ' ' }.joinToString("")

        val violations = javaSourceFiles.files.sortedBy { it.path }.flatMap { file ->
            val relativePath = rootDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            val source = file.readText()
            // Keep line positions stable while removing comments, strings, text blocks, and declarations.
            val masked = commentsAndLiterals.replace(source) { match -> blankTextPreservingLines(match.value) }
            val code = declarations.replace(masked) { match -> blankTextPreservingLines(match.value) }
            qualifiedType.findAll(code).mapNotNull { match ->
                val name = match.value.replace(annotations, "").replace(Regex("\\s+"), "").trim()
                if (name in allowedCollisions[relativePath].orEmpty()) null
                else {
                    val line = code.take(match.range.first).count { it == '\n' } + 1
                    "$relativePath:$line: use an import instead of $name"
                }
            }.toList()
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Inline fully qualified class references are not allowed:\n${violations.joinToString("\n")}"
            )
        }
    }
}

allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(":spotlessCheck", ":checkInlineFullyQualifiedTypes")
    }
}

tasks.named("check") {
    dependsOn("jacocoCoverageVerification")
}
