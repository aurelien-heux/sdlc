plugins { id("sdlc.java-conventions") }
repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone") { // Spring AI 2.0 milestones; drop once 2.0 GA is out
        content { includeGroupByRegex("org\\.springframework\\.ai.*") }
    }
}
dependencies {
    api(project(":libs:agent-core"))
    api(libs.spring.ai.anthropic)
    api(libs.spring.ai.openai)
    implementation(libs.snakeyaml)
    constraints {
        // anthropic-java pulls httpclient5:5.3.1; Boot 4.1's HttpComponents request factory
        // (used by the OpenAI RestClient) needs 5.4+ (TlsSocketStrategy). Align with Boot's BOM.
        implementation(libs.httpclient5)
    }
}
