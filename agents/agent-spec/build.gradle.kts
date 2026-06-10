plugins {
    id("sdlc.java-conventions")
    alias(libs.plugins.spring.boot)   // version comes from the catalog (springBoot)
}
repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone") { // Spring AI 2.0 milestones; drop once 2.0 GA is out
        content { includeGroupByRegex("org\\.springframework\\.ai.*") }
    }
}
dependencies {
    implementation(project(":libs:domain-shared"))
    implementation(project(":libs:agent-core"))
    implementation(project(":libs:traceability-graph"))
    implementation(libs.jakarta.json)
    runtimeOnly(libs.parsson)
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":libs:agent-core")))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.anthropic)
}
