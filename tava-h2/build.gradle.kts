plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.h2)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
