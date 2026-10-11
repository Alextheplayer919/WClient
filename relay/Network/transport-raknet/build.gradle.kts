/*
 * Copyright 2023 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

dependencies {
    // Lombok as annotation processor (avoids circular dependency)
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    
    api(libs.bundles.netty)
    api(libs.expiringmap)
    testImplementation(libs.testng)
    testImplementation(libs.junit.jupiter)
}

// The tests under src/test are JUnit 5 (org.junit.jupiter.api). Without this the default JUnit4/TestNG
// runner discovers nothing and the task still reports success, which is how the split-packet
// regressions stayed invisible: :relay:Network:transport-raknet:test was a no-op.
tasks.test {
    useJUnitPlatform()
}
