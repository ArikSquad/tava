plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.mssql.jdbc)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":tava-core")))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mssqlserver)
    testRuntimeOnly(libs.junit.launcher)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
