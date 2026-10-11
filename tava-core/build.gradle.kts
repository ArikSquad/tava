plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

dependencies {
    api(libs.slf4j.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.h2)
    testImplementation(project(":tava-h2"))
    testRuntimeOnly(libs.junit.launcher)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
}

publishing {
    publications.create<MavenPublication>("mavenJava") {
        from(components["java"])
    }
}
