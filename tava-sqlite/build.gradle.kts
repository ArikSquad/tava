plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.sqlite.jdbc)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":tava-core")))
    testRuntimeOnly(libs.junit.launcher)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
