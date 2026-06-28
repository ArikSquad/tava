plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":tava-core"))
    implementation(libs.aws.dynamodb)
    testImplementation(platform(libs.junit.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":tava-core")))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.localstack)
    testRuntimeOnly(libs.junit.launcher)
}
publishing { publications.create<MavenPublication>("mavenJava") { from(components["java"]) } }
