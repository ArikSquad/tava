plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.postgresql)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
