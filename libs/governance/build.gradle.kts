plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:agent-core"))
    api(project(":libs:traceability-graph"))
    testImplementation(project(":libs:adapter-common")) // FileArtifactRepository in RevalidateArtifactUseCaseTest
}
