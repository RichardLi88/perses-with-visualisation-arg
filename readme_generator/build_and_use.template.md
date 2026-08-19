### Obtain and Run

There are three ways to obtain Perses.

Perses must be built, tested, and run with Java 21. Ensure `java -version` reports Java 21 before
using the commands below. When running Bazel tests, select its Java 21 runtime explicitly if your
system default is newer:

```bash
bazelisk test --java_runtime_version=remotejdk_21 //...
```

- Download a prebuilt release JAR file from our [release page](https://github.com/perses-project/perses/releases),
  for example,

  ```bash
  wget https://github.com/uw-pluverse/perses/releases/download/v{majorVersion}.{minorVersion}/perses_deploy.jar
  java -jar perses_deploy.jar [options]? --test-script <test-script.sh> --input-file <program file>
  ```

- Clone the repo and build Perses from the source.

  ```bash
  git clone https://github.com/perses-project/perses.git
  cd perses
  bazelisk build //src/org/perses:perses_deploy.jar
  java -jar bazel-bin/src/org/perses/perses_deploy.jar [options]? \
      --test-script <test-script.sh> --input-file <program file>
  ```

- If you want to always use the trunk version of Perses, [perses-trunk](https://github.com/perses-project/perses/blob/master/scripts/perses-trunk) automatically downloads and builds the latest version.

  NOTE: [Bazelisk](https://github.com/bazelbuild/bazelisk) is the prerequisite to run perses-trunk successfully.

  ```bash
  wget https://raw.githubusercontent.com/perses-project/perses/master/scripts/perses-trunk
  chmod +x perses-trunk
  ./perses-trunk [options]? --test-script <test-script.sh> --input-file <program file>
  ```

#### Important Flags

- --test-script **&lt;test-script.sh&gt;**:
  The script encodes the constraints that both of the original program file and the reduced version should satisfy. It should return **0** if the constraints are satisfied.

- --input-file **&lt;program-file&gt;**: the program needs to be reduced. Currently, Perses
  supports C, Rust, Java and Go. Note that we can easily support any other languages,
  if the specific language can be parsed by an Antlr parser.

### Reduction visualization trace

Use `--visualization-dump-file <reduction.json>` to create one schema-versioned JSON document for
visualizing attempted and accepted reductions. Schema 2.0 separates content-addressed source from
logical reduction states, so concurrent candidates retain the state from which they were created.

```bash
java -jar perses_deploy.jar \
    --test-script test.sh \
    --input-file input.c \
    --visualization-dump-file reduction.json
```

The authoritative graph is formed by `states` and `candidates`: every candidate has an immutable
`baseStateId`, and an accepted candidate has a `resultStateId`. `programs` deduplicates accepted
multi-file source, while other candidates carry path-aware patches. Every candidate also records a
stable transformation `kind`, concrete edit class, reducer, action metadata, and syntax targets.
`steps` is the ordered accepted trajectory, and `summary` contains reduction and timing totals. The
source stored in `programs` and candidate patches uses the language's original-format printer for
readability, independently of `--code-format`. The machine-readable contract is in
`doc/reduction_trace_schema_v2.json`. This schema replaces the earlier JSONL schema and is not
backward compatible with it.

Check all available command line arguments

```bash
java -jar perses_deploy.jar  --help
```

The following is the complete list of command line arguments.
