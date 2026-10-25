# Decisions

Architecture decisions for Minilake, newest last. Each entry records what was decided, the evidence behind it, and what we gave up.

---

## D1: Parquet library: parquet-java vs. Arrow Dataset

**Date:** 2026-10-24
**Status:** Accepted
**Task:** T0.3 (spike)

### Context

`minilake-parquet` must write and read Parquet data files. Minilake owns its storage layer (`LogStore` / `ObjectStore` in `minilake-storage`, with local and S3 implementations), so the Parquet library has to read and write bytes through our interfaces rather than doing its own file I/O. We also want to avoid pulling a Hadoop cluster stack into a small library.

Candidates:

- **parquet-java** (`org.apache.parquet:parquet-hadoop`), the reference Java implementation, used by Spark, Delta Lake and Iceberg.
- **Arrow Java Dataset** (`org.apache.arrow:arrow-dataset`), Arrow's dataset layer. The Java module is marked experimental and delegates to the C++ Arrow implementation via JNI.

### Spike

Test: `minilake-parquet/src/test/java/minilake/parquet/ParquetRoundTripTest.java`

Writes three rows of a three-column schema (`id int64`, `name string`, `score double`) with `ExampleParquetWriter` + `LocalOutputFile`, reads them back with `ParquetFileReader` + `LocalInputFile`, and asserts the rows match.

### Findings

1. **Round trip works.** parquet-java 1.18.0 writes and reads the file with local `java.nio` file I/O through `LocalOutputFile` / `LocalInputFile`, with no Hadoop `FileSystem` or `Path` in our code.

2. **Hadoop is required at compile time.** With only `parquet-hadoop` on the classpath, compilation fails:

   ```
   error: cannot access Path
       .builder(new LocalOutputFile(file))
   class file for org.apache.hadoop.fs.Path not found
   ```

   `ExampleParquetWriter.builder(...)` is overloaded on Hadoop `Path` and on `OutputFile`. javac needs the Hadoop class to resolve the overload, even though we never use it. `parquet-hadoop` declares Hadoop as *provided*, so it is not pulled in transitively.

3. **Hadoop is also required at runtime.** With `hadoop-client-api` alone the code compiles, but the test fails at runtime. `hadoop-client-runtime` is needed as well.
   <!-- paste the exact runtime error here -->

4. **Cost of the Hadoop dependency:**

   | Jar | Scope | Size |
   |---|---|---|
   | `org.apache.hadoop:hadoop-client-api:3.4.1` | `implementation` | 19 MB |
   | `org.apache.hadoop:hadoop-client-runtime:3.4.1` | `runtimeOnly` | 29 MB |
   | **Total** | | **~48 MB** |

   Both are Hadoop's *shaded* client jars. Hadoop's own dependencies are relocated inside them, so they do not leak conflicting versions of Guava, Jackson and similar libraries onto our classpath.

5. **parquet-java's own dependencies are small and self-contained:** `parquet-column`, `parquet-common`, `parquet-encoding`, `parquet-format-structures` (Thrift-generated file metadata) and `parquet-jackson` (shaded Jackson).

6. **Arrow Dataset (from documentation, not run):** experimental API that may change between releases. File I/O and decoding happen in native C++ via JNI, which means platform-specific native libraries, native memory that must be closed explicitly, and crashes that can take down the JVM. Its I/O goes through Arrow's C++ filesystems, not our `ObjectStore`.

### Decision

**Use parquet-java**, accepting `hadoop-client-api` (compile) and `hadoop-client-runtime` (runtime) as dependencies of `minilake-parquet` only.

### Reasons

- **Fits our storage abstraction.** `InputFile` / `OutputFile` are small interfaces we can implement on top of `ObjectStore`, so Parquet bytes flow through our local and S3 backends, our fault injection, and our tests. Arrow Dataset would bypass all of that.
- **Pure Java.** No native libraries, no JNI memory management, the same behaviour on every OS and in CI.
- **Mature and stable API**, and the same library real lakehouse formats (Delta, Iceberg) build on.
- **The Hadoop cost is contained.** The shaded jars avoid version conflicts, and Gradle module boundaries keep Hadoop inside `minilake-parquet`. `minilake-core` and `minilake-storage` never see it.

### Consequences and trade-offs

- About 48 MB of Hadoop jars ship with anything that includes `minilake-parquet` (the CLI and benchmarks).
- Hadoop types (`Configuration`, `Path`) appear in parquet-java's API. Rule: **no Hadoop types outside `minilake-parquet`**. Wrap parquet-java behind our own reader/writer interfaces.
- We use `PlainParquetConfiguration` instead of Hadoop `Configuration` wherever the API allows.
- Revisit if parquet-java completes its Hadoop-free work (ParquetConfiguration and the codec refactor). The Hadoop jars could then be dropped without changing our interfaces.

### Follow-ups

- **Compression:** the spike wrote uncompressed files. When choosing a codec (Snappy or Zstd), check whether extra codec libraries are needed on the classpath and re-run the round-trip test with that codec.
- **Implement `InputFile` / `OutputFile` over `ObjectStore`** when the S3 storage backend lands.