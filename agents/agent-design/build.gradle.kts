plugins { id("sdlc.java-conventions") }
dependencies {
    implementation(project(":libs:domain-shared"))
    implementation(project(":libs:agent-core"))
    implementation(project(":libs:traceability-graph"))
    implementation(project(":libs:adapter-common"))
    implementation(libs.jakarta.json)
    runtimeOnly(libs.parsson)
    testImplementation(testFixtures(project(":libs:agent-core")))
}
