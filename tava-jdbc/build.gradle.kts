plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":tava-core"))
}

publishing {
    publications.create<MavenPublication>("mavenJava") {
        from(components["java"])
    }
}
