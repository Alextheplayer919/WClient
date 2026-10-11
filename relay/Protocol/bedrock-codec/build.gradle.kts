plugins {
    id("java-library")
    alias(libs.plugins.checkerframework)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

dependencies {
    // Lombok as annotation processor (avoids circular dependency)
    compileOnly("org.projectlombok:lombok:1.18.34")
    // org.slf4j is referenced directly here (TextSerializer_v898/v924 log malformed JSON, and lombok's
    // @Slf4j generates a Logger field in several serializers), so declare it rather than hoping it arrives
    // transitively: a class that cannot link dies with NoClassDefFoundError while the codec is being built.
    api("org.slf4j:slf4j-api:2.0.16")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    api(project(":relay:Protocol:common"))
    api(libs.adventure.text.serializer.legacy)
    api(libs.adventure.text.serializer.json)
    api(platform(libs.fastutil.bom))
    api(libs.netty.buffer)
    api(libs.fastutil.long.common)
    api(libs.fastutil.long.obj.maps)
    api(libs.jose4j)
    api(libs.nbt)
    api(libs.jackson.annotations)
    api(libs.jackson.databind)

    // Tests
    testImplementation(libs.junit)
}