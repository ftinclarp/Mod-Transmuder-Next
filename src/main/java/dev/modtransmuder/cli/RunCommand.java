package dev.modtransmuder.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.modtransmuder.config.Config;
import dev.modtransmuder.config.ConfigLoader;
import dev.modtransmuder.error.ConfigException;
import dev.modtransmuder.error.ExitCode;
import dev.modtransmuder.util.Logger;
import dev.modtransmuder.util.PathResolver;
import dev.modtransmuder.util.PathResolver.ResolvedPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The {@code run} command — the whole CLI surface of ARCHITECTURE §6.
 *
 * <p>Step 1 (skeleton): only {@code --dry-run} is functional. Every other
 * flag is declared so {@code --help} shows the full §6 surface and the wiring
 * for later steps is already in place. CLI overrides are applied to the
 * config (see {@link #applyOverrides}).
 */
@Command(
        name = "run",
        description = "Port a Forge 1.7.10 mod to Fabric 1.21.1.",
        mixinStandardHelpOptions = true,
        version = "0.1.0-SNAPSHOT")
public final class RunCommand implements Callable<Integer> {

    /** Keep in sync with gradle.properties {@code version=}. */
    static final String VERSION = "0.1.0-SNAPSHOT";

    /** Static stage order from ARCHITECTURE §3 (no implementations yet). */
    static final List<String> STAGE_ORDER = List.of(
            "stage-download", "stage-unpack", "stage-transform", "stage-validate");

    private final ConfigLoader loader = new ConfigLoader();
    private final ObjectMapper json = new ObjectMapper();
    // ---------------- config selection ----------------
    @Option(names = "--config", paramLabel = "<file>",
            description = "Path to JSON config (default: transmuder.json). Same as the positional argument.")
    private Path configPath;

    @Parameters(index = "0", arity = "0..1", paramLabel = "config",
            description = "Path to JSON config (default: transmuder.json). Same as --config.")
    private Path configPositional;

    // ---------------- config overrides (§6) ----------------
    @Option(names = "--template-url", paramLabel = "<url>",
            description = "Override template_zip_url.")
    private String templateUrl;

    @Option(names = "--input-dir", paramLabel = "<path>",
            description = "Override transmudation_input.")
    private String inputDir;

    @Option(names = "--output-dir", paramLabel = "<path>",
            description = "Override transmudation_output.")
    private String outputDir;

    @Option(names = "--rewrite-data", paramLabel = "<file>",
            description = "Read an inline array of rules (same §4 schema) from this file and use it in place of rewrite_data.")
    private Path rewriteDataFile;

    @Option(names = "--stop-if-fail", arity = "1", paramLabel = "<bool>",
            description = "Override stop_if_fail (true/false).")
    private Boolean stopIfFail;

    // ---------------- stage selection (§6) ----------------
    @Option(names = "--only", paramLabel = "<stageId>",
            description = "Run only this stage, no prerequisites.")
    private String only;

    @Option(names = "--from", paramLabel = "<stageId>",
            description = "Run this stage and everything after it.")
    private String from;

    // ---------------- behavior ----------------
    @Option(names = "--dry-run", description = "Validate config and print the planned stage order, touching no disk, then exit 0.")
    private boolean dryRun;

    @Option(names = "--verbose", description = "DEBUG logging and stack traces.")
    private boolean verbose;

    @Option(names = "--quiet", description = "Only WARN+ logging.")
    private boolean quiet;

    @Option(names = "--keep-staging", hidden = true,
            description = "Keep staging dirs after a run (debug).")
    private boolean keepStaging;

    @Override
    public Integer call() {
        Logger logger = new Logger(effectiveLevel(null));

        Config config;
        try {
            config = loader.load(effectiveConfigPath());
        } catch (ConfigException e) {
            reportError(logger, e);
            return ExitCode.CONFIG.value();
        }

        logger.setThreshold(effectiveLevel(config));
        try {
            config = applyOverrides(config);
            ResolvedPaths paths = PathResolver.resolve(config);
            logger.debug("resolved input=" + paths.inputDir()
                    + " output=" + paths.outputDir()
                    + " cache=" + paths.cacheDir()
                    + " work=" + paths.workDir());
        } catch (ConfigException e) {
            reportError(logger, e);
            return ExitCode.CONFIG.value();
        }

        if (dryRun) {
            printPlan();
            printJsonSummary("dry-run", "dry-run");
            return ExitCode.OK.value();
        }

        logger.error("not implemented yet: only --dry-run is functional in this skeleton");
        printJsonSummary("not-implemented", "not implemented");
        return ExitCode.GENERIC.value();
    }

    private Logger.Level effectiveLevel(Config config) {
        if (quiet) {
            return Logger.Level.WARN;
        }
        if (verbose || Boolean.TRUE.equals(config != null ? config.verbose() : null)) {
            return Logger.Level.DEBUG;
        }
        return Logger.Level.INFO;
    }

    private void reportError(Logger logger, Exception e) {
        logger.error(e.getMessage());
        if (verbose) {
            e.printStackTrace(System.err);
        }
    }

    private Path effectiveConfigPath() {
        if (configPath != null) {
            return configPath;
        }
        if (configPositional != null) {
            return configPositional;
        }
        return Path.of("transmuder.json");
    }

    /**
     * Merge CLI overrides onto the raw config (ARCHITECTURE §6). Non-null
     * override fields win; the result is a fresh immutable {@link Config}.
     */
    private Config applyOverrides(Config raw) {
        return new Config(
                templateUrl != null ? templateUrl : raw.templateZipUrl(),
                outputDir != null ? outputDir : raw.transmudationOutput(),
                inputDir != null ? inputDir : raw.transmudationInput(),
                rewriteDataFile != null ? readRewriteDataFile() : raw.rewriteData(),
                stopIfFail != null ? stopIfFail : raw.stopIfFail(),
                raw.cacheDir(),
                raw.timeoutSeconds(),
                raw.templateSha256(),
                raw.verbose());
    }

    private JsonNode readRewriteDataFile() {
        if (!Files.isRegularFile(rewriteDataFile)) {
            throw new ConfigException("--rewrite-data file not found: " + rewriteDataFile);
        }
        try {
            JsonNode node = json.readTree(Files.readString(rewriteDataFile, StandardCharsets.UTF_8));
            if (node == null || !node.isArray()) {
                throw new ConfigException("--rewrite-data file must contain a JSON array: " + rewriteDataFile);
            }
            return node;
        } catch (ConfigException e) {
            throw e;
        } catch (IOException e) {
            throw new ConfigException("cannot read --rewrite-data file " + rewriteDataFile + ": " + e.getMessage(), e);
        }
    }

    private void printPlan() {
        System.out.println("Planned stages (dry-run, nothing executed):");
        for (String id : STAGE_ORDER) {
            System.out.println("  " + id);
        }
    }

    /**
     * Machine-readable one-line JSON summary on stdout (ARCHITECTURE §7). In
     * dry-run every stage reports SKIPPED with the given note; the note is
     * "dry-run" for dry-run and "not implemented" otherwise.
     */
    private void printJsonSummary(String overall, String note) {
        ObjectNode root = json.createObjectNode();
        root.put("overall", overall);
        ArrayNode stages = root.putArray("stages");
        for (String id : STAGE_ORDER) {
            ObjectNode stage = stages.addObject();
            stage.put("id", id);
            stage.put("status", "SKIPPED");
            if (note != null) {
                stage.put("note", note);
            }
        }
        try {
            System.out.println(json.writeValueAsString(root));
        } catch (IOException e) {
            System.err.println("ERROR: failed to serialize JSON summary: " + e.getMessage());
        }
    }
}
