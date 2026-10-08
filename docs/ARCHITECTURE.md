# Mod-Transmuder-Next — Architecture Proposal (v3)

A command-line Java tool that ports Minecraft mods from Forge 1.7.10 to
Fabric 1.21.1. This document is the reference for implementing individual
stages in later prompts.

Goal of the pipeline: take a Forge mod and produce a runnable Fabric mod in
an output directory, validating by a Gradle build.

---

## 1. Gradle Setup

- **DSL: Kotlin DSL** — type-safe and IDE-autocompleteable, and the modern
  Fabric ecosystem (fabric-loom, current fabric templates) ships Kotlin DSL
  examples, so the code we translate will match. Groovy DSL is legacy here.
- **Java toolchain**: `languageVersion = 21` via Gradle toolchain API.
  Build on JDK 21; target bytecode 21.
- **Plugins**: `java`, `application` (entry class + start scripts),
  `com.gradleup.shadow` (fat jar so `java -jar build/libs/mod-transmuder-next.jar`
  works standalone). No distribution/installer plugin — one fat jar is the
  deliverable.
- **Dependencies (minimal set only)**:
  - `com.fasterxml.jackson.core:jackson-databind:2.17.x` — JSON config,
    result report, and parsing fabric.mod.json when translating metadata.
  - `info.picocli:picocli:4.7.x` — CLI, one zero-dependency jar, declarative
    help and typed flags; avoids hand-rolling an arg parser.
  - Test scope: `org.junit.jupiter:junit-jupiter`.
  - That's it — no Lombok, no DI framework, no logging framework (roll our
    own thin logger, see §7).
- **Wrapper**: `./gradlew` wrapper committed with pinned Gradle version.
- **Versioning**: centralized in `gradle.properties` (`version=`).

## 2. Package Layout

Root package `dev.modtransmuder`:

- `Main` — entry, wires CLI → config → stage list → runner → exit code.
- `cli` — picocli command definition, flag→config override mapping.
- `config` — `Config` record, `ConfigLoader` (parse + validate), validator
  rules.
- `pipeline` — `Stage` interface, `AbstractStage`, `PipelineContext`,
  `StageResult`, `PipelineRunner` (sequencer honoring stop_if_fail).
- `stage.download` / `stage.unpack` / `stage.transform` / `stage.validate` —
  one subpackage per pipeline stage, each stage implementation plus its
  private helpers.
- `model` — shared domain types (`ModModel`, `Manifest`) and enums.
- `transform` — rewrite engine, `RewriteRule` matching, text/hunk
  rewriters, `MetadataWriter` (fabric.mod.json + build.gradle translation).
- `util` — `ZipUtil` (zip-slip-safe extraction), `FileUtil`, `Logger`,
  `PathResolver`.
- `error` — exception hierarchy, `ExitCode` enum.

No cycles: `pipeline` depends on `stage.*`, `stage.*` depends on `model`,
`transform`, `util`, `error`. `config`/`cli` depend downward only.

## 3. Module Breakdown — Pipeline Stages

Order: `download → unpack → transform → validate`. Config loads before the
pipeline, in `Main`. Stage ids: `stage-download`, `stage-unpack`,
`stage-transform`, `stage-validate`.

| Stage | Purpose (one line) | Input | Output | Failure modes |
|---|---|---|---|---|
| `download` | Fetch the Fabric template zip once into a cache, from `template_zip_url`. | `template_zip_url` (+ optional `cache_dir`) | local zip path | network down, HTTP non-2xx, TLS, timeout, disk full, no write permission |
| `unpack` | Extract the template zip into a staging dir, then atomically replace the output dir. | zip path, `transmudation_output` | populated output tree | corrupt/truncated zip, zip-slip entry paths, path length, disk full, permission, atomic-move fallback failure |
| `transform` | Read the Forge mod from `transmudation_input`, apply the v1 rule set to sources/metadata in the output tree, write the manifest last. | input dir, `rewrite_data`, output tree | transformed output + `transmuder-manifest.json` | missing/empty input, malformed `rewrite_data`, bad regex, file unreadable as text / encoding mismatch |
| `validate` | Run a Gradle build in the output tree to prove it compiles. | output tree, env (JDK, network) | build result | Gradle absent, dependency fetch failure, compile errors, disk, long build → timeout |

A final JSON status line is always printed on stdout. Exit code rules in §7.
`validate` may be `SKIPPED` (see §7).

## 4. Data Model

- **`Config`** — immutable record, 1:1 with the JSON file, unknown keys
  rejected. Fields: `templateZipUrl`, `transmudationOutput` (string path),
  `transmudationInput` (string path), `rewriteData` (parsed into
  `RewriteData`), `stopIfFail` (boolean). Optional extensions: `cacheDir`,
  `timeoutSeconds`, `templateSha256`, `verbose` — defaults applied at load.
- **`RewriteData`** — ordered `List<RewriteRule>`, inline JSON array in the
  config. Schema (explicit, v1 contract):
  ```json
  {
    "rewrite_data": [
      {
        "type": "REPLACE_LITERAL | REPLACE_REGEX | RENAME | DELETE_FILE | WRITE_FILE",
        "glob": "**",                        // optional; default "**"
        "pattern": "",                       // required for REPLACE_LITERAL / REPLACE_REGEX / RENAME
        "replacement": "",                   // required for REPLACE_* / WRITE_FILE / RENAME
        "regex_flags": ["DOTALL", "MULTILINE", "CASE_INSENSITIVE", "UNICODE_CASE"],
        "enabled": true
      }
    ]
  }
  ```
  - `glob` — filter over regular files; default `**`. Only files are
    rewritten, never directories.
  - `type` semantics:
    - `REPLACE_LITERAL` — replace all literal occurrences of `pattern`
      with `replacement`.
    - `REPLACE_REGEX` — replace all matches of the compiled regex
      `pattern` (Java `Pattern` dialect) with `replacement`; `regex_flags`
      optional. Replacement supports capture-group backrefs `$1` … per
      `Matcher.appendReplacement` semantics.
    - `RENAME` — `pattern` matches the basename; `replacement` is the new
      basename. Refuses to collide with an existing file (error).
    - `DELETE_FILE` — remove matched files. `pattern`/`replacement`
      unused; entry matches only by `glob`.
    - `WRITE_FILE` — write `replacement` (treated literally, interpreted
      as UTF-8 bytes) to the single matched path. `glob` must match
      exactly one file path; creating a new file at that path is allowed.
  - `enabled: false` — rule is skipped (still validated). Mutual use of
    `pattern`/`replacement` is per-type required and enforced at parse time.
  - Validation: unknown `type`, unknown `regex_flags`, un-compilable
    `REPLACE_REGEX` pattern, missing required fields for the chosen `type`
    → `ConfigException` under fail-loud rules.
  - Example — swap Minecraft version string across the output:
    ```json
    { "type": "REPLACE_LITERAL", "glob": "**/build.gradle",
      "pattern": "1.19.4", "replacement": "1.21.1" }
    ```
- **`ModModel`** — read-only summary of the input: modid, name, version,
  authors, discovered files, presence of mixins/annotations. Used by
  `transform` to decide rewrites; rules target files by glob.
- **`PipelineContext`** — handed to every stage, carries config, resolved
  absolute paths (`inputDir`, `outputDir`, `cacheDir`, `workDir`), logger,
  and accumulated `List<StageResult>`. Paths resolved once, prior to run.
- **`StageResult`** — record: `stageId`, `status` (SUCCESS / SKIPPED /
  FAILED), `output`, `message`, `throwable` (nullable), `durationMs`.
- **`Manifest`** — written last into the output dir as
  `.transmuder-manifest.json`; holds the run summary (stage results, overall
  status, timestamps). Also serves as the audit trail for the output dir:
  its presence vs. absence distinguishes a completed run from a torn one.

## 5. Contracts Between Stages

Interfaces/abstract classes only — no implementation:

```text
Stage           — run(PipelineContext): StageResult; id() for selection
AbstractStage   — base class: stages the run, wraps Throwable into
                  StageResult, honors stopIfFail
StageSequencer  — runs a List<Stage> in order; returns List<StageResult>;
                  stops early when stopIfFail and a failure arrives
PipelineBuilder— builds the concrete ordered stage list given Config
ConfigLoader   — parse(Path): Config (throws ConfigException)
PathResolver   — resolve(Config): ResolvedPaths
BuildRunner     — run(Path outputDir): BuildOutcome  (swappable in tests)
ModReader       — read(Path inputDir): ModModel + raw file listing
ModRewriter     — apply(RewriteData, file set): transformed output writes
```

Concrete notes that make the contracts usable for implementation:

- `Stage` carries `id`, one of `stage-download` / `stage-unpack` /
  `stage-transform` / `stage-validate`. CLI `--only`/`--from` select these
  ids; unselected stages report SKIPPED.
- No DI framework: `PipelineBuilder` in `Main` news up stages with their
  deps (download client, unpacker, etc.) — constructor injection is manual
  but explicit. A `Factory<Stage>` is only added if it earns its place.
- `BuildRunner` is separate from `stage.validate` so tests stub the Gradle
  invocation with a fixed outcome; `stage.validate` only interprets it.
- `ModReader` / `ModRewriter` are the cut points between understanding the
  input and mutating the output; `transform` orchestrates them in sequence.
- Errors are typed; nothing returns `null`.

## 6. CLI Surface

Single `run` command with flags; flags override config keys (config stays the
source of truth for anything not overridden):

- `--config <file>` — path to JSON config (also positional). Default
  `transmuder.json`.
- `--template-url <url>`, `--input-dir <path>`, `--output-dir <path>`,
  `--rewrite-data <file>` — override the matching Config field. The
  `--rewrite-data` override still resolves to an *inline JSON array of
  rules* read from that file (same §4 schema), not a new schema.
- `--stop-if-fail <bool>` — override.
- `--only <stageId>` — run only the named stage (no prerequisites).
- `--from <stageId>` — run the named stage and everything after it.
- `--dry-run` — resolve paths, validate config, print the planned stage
  order and exit 0. No side effects, no disk writes.
- `--verbose` / `--quiet` — log level (see §7).
- `--version`, `--help`.

Exit codes are defined in §7.

## 7. Logging and Error Handling

- **Logging**: a tiny hand-rolled `Logger` on `stderr`, levels
  `ERROR/WARN/INFO/DEBUG`, default INFO; `--quiet` → WARN+, `--verbose` →
  DEBUG+. No framework (one less dep, trivial for a CLI). All human-readable
  logs go to stderr so stdout stays machine-clean.
- **Machine output**: at the end, a single JSON summary line on stdout with
  per-stage status and the overall status — the contract with any wrapper
  script. Skipped stages appear as `"validate": "SKIPPED"`.
- **Fail loudly**: root `TransmuderException` + typed subclasses
  (`ConfigException`, `DownloadException`, `UnpackException`,
  `TransformException`, `ValidationException`). `Main` catches it, prints
  `ERROR: <message>` (stack trace in `--verbose`), exits with the mapped code.
- **Exit codes** (set by `Main`, final):
  - `0` — all executed stages SUCCESS, or **validate was skipped** (e.g.
    selected out via `--only`/`--from`); in the skips-validate case the JSON
    summary must report `"validate": "SKIPPED"` on stdout.
  - `1` — generic failure.
  - `2` — config error.
  - `3` — download failure.
  - `4` — unpack failure.
  - `5` — transform failure.
  - `6` — validation failure.
  - There is no "continue-and-exit-zero" path: if any executed stage FAILED,
    the exit code reflects it. The `validate: SKIPPED` → exit `0` rule is the
    sole exception, by design (selection is an explicit user intent, not a
    silent failure).
- **stop_if_fail semantics**:
  - `true` — first failing step aborts the remaining pipeline; exit non-zero.
  - `false` — run all steps, collect failures, exit non-zero if any failed.
  - Exit `0` only when every executed stage is SUCCESS (SKIPPED allowed for
    the skipped-validate case).
- **Idempotency & output ownership (staging + atomic move)**:
  - The tool owns exactly two directories: the cache dir (downloads) and the
    output dir. It never writes to the input dir.
  - `unpack` never mutates the live output dir. It unpacks the zip into a
    fresh staging directory created per run (a sibling of the output dir,
    e.g. `<output>.staging.<runId>`), then swaps it in with an atomic
    `Files.move(staging, output, REPLACE_EXISTING, ATOMIC_MOVE)`, falling
    back to delete-and-rename only within the same parent and only after
    verifying the target path is the configured output dir. A failed or
    interrupted unpack is detected by zip-slip checks / IO errors, discards
    the staging dir, and leaves the previous output fully intact — there is
    never a torn output tree on disk.
  - Where the clean happens relative to unpack: there is **no explicit
    pre-clean of the output dir**. The atomic swap *is* the clean — every
    run starts from a pristine template tree. In particular, source files in
    the output tree are never statefully merged across runs, so `transform`
    partial output from a failed prior run cannot leak into the next run:
    unpack has already replaced the whole tree.
  - The delete-only-if-configured guard still applies to the fallback path:
    the tool refuses to delete anything whose resolved absolute path is not
    the exact path it recorded at startup.
  - `transform` writes directly into the (post-swap) output dir and writes
    the manifest last; a failing `transform` may leave partial output, which
    the next run's atomic swap discards. The output is kept in place on
    validation failure for inspection — repeated runs remain idempotent.
  - `unpack` runs on every invocation (re-extracting the cached zip); no
    skip-by-comparison logic in v1 — this is what makes reruns trivially
    deterministic.
  - Download caching is keyed by URL (+ optional size digest from
    `templateSha256`) so repeat runs skip the network fetch, with identical
    behavior on cache hit or miss.
  - Deterministic order: `download → unpack → transform → validate`;
    manifest written last.
  - Staging dirs are cleaned up on both success (moved away) and failure
    (deleted) unless `--keep-staging` (hidden, for debugging) is set.

## 8. Validation Stage Semantics

- `stage-validate` invokes Gradle (`./gradlew` in the output tree — the
  Fabric template ships a wrapper) via `BuildRunner`, streaming build output
  to the logger's DEBUG channel and honoring `timeoutSeconds`.
- A nonzero Gradle exit is a FAILED stage result; the output tree is kept for
  inspection.
- The Fabric template requires network for first-time dependency resolution
  and a JDK; the tool auto-detects `JAVA_HOME` then `java` on PATH (compiled
  `BuildRunner` behavior) and fails fast with a clear message if neither is
  found.
- If `validate` is not in the selected stage set (`--only`/`--from`), it
  reports SKIPPED and the process exits 0 (see §7).

## 9. Confirmed Decisions (v3)

Closed items — do not re-open:

1. **rewrite_data semantics** — inline JSON array in the config; schema
   defined explicitly in §4. `Config`/`RewriteData` hold it as typed rules.
2. **Text-based rewriting** — v1 transform is textual (literal/regex
   replace over file contents), no Java AST parser. Justification: matches
   the prototype's behavior, zero extra dependencies, deterministic. An
   AST-based rewrite is out of scope for v1; revisit only when textual
   replacement proves insufficient.
3. **Post-unpack cleanup** — after extraction, delete `.git`, `LICENSE`,
   `README.md`; keep gradle files and resources. Implemented inside
   `stage-unpack` before the atomic swap, so the output tree never contains
   them.
4. **Resources** — non-Java resources (textures, lang/data files) are
   copied through from the template and, where relevant, from the input
   untouched in v1; no filtering or migration.
5. **JDK detection** — auto-detect `JAVA_HOME` then `java` on PATH; fail
   with a clear message if absent (enforced by `BuildRunner`; §8).
6. **Checksum** — optional `template_sha256` config field, default absent;
   when present, verified against the downloaded zip before unpack.
7. **Network/profile** — default connect/read timeouts for the HTTP download;
   no proxy configuration in v1 (documented limitation).
8. **Forge feature scope (v1)** — translate only `@Mod` (main class
   annotation), `@SubscribeEvent`, and basic item/block registration
   patterns; anything deeper is out of scope and reported as a transform
   warning, never silently dropped.
9. **fabric.mod.json** — always present from the template; the tool in v1
   only translates existing metadata (mod id/name/version), and never
   generates a `fabric.mod.json` from scratch.

No open architecture items remain; implementation can start from any stage.

---

## Changelog v2 → v3

- D1(c): `rewrite_data` is now explicitly an inline JSON array with a
  defined schema (§4) and rule types; removed the "copied from prototype"
  wording and the old §9.1 open item; `--rewrite-data <file>` still reads an
  array of the same schema.
- D2: `Manifest` stays in v1 — kept in §4 and §7, still referenced in the
  transform row; clarified its role as the run/audit trail in the output dir.
- D3: output ownership rewritten for staging + atomic move — `unpack` writes
  to a per-run staging dir and atomically swaps it in; no explicit pre-clean
  of the output dir, the swap is the clean; partial `transform` state cannot
  leak across runs; guarded delete for the fallback path.
- C1: resolved by D1(c) — inline schema defined in §4, no reference to the
  prototype shape remains anywhere.
- C2: `transform` failure mode fixed — "unreadable as text / encoding
  mismatch" instead of "non-parseable files", consistent with text-based v1
  rewriting (§3 and §9.2).
- C3/D4: exit-code section now states explicitly that a skipped `validate`
  → exit 0 with `"validate": "SKIPPED"` on the stdout JSON summary; the
  "only exception" rule is spelled out (§7).
- §3: stage ids added inline (`stage-download` … `stage-validate`); transform
  row now says "apply the v1 rule set" and mentions the manifest write.
- §1: Jackson scope note, wrapper/versioning bullets tidied; removed
  leftover "CI-authored via maven id" and stray `$`/`>>` artifacts.
- §9: renumbered into a closed "Confirmed Decisions" list (items 1–9 from
  the owner review), each with a one-sentence consequence; stripped the old
  open-questions framing.
- Version title updated in the document header.
