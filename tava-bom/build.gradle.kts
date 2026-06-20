plugins {
    `java-platform`
    `maven-publish`
}

javaPlatform.allowDependencies()

dependencies {
    constraints {
        listOf(
            "tava-core", "tava-postgres", "tava-mysql", "tava-sqlite", "tava-h2",
            "tava-sqlserver", "tava-oracle", "tava-mongodb", "tava-dynamodb"
        ).forEach { api(project(":$it")) }
    }
}

publishing {
    publications.create<MavenPublication>("mavenJava") {
        from(components["javaPlatform"])
    }
}
