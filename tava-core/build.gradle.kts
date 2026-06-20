plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.slf4j.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

publishing {
    publications.create<MavenPublication>("mavenJava") {
        from(components["java"])
    }
}
