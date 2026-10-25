plugins { id("minilake.java-conventions"); application }
dependencies {
    implementation(project(":minilake-core"))
    implementation(project(":minilake-parquet"))
}