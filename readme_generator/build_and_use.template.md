### Obtain and Run

There are three ways to obtain Perses.

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

Use `--visualization-dump-file <trace.jsonl>` to create a versioned JSONL event stream for
visualizing attempted and accepted reductions. Each line is one JSON object. The stream records the
initial program, tested candidates, cached rejections, cancelled tests, committed candidates, and
the final reduction summary.

```bash
java -jar perses_deploy.jar \
    --test-script test.sh \
    --input-file input.c \
    --visualization-dump-file trace.jsonl
```

Candidate and commit records share an opaque string `candidateId`. Each snapshot contains output
files as `path`/`content` pairs and ordered token objects with `index`/`text`; a commit advances the
candidate's `baseRevision` to `newRevision`. Commit records retain edit metadata even when no test
event preceded them, and critical failures are emitted as `error` records. Visualizers should diff
a candidate snapshot against its base revision. The schema is identified by `schemaVersion` and
currently has version `1`. The trace can be large because candidate records contain complete source
snapshots.

Check all available command line arguments

```bash
java -jar perses_deploy.jar  --help
```

The following is the complete list of command line arguments.
