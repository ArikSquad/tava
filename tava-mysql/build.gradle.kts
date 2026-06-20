plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-jdbc"))
    runtimeOnly(libs.mysql.connector)
    runtimeOnly(libs.mariadb.client)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
