pluginManagement { includeBuild("build-logic") }

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "sdlc-platform"
include("libs:domain-shared")
include("libs:traceability-graph")
include("libs:agent-core")
// later tasks add: agents:agent-spec
