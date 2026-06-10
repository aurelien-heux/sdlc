plugins { id("sdlc.java-conventions") }
dependencies {
    api(project(":libs:domain-shared"))
    implementation(libs.snakeyaml) // used by Task 7's parser
}
