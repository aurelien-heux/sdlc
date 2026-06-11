plugins {
    id("sdlc.java-conventions")
    alias(libs.plugins.spring.boot)   // version comes from the catalog (springBoot)
}
dependencies {
    implementation(project(":libs:domain-shared"))
    implementation(project(":libs:agent-core"))
    implementation(project(":libs:traceability-graph"))
    implementation(project(":libs:adapter-common"))
    implementation(project(":libs:governance"))
    implementation(project(":libs:adapter-llm-spring"))
    implementation(project(":libs:adapter-git"))
    implementation(project(":libs:adapter-graph-postgres"))
    implementation(project(":libs:adapter-otel"))
    implementation(libs.postgresql)
    implementation(libs.otel.autoconfigure)
    implementation(libs.jakarta.json)
    runtimeOnly(libs.parsson)
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":libs:agent-core")))
    // Test-scope cross-agent dependencies are for the closed-loop E2E ONLY: the loop test
    // drives intent→spec→design→backlog inside one JVM. Production code never crosses
    // agent boundaries — these must stay testImplementation.
    testImplementation(project(":agents:agent-intent"))
    testImplementation(project(":agents:agent-spec"))
    testImplementation(project(":agents:agent-design"))
    testImplementation(project(":libs:governance"))
    implementation(libs.spring.boot.starter)
}

// the demo prompts for approval on stdin; without this, bootRun sees EOF and auto-rejects
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    standardInput = System.`in`
}
