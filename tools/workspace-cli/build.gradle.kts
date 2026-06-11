plugins {
    id("sdlc.java-conventions")
    application
}
dependencies {
    implementation(project(":libs:governance"))        // RevalidateArtifactUseCase
    implementation(project(":libs:traceability-graph")) // ProjectionBuilder, InMemoryTraceabilityGraph
    implementation(project(":libs:adapter-common"))     // FileArtifactRepository (api's agent-core)
}
application { mainClass = "dev.sdlc.tools.cli.WorkspaceCliMain" }

// `gradlew run` executes in the module dir; resolve the default ./workspace against the
// repo root instead, and forward -Dworkspace from the gradle invocation to the app JVM.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    systemProperty("workspace", System.getProperty("workspace", "workspace"))
}
