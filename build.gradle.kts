plugins {
    java
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "dev.kualu"
    version = "1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }
}

// Back-compat: `./gradlew run` launches the CLI, same as `./gradlew :cli:run`.
tasks.register("run") {
    group = "application"
    description = "Run the interactive CLI chat console"
    dependsOn(":cli:run")
}
