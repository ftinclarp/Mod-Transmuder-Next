plugins {
    java
    application
    id("com.gradleup.shadow") version "9.2.2"
}

group = "dev.modtransmuder"
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.modtransmuder.Main"
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("info.picocli:picocli:4.7.6")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Gradle 9 no longer wires the JUnit Platform launcher automatically.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "mod-transmuder-next"
    archiveClassifier = "all"
    mergeServiceFiles()
}
