pluginManagement { includeBuild("build-logic") }

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "sdlc-platform"
include("libs:domain-shared")
// later tasks add: libs:traceability-graph, libs:agent-core, agents:agent-spec
