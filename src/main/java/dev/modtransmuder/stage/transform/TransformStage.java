package dev.modtransmuder.stage.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.modtransmuder.config.Config;
import dev.modtransmuder.error.TransformException;
import dev.modtransmuder.pipeline.PipelineContext;
import dev.modtransmuder.pipeline.Stage;
import dev.modtransmuder.pipeline.StageResult;
import dev.modtransmuder.pipeline.Status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code stage-transform} (ARCHITECTURE §5, stage 4).
 *
 * <p>Reads a Forge 1.7.10 mod from {@code transmudation_input}, copies its
 * sources into the already-unpacked Fabric output tree, rewrites Forge imports
 * to the MTN compatibility layer, wires the layer into the output build, and
 * writes a generated {@code fabric.mod.json}.
 *
 * <p>Mod discovery: the mod root is {@code inputDir} itself when it contains
 * {@code src/main/resources/mcmod.info}, otherwise the first direct
 * subdirectory that does (covers config {@code in-mods} → {@code
 * in-mods/MTN-example-forge-mod}). Fails with a clear message when no
 * {@code mcmod.info} is found.
 */
public final class TransformStage implements Stage {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Comment we add to the output build, per stage 4d. */
    static final String LAYER_COMMENT = "// added by MTN: Forge 1.7.10 compatibility layer";
    static final String LAYER_JITPACK_URL = "https://jitpack.io";

    /**
     * Single source of truth for the layer's remote (JitPack) version, used
     * both as {@link Config}'s default and when emitting the dependency into
     * the output build file.
     */
    public static final String DEFAULT_REMOTE_LAYER_VERSION = "v1.0.3";

    /** Entrypoint key in the generated fabric.mod.json naming the @Mod class. */
    static final String FORGE_MOD_CLASS_ENTRYPOINT = "mtn:forge-mod-class";
    /** Annotation whose class becomes the entrypoint target. */
    private static final String MOD_ANNOTATION_LEAD = "@Mod";

    @Override
    public String id() {
        return "stage-transform";
    }

    @Override
    public StageResult run(PipelineContext ctx) {
        try {
            TransformSummary summary = transform(ctx);
            return new StageResult(id(), Status.SUCCESS, summary.message(), null, 0L);
        } catch (TransformException e) {
            return new StageResult(id(), Status.FAILED, e.getMessage(), e, 0L);
        } catch (RuntimeException e) {
            return new StageResult(id(), Status.FAILED, "transform failed: " + e.getMessage(), e, 0L);
        }
    }

    private TransformSummary transform(PipelineContext ctx) {
        Path inputDir = ctx.paths().inputDir().toAbsolutePath().normalize();
        Path outputDir = ctx.paths().outputDir().toAbsolutePath().normalize();

        // 4a — parse input metadata (mod discovery + parse).
        Path modRoot = resolveModRoot(inputDir);
        ForgeModInfo info = McmodInfoParser.parse(modRoot.resolve("src/main/resources/mcmod.info"));

        // 4b — copy sources, removing the template's example package.
        Path outJava = outputDir.resolve("src/main/java");
        Path outResources = outputDir.resolve("src/main/resources");
        deleteTree(outJava.resolve("com/example"));

        int copied = 0;
        copied += copyJava(modRoot, outJava);
        copied += copyResourcesExceptMcmodInfo(modRoot, outResources);

        // 4c — rewrite Forge imports in the copied Java files.
        int rewritten = rewriteJavaImports(outJava);

        // 4c' — discover the @Mod-annotated class in the copied sources.
        String modClassName = discoverModClass(outJava);

        // 4d — wire the layer into the output build (local or JitPack per config).
        wireLayerIntoBuild(outputDir, ctx.config());

        // 4e — generate fabric.mod.json.
        writeFabricModJson(outResources.resolve("fabric.mod.json"), info, modClassName);

        String message = "modid=" + info.modid() + " copies=" + copied
                + " rewritten=" + rewritten + " layersWired=1";
        return new TransformSummary(message);
    }

    // ---------------- 4b helpers ----------------

    private int copyJava(Path modRoot, Path outJava) {
        Path inJava = modRoot.resolve("src/main/java");
        return copyTree(inJava, outJava);
    }

    /** Copies {@code src/main/resources} files, except {@code mcmod.info}. */
    private int copyResourcesExceptMcmodInfo(Path modRoot, Path outResources) {
        Path inResources = modRoot.resolve("src/main/resources");
        return copyTreeFiltered(inResources, outResources, p -> !p.getFileName().toString().equals("mcmod.info"));
    }

    /**
     * Recursively copies every regular file (preserving relative paths) from
     * {@code src} into {@code dst}. Returns the number of files copied.
     */
    private static int copyTree(Path src, Path dst) {
        return copyTreeFiltered(src, dst, ignore -> true);
    }

    private static int copyTreeFiltered(Path src, Path dst, java.util.function.Predicate<Path> accept) {
        if (!Files.isDirectory(src)) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger();
        try (var walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile)
                    .filter(accept)
                    .forEach(p -> {
                        Path rel = src.relativize(p);
                        Path target = dst.resolve(rel);
                        try {
                            Files.createDirectories(target.getParent());
                            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                            count.incrementAndGet();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return count.get();
    }

    // ---------------- 4c helper ----------------

    private int rewriteJavaImports(Path outJava) {
        if (!Files.isDirectory(outJava)) {
            return 0;
        }
        AtomicInteger changed = new AtomicInteger();
        try (var walk = Files.walk(outJava)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            String rewritten = ImportRewriter.rewriteFml(text);
                            if (!rewritten.equals(text)) {
                                Files.writeString(p, rewritten, StandardCharsets.UTF_8);
                                changed.incrementAndGet();
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return changed.get();
    }

    // ---------------- 4d helper ----------------

    /**
     * Adds the layer dependency to the output build file
     * ({@code build.gradle} — Loom's Groovy template), prefixed with
     * {@link #LAYER_COMMENT}. Idempotent: no-op when the dependency line is
     * already present.
     *
     * <p>When {@code useLocalLayer}, the source is the local Maven repo
     * ({@code mavenLocal()}) and the dependency uses the local version — a
     * fast dev loop with {@code publishToMavenLocal}, avoiding a JitPack
     * rebuild wait. Otherwise (default) JitPack is used with the remote
     * release version.
     */
    private void wireLayerIntoBuild(Path outputDir, Config config) {
        Path buildFile = outputDir.resolve("build.gradle");
        if (!Files.isRegularFile(buildFile)) {
            // Fall back to Kotlin DSL name if a Groovy template is absent.
            buildFile = outputDir.resolve("build.gradle.kts");
        }
        if (!Files.isRegularFile(buildFile)) {
            throw new TransformException("no build.gradle(.kts) found in output: " + outputDir);
        }
        try {
            String text = Files.readString(buildFile, StandardCharsets.UTF_8);
            boolean local = config.useLocalLayerOrFalse();
            String depCoord = "com.github.ftinclarp:MTN-forge-layer:"
                    + (local ? config.effectiveLocalLayerVersion() : config.effectiveRemoteLayerVersion());
            if (text.contains(depCoord)) {
                return; // already wired
            }
            String repoBlock;
            if (local) {
                repoBlock = LAYER_COMMENT + "\n\tmavenLocal()";
            } else {
                repoBlock = LAYER_COMMENT + "\n\tmaven { url = \"https://jitpack.io\" }";
            }
            String depBlock = LAYER_COMMENT + "\n\tmodImplementation \"" + depCoord + "\"";
            String warped = text;
            if (!text.contains(LAYER_JITPACK_URL) && !text.contains("mavenLocal()")) {
                warped = insertAfterBlockLine(warped, "repositories {", repoBlock);
            }
            warped = insertAfterBlockLine(warped, "dependencies {", depBlock);
            Files.writeString(buildFile, warped, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TransformException("failed to wire layer into " + buildFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Inserts {@code block} (multi-line) immediately after the first line
     * whose trimmed form is exactly {@code anchor}, preserving file line
     * endings.
     */
    private static String insertAfterBlockLine(String text, String anchor, String block) {
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inserted = false;
        for (String line : lines) {
            sb.append(line).append(nl);
            if (!inserted && line.trim().equals(anchor)) {
                sb.append(block).append(nl);
                inserted = true;
            }
        }
        if (!inserted) {
            throw new TransformException("cannot locate '" + anchor + "' in build file to wire the layer");
        }
        return sb.toString();
    }

    // ---------------- 4e helper ----------------

    private void writeFabricModJson(Path target, ForgeModInfo info, String modClassName) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("id", info.modid());
        root.put("version", info.version());
        root.put("name", info.name());
        root.put("description", info.description());
        ArrayNode authors = root.putArray("authors");
        info.authors().forEach(authors::add);
        root.put("license", "MIT");
        root.put("environment", "*");

        ObjectNode entrypoints = root.putObject("entrypoints");
        entrypoints.putArray("main").add("mtn.forge_layer.FabricEntry");
        ArrayNode forgeMods = entrypoints.putArray(FORGE_MOD_CLASS_ENTRYPOINT);
        if (modClassName != null && !modClassName.isBlank()) {
            forgeMods.add(modClassName);
        }

        ObjectNode depends = root.putObject("depends");
        depends.put("fabricloader", ">=0.15.0");
        depends.put("fabric-api", "*");
        depends.put("minecraft", "~1.21.1");
        depends.put("java", ">=21");

        try {
            Files.deleteIfExists(target);
            Files.writeString(target, pretty(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TransformException("failed to write " + target + ": " + e.getMessage(), e);
        }
    }

    private static String pretty(ObjectNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------- mod discovery ----------------

    /**
     * {@code inputDir} if it directly holds {@code src/main/resources/mcmod.info},
     * else the first direct subdirectory that does. Fails if none is found.
     */
    private static Path resolveModRoot(Path inputDir) {
        if (hasMcmodInfo(inputDir)) {
            return inputDir;
        }
        List<Path> subdirs;
        try (var stream = Files.list(inputDir)) {
            subdirs = stream.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) {
            throw new TransformException("cannot scan input dir " + inputDir + ": " + e.getMessage(), e);
        }
        for (Path dir : subdirs) {
            if (hasMcmodInfo(dir)) {
                return dir;
            }
        }
        throw new TransformException("no Forge mod found under " + inputDir
                + " (expected src/main/resources/mcmod.info)");
    }

    private static boolean hasMcmodInfo(Path root) {
        return Files.isRegularFile(root.resolve("src/main/resources/mcmod.info"));
    }

    /**
     * Scans the copied (and already import-rewritten) Java sources for the
     * first top-level class annotated with {@code @Mod} and returns its
     * fully-qualified name (package from the {@code package} declaration plus
     * the source file's base name). Returns {@code null} when none is found.
     * The {@code @Mod} annotation survives the import rewrite (its simple name
     * is unchanged); {@code @Mod.EventHandler} usages inside method bodies
     * also contain {@code "@Mod"} so the match requires the annotation form
     * {@code "@Mod(" } that precedes a class-level annotation.
     */
    private static String discoverModClass(Path outJava) {
        if (!Files.isDirectory(outJava)) {
            return null;
        }
        try (var walk = Files.walk(outJava)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))::iterator) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (!hasClassLevelModAnnotation(text)) {
                    continue;
                }
                String pkg = packageName(text);
                String simple = p.getFileName().toString().replaceFirst("\\.java$", "");
                return pkg.isEmpty() ? simple : pkg + "." + simple;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
    }

    /** True when the source declares {@code package x.y.z;}. */
    private static String packageName(String source) {
        var m = java.util.regex.Pattern
                .compile("\\bpackage\\s+([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*)\\s*;")
                .matcher(source);
        return m.find() ? m.group(1) : "";
    }

    /** True when a class-level {@code @Mod} annotation is present (not {@code @Mod.EventHandler}). */
    private static boolean hasClassLevelModAnnotation(String source) {
        return source.contains("@Mod(") || source.contains("@Mod (");
    }

    // ---------------- misc ----------------

    private void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        List<Path> paths;
        try (var walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Carries the counts for the SUCCESS message. */
    private record TransformSummary(String message) {
    }
}
