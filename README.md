# Mod-Transmuder-Next

Turning gold into mud. This even worse than original transmuder

## Build & Run (skeleton)

Step 1 of 5 — compilable skeleton only. No pipeline stages exist yet:
`--dry-run` validates the config and prints the planned stage order,
everything else reports "not implemented yet".

Requirements: JDK 21 (Gradle toolchain), internet access on first run
(downloads the Gradle distribution and dependencies).

```sh
# Compile and run the tests
./gradlew build

# Show the full CLI surface (help)
./gradlew run --args="--help"

# Dry-run against a valid config (prints planned stages, exits 0)
./gradlew run --args="--dry-run --config transmuder.json"
```

A minimal config file `transmuder.json` looks like:

```json
{
  "template_zip_url": "https://example.com/fabric-example-mod.zip",
  "transmudation_output": "out",
  "transmudation_input": "in-mods",
  "rewrite_data": [],
  "stop_if_fail": true
}
```

Invalid config (missing keys, unknown keys, wrong types) → exit 2 with an
`ERROR:` line on stderr. See `docs/ARCHITECTURE.md` §6–§7 for the full CLI
and exit-code contract.

## License

MIT. See [LICENSE](LICENSE).

