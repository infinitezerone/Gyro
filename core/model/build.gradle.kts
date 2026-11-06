plugins {
    id("gyro.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.core)
    testImplementation("junit:junit:4.13.2")
}
