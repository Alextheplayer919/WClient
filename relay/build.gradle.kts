plugins {
    id("java-library")
    kotlin("jvm")
}

group = "com.retrivedmods.neon.relay"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    implementation(platform(libs.log4j.bom))
    implementation(libs.log4j.api)
    implementation(libs.log4j.core)
    implementation(libs.jose4j)
    implementation(libs.jackson.databind)
    implementation(libs.kotlinx.coroutines.core)
    
    // Use api to expose these to the app module
    api(libs.minecraft.auth)
    api(project(":relay:Network:transport-raknet"))
    api(project(":relay:Protocol:bedrock-codec"))
    api(project(":relay:Protocol:bedrock-connection"))
    api(project(":relay:Protocol:common"))
    api(libs.bundles.netty)
    
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // CI only ever shows the task name unless the assertion text is printed, so print it.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

kotlin {
    jvmToolchain(17)
}
