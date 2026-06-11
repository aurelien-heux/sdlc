plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:agent-core"))
    implementation(libs.otel.api)
    runtimeOnly(libs.otel.sdk)
    runtimeOnly(libs.otel.exporter.otlp)
    runtimeOnly(libs.otel.autoconfigure)
    testImplementation(libs.otel.sdk)
    testImplementation(libs.otel.sdk.testing)
}
