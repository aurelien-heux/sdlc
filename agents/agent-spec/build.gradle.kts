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
    implementation(project(":libs:adapter-common"))
    implementation(libs.jakarta.json)
    runtimeOnly(libs.parsson)
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":libs:agent-core")))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.anthropic)
    implementation(libs.spring.ai.openai)
    testImplementation(libs.spring.boot.starter.test)
    constraints {
        // anthropic-java pulls httpclient5:5.3.1; Boot 4.1's HttpComponents request factory
        // (used by the OpenAI RestClient) needs 5.4+ (TlsSocketStrategy). Align with Boot's BOM.
        implementation(libs.httpclient5)
    }
}

// the demo prompts for approval on stdin; without this, bootRun sees EOF and auto-rejects
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    standardInput = System.`in`
}
