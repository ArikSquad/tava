plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-core"))
    implementation(libs.aws.dynamodb)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
