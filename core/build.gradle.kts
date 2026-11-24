plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    // BIP-340 Schnorr on secp256k1. The JNI bindings are chosen by the consumer:
    // -jni-jvm for JVM tests, -jni-android in the app.
    implementation(libs.secp256k1.kmp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.secp256k1.kmp.jni.jvm)
}

tasks.test {
    useJUnitPlatform()
}
