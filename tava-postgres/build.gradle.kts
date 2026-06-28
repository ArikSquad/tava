plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.junit.launcher)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
