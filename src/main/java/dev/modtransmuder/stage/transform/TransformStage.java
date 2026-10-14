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
import java.util.ArrayList;
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
 * <p>Mod discovery: {@code transmudation_input} points at the mod directory
 * directly. {@code resolveModRoot} verifies that the input "looks like a mod"
 * (has any {@code src/<name>/java} or {@code src/main/resources/mcmod.info})
 * and fails with a clear message otherwise. There is no container scanning.
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
    public static final String DEFAULT_REMOTE_LAYER_VERSION = "v2.4.4";

    /**
     * Single source of truth for the Minecraft API layer's remote (JitPack)
     * version, used both as {@link Config}'s default and when emitting the
     * dependency into the output build file.
     */
    public static final String DEFAULT_REMOTE_MINECRAFT_LAYER_VERSION = "v1.1.0";

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

        // 4a — validate the mod dir (transmudation_input is a single mod dir),
        // then parse input metadata (mod discovery + parse).
        Path modRoot = resolveModRoot(inputDir);
        ctx.setResolvedInputDir(modRoot);
        ForgeModInfo info = McmodInfoParser.parse(modRoot.resolve("src/main/resources/mcmod.info"));

        // 4b — copy sources from every src/<name>/java root, removing the
        // template's example package.
        Path outJava = outputDir.resolve("src/main/java");
        Path outResources = outputDir.resolve("src/main/resources");
        deleteTree(outJava.resolve("com/example"));

        int copied = copyJava(ctx, modRoot, outJava);

        // 4c — rewrite Forge imports in the copied Java files.
        int rewritten = rewriteJavaImports(outJava);

        // 4c' — discover the @Mod-annotated class in the copied sources.
        String modClassName = discoverModClass(outJava);

        // 4c'' — resource generation (copies resources with legacy-folder
        // rename, writes models/blockstates/textures/lang for registered names).
        for (var e : ResourceGenerator.resourceRootsWithCounts(modRoot, info.modid()).entrySet()) {
            ctx.logger().info("stage-transform: resource root src/" + e.getKey()
                    + "/resources contributed " + e.getValue() + " file(s)");
        }
        ResourceGenerator.ResourceStats resources =
                ResourceGenerator.generate(modRoot, outJava, outResources, info.modid());
        String resourcesLine = ResourceGenerator.summary(resources);
        RecipeGenerator.RecipeStats recipes =
                RecipeGenerator.generate(outJava, outResources, info.modid());
        String recipesLine = RecipeGenerator.summary(recipes);

        // 4d — wire the layer into the output build (local or JitPack per config).
        wireLayerIntoBuild(outputDir, ctx.config());

        // 4e — generate fabric.mod.json.
        writeFabricModJson(outResources.resolve("fabric.mod.json"), info, modClassName);

        String message = "modid=" + info.modid() + " copies=" + copied
                + " rewritten=" + rewritten + " layersWired=1 " + resourcesLine
                + " " + recipesLine;
        return new TransformSummary(message);
    }

    // ---------------- 4b helpers ----------------

    /**
     * Copies every {@code src/<name>/java} root under {@code modRoot} into
     * {@code outJava}, preserving each root's package structure (a relative
     * path inside the root is placed under the same relative path in the
     * output). Logs the source roots found and the per-root file counts. Fails
     * when the same relative output path would be produced by two different
     * roots (e.g. {@code src/main/java/foo/A.java} and {@code src/api/java/foo/A.java}).
     */
    private static int copyJava(PipelineContext ctx, Path modRoot, Path outJava) {
        List<Path> javaRoots = sourceRoots(modRoot, "java");
        if (javaRoots.isEmpty()) {
            ctx.logger().info("stage-transform: no Java source roots found");
            return 0;
        }
        int total = 0;
        for (Path root : javaRoots) {
            int n = copyTreeCollisionSafe(root, outJava);
            ctx.logger().info("stage-transform: copied " + n + " files from source root "
                    + toSlash(modRoot.relativize(root)) + " -> " + toSlash(outJava));
            total += n;
        }
        ctx.logger().info("stage-transform: found " + javaRoots.size() + " Java source root(s), "
                + total + " files total");
        return total;
    }

    /** Lists every {@code src/<name>/java} root under {@code modRoot}, sorted. */
    private static List<Path> sourceRoots(Path modRoot, String kind) {
        Path src = modRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return List.of();
        }
        List<Path> roots = new ArrayList<>();
        try (var stream = Files.list(src)) {
            stream.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(sub -> {
                        Path kindDir = sub.resolve(kind);
                        if (Files.isDirectory(kindDir)) {
                            roots.add(kindDir);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return roots;
    }

    /**
     * Copies every regular file from {@code src} into {@code dst}, preserving
     * relative paths. When two roots would target the same output file, the
     * second copy would overwrite — this method detects that by tracking output
     * paths already claimed by earlier roots and failing loudly instead.
     * Returns the number of files copied.
     */
    private static int copyTreeCollisionSafe(Path src, Path dst) {
        if (!Files.isDirectory(src)) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger();
        try (var walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> {
                        Path rel = src.relativize(p);
                        Path target = dst.resolve(rel);
                        try {
                            if (Files.exists(target)) {
                                throw new TransformException("collision while copying source roots: '" + rel
                                        + "' already exists in " + dst + "; two src/*/"
                                        + src.getParent().getFileName() + " roots produce the same output path");
                            }
                            Files.createDirectories(target.getParent());
                            Files.copy(p, target);
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

    private static String toSlash(Path path) {
        return path.toString().replace('\\', '/');
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
     * Adds the compatibility layer dependencies to the output build file
     * ({@code build.gradle} — Loom's Groovy template), prefixed with
     * {@link #LAYER_COMMENT}. Idempotent: no-op when all dependency lines
     * are already present.
     *
     * <p>Two dependencies are wired, mirroring the two-layer split:
     * <ul>
     *   <li>{@code com.github.ftinclarp:MTN-forge-layer:<version>} — the FML
     *       /{@code cpw.mods.fml.*} shim</li>
     *   <li>{@code com.github.ftinclarp:MTN-minecraft-layer:<version>} — the
     *       {@code net.minecraft.*} API stand-ins</li>
     * </ul>
     * When {@code useLocalLayer}, both come from the local Maven repo
     * ({@code mavenLocal()}) using the configured local versions — a fast
     * dev loop with {@code publishToMavenLocal}, avoiding a JitPack rebuild
     * wait. Otherwise (default) JitPack is used with the remote release
     * versions.
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
            String forgeCoord = "com.github.ftinclarp:MTN-forge-layer:"
                    + (local ? config.effectiveLocalLayerVersion() : config.effectiveRemoteLayerVersion());
            String minecraftCoord = "com.github.ftinclarp:MTN-minecraft-layer:"
                    + (local ? config.effectiveLocalMinecraftLayerVersion()
                             : config.effectiveRemoteMinecraftLayerVersion());
            boolean hasForge = text.contains(forgeCoord);
            boolean hasMinecraft = text.contains(minecraftCoord);
            if (hasForge && hasMinecraft) {
                return; // already wired
            }

            boolean needsRepoLifecycle = false;
            String repoBlock;
            if (local) {
                repoBlock = LAYER_COMMENT + "\n\tmavenLocal()";
                needsRepoLifecycle = !text.contains("mavenLocal()");
            } else {
                repoBlock = LAYER_COMMENT + "\n\tmaven { url = \"https://jitpack.io\" }";
                needsRepoLifecycle = !text.contains(LAYER_JITPACK_URL);
            }

            String warped = text;
            if (needsRepoLifecycle) {
                warped = insertAfterBlockLine(warped, "repositories {", repoBlock);
            }
            if (!hasForge) {
                warped = insertAfterBlockLine(warped, "dependencies {",
                        LAYER_COMMENT + "\n\tmodImplementation \"" + forgeCoord + "\"");
            }
            if (!hasMinecraft) {
                warped = insertAfterBlockLine(warped, "dependencies {",
                        LAYER_COMMENT + "\n\tmodImplementation \"" + minecraftCoord + "\"");
            }
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
        // The layer mod (MTN-forge-layer) already declares "main":
        // ["mtn.forge_layer.FabricEntry"] in its own fabric.mod.json, so
        // Fabric calls FabricEntry.onInitialize once — from the layer mod.
        // getEntrypointContainers() enumerates all mods, so the ported mod's
        // mtn:forge-mod-class entrypoint is visible regardless of which mod
        // declares the main entrypoint. Emitting "main" here would double
        // dispatch the Forge lifecycle.
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
     * Validates that {@code inputDir} looks like a mod and returns its
     * absolute, normalized path. Per the project rule, {@code
     * transmudation_input} always points at the mod directory directly — never
     * a container of mods. A mod-looking directory has {@code src/main/java/}
     * or {@code src/main/resources/mcmod.info} (more generally, any
     * {@code src}/subdir/{@code java}). Otherwise it is left as-is for the
     * caller and this fails with a clear message.
     */
    static Path resolveModRoot(Path inputDir) {
        Path abs = inputDir.toAbsolutePath().normalize();
        if (looksLikeMod(abs)) {
            return abs;
        }
        throw new TransformException("transmudation_input does not look like a mod: " + abs
                + " (expected src/ and mcmod.info)");
    }

    private static boolean looksLikeMod(Path root) {
        try (var stream = Files.list(root.resolve("src"))) {
            for (Path sub : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                Path name = sub.getFileName();
                if (name == null) {
                    continue;
                }
                // any src/<name>/java directory counts, e.g. src/main/java,
                // src/api/java, src/core/java — so does src/main/resources/mcmod.info.
                if (Files.isDirectory(sub.resolve("java"))) {
                    return true;
                }
            }
        } catch (IOException e) {
            // fall through to the explicit src/main check below
        }
        return hasMcmodInfo(root) || Files.isDirectory(root.resolve("src/main/java"));
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
