plugins { id("sdlc.java-conventions") }
dependencies {
    implementation(project(":libs:domain-shared"))
    implementation(project(":libs:agent-core"))
    implementation(project(":libs:traceability-graph"))
    testImplementation(libs.archunit)
}
