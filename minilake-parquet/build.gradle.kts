plugins { id("minilake.java-conventions"); `java-library` }

dependencies {
    implementation(project(":minilake-storage"))
    implementation("org.apache.parquet:parquet-hadoop:1.18.0")
    implementation("org.apache.hadoop:hadoop-client-api:3.4.1")
}