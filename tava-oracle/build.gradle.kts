plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.oracle.jdbc)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
