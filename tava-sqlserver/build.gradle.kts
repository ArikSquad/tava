plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.mssql.jdbc)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
