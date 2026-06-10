plugins {
    id("sdlc.java-conventions")
    `java-library`
}
dependencies {
    api(project(":libs:domain-shared"))
    implementation(libs.snakeyaml) // used by Task 7's parser
}
