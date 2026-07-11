plugins {
    base
}

group = "eu.mikart.tava"
version = "1.1.3"

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
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        dependencies {
            add("compileOnly", rootProject.libs.jetbrains.annotations)
            add("testCompileOnly", rootProject.libs.jetbrains.annotations)
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
