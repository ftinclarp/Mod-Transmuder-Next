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

---

## Strategic decisions and known gaps

This section records long-term strategy and known limitations.
It is NOT the v1 scope. v1 is what the pipeline currently does:
translate sources, wire layers, generate resources, validate build.

## Current port state (after batch 3)

Layer coverage: Bucket A is empty. All layer-tail classes and
methods Iron Chest needs are present as stubs (with TODO(mtn-layer)
markers).

Remaining for a full Iron Chest port: Bucket B, which is not
layer-tail. It requires real implementations, not stubs:

  - Client rendering (47 errors): R4b (TileEntitySpecialRenderer
    -> Fabric BlockEntityRenderer) + R4c (ModelChest + GL11 ->
    BufferBuilder).
  - Network (16 errors): Packet + FMLProxyPacket +
    FMLEmbeddedChannel -> Fabric networking.
  - OreDictionary (4 errors): -> Fabric tags.
  - MinecraftForge event bus (2 errors): -> Fabric callbacks (R5).

None of these can be stubbed meaningfully. They are separate
modules, to be tackled one at a time, not as a "layer batch".

Decision pending: which module to start next.

### Technical debt: ForgeDirection placement

The current placement of ForgeDirection in minecraft-layer (at the
literal net.minecraftforge.common.util path) is a workaround so that
Block's rotateBlock/getValidRotations and World.isSideSolid can
compile without minecraft-layer depending on forge-layer. It works
but is semantically wrong.

TODO: consider moving ForgeDirection back to forge-layer and routing
Block/World methods through an interface, if the layering ever needs
to be strict.

### Modes of porting

MTN's v1 approach is called Mode T (source translation):

  Mode T — Source translation (current):
    Input:  mod sources (open-source mods).
    Tool:   regex/AST rewrite of imports and calls, javac.
    Output: rewritten sources -> compiled ported jar.
    Pros:   simple, debuggable, compile-time errors.
    Cons:   requires sources.

Three additional modes are acknowledged but NOT part of v1:

  Mode B — Offline Mixin/ASM translation:
    Read Mixin classes / Forge ASM transformers from the mod,
    translate to Fabric-compatible Mixin classes at port time.
    Covers simple @Inject / @Redirect / @ModifyConstant.
    Partial coverage; @Overwrite and bytecode tricks need manual work.

  Mode C — Targeted class baking:
    Extract only the Minecraft classes that a mod patches via
    coremod/ASM. Replace their imports with layer packages, apply
    patches, compile, substitute the layer's dummy class.
    Dependency graph tends to explode; runtime compilation is heavy.
    Experimental. Nobody has done this publicly.

  Mode X — Runtime bytecode transformation (Sinytra-style):
    Load real Forge jar as-is in an isolated classloader; provide
    Forge API shims; use Mixin/ASM to intercept calls.
    Comparable to Sinytra Connector for 1.18+.
    Different project, team-scale, years of work.
    NOT part of MTN. See "Fundamental limits" below.

### Transformers (separate repos, not part of core)

  MTN-mixin-transformer:
    Translates UniMixins 1.7.10 Mixin classes to Fabric 1.21.1 Mixin.
    - Read @Mixin / @Inject / @Redirect / @At annotations.
    - Translate MCP names -> Yarn/Mojmap.
    - Translate method descriptors and pointcuts.
    - Generate Fabric-compatible Mixin + mixins.<modid>.json.
    Supports: @Inject, @Redirect, @ModifyConstant.
    Partial: @Overwrite with large bodies, bytecode tricks.

  MTN-lwjgl-transformer:
    Translates LWJGL 2 calls (Forge 1.7.10) to LWJGL 3 / Fabric
    render API (1.21.1).
    - GL11.glBegin/glVertex/glEnd -> BufferBuilder.
    - glPushMatrix/glTranslate/glRotate -> PoseStack.
    - Display.create -> no-op (Fabric handles windowing).
    - Keyboard/Mouse -> Fabric input API.
    Partial: fixed-function pipeline (lighting, fog) needs manual
    rewrite or shaders.

  MTN-fake-api (mono-repo, subproject per API):
    Runtime adapters for third-party APIs, distributed as standalone
    Fabric mods. User installs only the ones they need.
    Examples:
      - NEI -> JEI / EMI / REI (separate subprojects per target).
      - WAILA -> Jade.
      - Baubles -> Trinkets.
      - Inventory Tweaks -> Quark / Inventorio / no-op.
    Each subproject is a separate Fabric mod with its own
    fabric.mod.json and its own JitPack tag.

### Pipeline order

When porting a mod that uses Mixin and/or LWJGL:

  1. transform (base port)
       - rewrite imports: net.minecraft.* -> mtn.minecraft_layer.*,
         cpw.mods.fml.* -> mtn.forge_layer.*
       - copy resources, generate models/textures/lang
       - generate fabric.mod.json

  2. MTN-lwjgl-transformer (if the mod uses LWJGL directly)
       - rewrite GL11.* / Display.* / Keyboard.* / Mouse.* calls to
         Fabric render API
       - must run BEFORE Mixin because Mixin may inject into methods
         that originally contained LWJGL calls

  3. layer wiring
       - ported mod depends on MTN-forge-layer + MTN-minecraft-layer
       - fabric.mod.json declares this dependency

  4. MTN-mixin-transformer (if the mod uses Mixin)
       - translate Mixin classes, generate Fabric Mixin + json
       - register in fabric.mod.json
       - runs LAST so Mixin patches apply to already translated code

  5. assemble and validate (gradle build)

### Performance budget

Target (not guarantee) for a medium mod on a warm Gradle cache:

  transform (source rewriting):
    Small mod (<100 files):   5-15 s
    Medium mod (100-500):     15-60 s
    Large mod (>500):         1-5 min
  MTN-lwjgl-transformer:     +5-15 s
  MTN-mixin-transformer:     +10-30 s (more for hundreds of Mixins)
  gradle build (cold loom):  5-15 min (first time only)
  gradle build (warm):       30-120 s

Not in the budget: first-time loom setup, runtime decompilation
(Mode X / Mode 1), full Minecraft bake.

### Known gaps and their plans

Coverage: ~95% of known gaps have a plan. The rest are fundamental
limits or require a separate project.

### Dynamic recipe registration

Transform's RecipeGenerator handles static recipe registrations
(constant arguments to GameRegistry.addRecipe / addShapedRecipe /
addShapelessRecipe). It does not handle recipes built dynamically
inside runtime loops, conditions, or methods (e.g. Iron Chest's
IronChestType.registerBlocksAndRecipes loops over tiers and builds
a ShapedOreRecipe per tier).

In such cases the stage logs `[MTN-IGNORE] dynamic recipe
registration: <file>:<line>` and skips the recipe.

Consequence: dynamic recipes do not appear in the generated port's
data/<modid>/recipes/. In-game, the affected items are not craftable
in the normal crafting table.

Workaround for v1: obtain the items via /give or the creative tab.

Long-term: either extend the transform to execute the registration
code in a sandbox and capture the resulting recipe objects, or
hand-write the JSON recipes for the specific mod. Both are separate
tasks, not part of the current pipeline.

Oredict tags are still emitted for the OreDictionary.registerOre
calls, so generated recipes that reference c:chests/wooden will
work if such a recipe is written by hand later.

  Gaps with a plan (ordered by difficulty):

  1. Recipes:
     CraftingManager.addRecipe -> generate JSON recipe files
     in data/<modid>/recipes/. Implemented via transform +
     a new RecipeRewriter stage.

  2. OreDictionary:
     OreDictionary.registerOre("name", item) -> JSON tag in
     data/<modid>/tags/items/. Mapping via a name dictionary
     (ingotCopper -> c:ingots/copper). Implemented in
     ResourceGenerator.

  3. Config:
     Forge Configuration -> JSON/TOML + a layer adapter that reads
     it. Optional GUI via ClothConfig/ModMenu.

  4. Keybindings:
     KeyBinding delegates to KeyBindingHelper.registerKeyBinding.
     Forge -> Fabric key codes: 1:1.

  5. Commands:
     ICommand -> Fabric Brigadier command. Layer provides a
     CommandBase wrapper for argument parsing.

  6. Access Transformers:
     Parse *_at.cfg -> generate .accesswidener. Names via mapping.

  7. World generation:
     IWorldGenerator -> Fabric BiomeModification + JSON features.
     Ore features: simple mapping. Structures: complex mapping
     (Jigsaw). Seed compatibility not guaranteed.

  8. Entity + AI:
     EntityRegistry.registerModEntity -> Registry.register(
     Registries.ENTITY_TYPE, ...). EntityAIBase -> vanilla/Fabric
     Goal. Mapping of AI behaviors via dictionary.

  9. Server-side APIs:
     MinecraftServer / ServerConfigurationManager -> layer wrappers
     over Fabric equivalents.

  10. Client-side APIs:
      ClientRegistry / FMLClientHandler -> wrappers over
      ClientModInitializer / MinecraftClient. Custom models via
      ModelLoadingPlugin.

  11. Reflection (ObfuscationReflectionHelper):
      Transformer replaces reflection with MCP names by direct
      calls via Fabric mappings. Complex but works for typical
      cases.

  12. Fluids:
      FluidRegistry.registerFluid / BlockFluidBase -> Fabric
      Fluid / FlowableFluid / FluidBlock. Fluid rendering separate.

  13. Energy systems:
      Fake-api for each system (IC2 EU -> Fabric Energy,
      BC MJ -> Fabric Energy, RF -> Fabric Energy). Ratios:
      1 EU ~ 4 RF, 1 MJ ~ 10 RF.

  14. Custom rendering (TESR):
      Partial via MTN-lwjgl-transformer (immediate mode ->
      BufferBuilder). Partial manual (fixed pipeline -> shaders).
      Simple TESRs work; complex (shaders, post-processing) need
      manual rewrite. This is R4 + LWJGL transformer scope.

  15. Multiblocks and structures:
      No unified plan. Each mod does its own logic via TileEntity.
      Layer provides the TileEntity API; structure verification is
      the mod's responsibility. Works only via manual patches.

  16. Mixin / coremods:
      MTN-mixin-transformer handles simple patches. Complex patches
      need manual translation. See Mode B.

  17. Mod dependencies:
      Dependency graph + recursive porting. fake-api for
      integrations. Works, but slow for large ecosystems.

  18. Dynamic class loading:
      Custom classloader per ported mod that resolves dynamic
      Class.forName calls. Complex, fragile. Handled case-by-case.

  19. Data fixers:
      If the mod's data is plain NBT, Fabric usually handles it.
      Complex data needs manual migration via DataFixerUpper.

  20. Texture atlas and models:
      Mostly done in ResourceGenerator. Needs finishing for blocks
      with custom models. R3c/R4 scope.

  Fundamental limits (no plan):

  - Full Minecraft rewrites (TFC-style): would require rewriting
    Minecraft itself, not a porting tool.
  - Native code heavily coupled to 1.7.10 OpenGL: needs rewriting
    in C++ or via shaders.
  - Mods whose behavior fundamentally depends on 1.7.10 bytecode
    tricks: not expressible in a layer.

### Fundamental limits of layer-based translation

Layer-based translation works when the mod uses public Forge /
Minecraft API as intended. It fails when the mod:

  - Patches Minecraft via ASM / Mixin / coremod (needs Mode B, X).
  - Uses reflection into internal Minecraft APIs (needs per-case
    emulation or bytecode transform).
  - Depends on initialization order between mods (needs manual
    patch).
  - Relies on bytecode-specific Minecraft behavior (needs Mode X).

Estimated coverage: 80-90% of 1.7.10 mods.
The remaining 10-20% require transformers, fake-api, manual
patches, or a different project entirely.

MTN is honest about these limits. It does not promise 100%
coverage.

### Agent swarm (idea, not planned)

For very large mods (GT5, Thaumcraft, IC2) a future idea is an
"agent swarm" that translates classes in parallel with automatic
validation:

  - N agents each translate one class independently.
  - Automatic validation (compile + tests + behavior diff).
  - Escalation to human on failure.
  - Open questions: coordination, validation methodology, token
    cost.

This is a separate project (MTN-factory or similar). Not part of
MTN. Requires the base pipeline (Mode T) to be stable first.
