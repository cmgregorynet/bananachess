plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The conformance fixture is written by the Swift engine and shared with the web port.
val conformanceFixture = rootProject.layout.projectDirectory.file("../Web/fixtures/conformance.json.gz")

tasks.test {
    useJUnitPlatform()
    inputs.file(conformanceFixture).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("conformance.fixture", conformanceFixture.asFile.absolutePath)
}
