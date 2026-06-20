plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-core"))
    implementation(libs.mongodb.driver.sync)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
