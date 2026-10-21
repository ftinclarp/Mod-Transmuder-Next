package dev.modtransmuder.stage.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.modtransmuder.error.TransformException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code stage-transform} resource generation. After the Java copy + import
 * rewrite, this sub-step:
 * <ol>
 *   <li>discovers registered block/item names from {@code GameRegistry}
 *       calls in the copied sources;</li>
 *   <li>copies input resources, renaming legacy {@code textures/blocks|items}
 *       folders to the Fabric singular layout and converting the lang
 *       file;</li>
 *   <li>generates JSON models + blockstates for every registered name;</li>
 *   <li>converts {@code en_US.lang} to {@code en_us.json};</li>
 *   <li>writes a solid-color fallback PNG for any registered block/item
 *       without a source texture.</li>
 * </ol>
 *
 * <p>Pure file/resource generation — no game code, no layer involvement, so
 * the Yarn-vs-Mojang mapping question does not apply here.
 */
final class ResourceGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern REGISTER_BLOCK_2 = Pattern.compile(
            "GameRegistry\\.registerBlock\\([^,\\)]+,\\s*\"([^\"]+)\"\\)");
    private static final Pattern REGISTER_BLOCK_3 = Pattern.compile(
            "GameRegistry\\.registerBlock\\([^,\\)]+,\\s*[^\\)]+,\\s*\"([^\"]+)\"\\)");
    private static final Pattern REGISTER_ITEM = Pattern.compile(
            "GameRegistry\\.registerItem\\([^,\\)]+,\\s*\"([^\"]+)\"\\)");
    private static final Pattern REGISTER_ITEM_VAR = Pattern.compile(
            "GameRegistry\\.registerItem\\([^,\\)]+,\\s*\\w+\\)");
    private static final Pattern REGISTER_TEXTURE_ITEM = Pattern.compile(
            "textures\\s*=\\s*\"([^\"]+)\"");

    /** Direct TESR bindings, e.g. {@code bindTileEntitySpecialRenderer(TileEntityIronChest.class, ...)}. */
    private static final Pattern BIND_TESR = Pattern.compile(
            "bindTileEntitySpecialRenderer\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\.class");
    /**
     * Tile-entity class literals of the form {@code TileEntityX.class}. Covers
     * data-driven bindings where the bound class comes from an enum/field
     * (1.7.10 chest mods do {@code bindTileEntitySpecialRenderer(typ.clazz, ...)}
     * with {@code typ.clazz = TileEntityIronChest.class}), which a direct
     * binding regex can't resolve.
     */
    private static final Pattern TILE_CLASS_LITERAL = Pattern.compile(
            "\\b(TileEntity[A-Za-z0-9_]*)\\.class");
    /** A block creates per-instance tile entities via one of these methods. */
    private static final Pattern CREATE_TILE = Pattern.compile(
            "createTileEntity|createNewTileEntity");

    /** "item.old.name" dep key forms. */
    private static final Pattern OLD_ITEM_KEY = Pattern.compile("^item\\.([^.]+)\\.name$");
    private static final Pattern OLD_TILE_KEY = Pattern.compile("^tile\\.([^.]+)\\.name$");

    private ResourceGenerator() {
    }

    /** Summary of what the resource step produced. */
    record ResourceStats(
            int textures,       // texture files present after copy+fallback (fallback count)
            int models,         // generated model json files
            int blockstates,    // generated blockstate json files
            int langKeys) {     // lang keys written
    }

    /**
     * Runs the whole resource generation sub-step.
     *
     * @param modRoot      the discovered Forge mod root (has {@code src/main/resources})
     * @param outJava      output tree {@code src/main/java}
     * @param outResources output tree {@code src/main/resources}
     * @param modid        the mod's id from mcmod.info
     */
    static ResourceStats generate(Path modRoot, Path outJava, Path outResources, String modid) {
        // Asset paths/namespaces must be lowercase for Fabric. Use the
        // lowercased id everywhere.
        String assetId = modid.toLowerCase(java.util.Locale.ROOT);
        Set<String> blockNames = discoverNames(outJava, REGISTER_BLOCK_2);
        blockNames.addAll(discoverNames(outJava, REGISTER_BLOCK_3));
        Set<String> itemNames = discoverNames(outJava, REGISTER_ITEM);
        // registerItem(item, variableName) forms can't be captured by regex, so
        // also take names from the item texture folder: one model per texture.
        itemNames.addAll(discoverItemTextures(modRoot, assetId, "items"));
        itemNames.addAll(discoverItemTextures(modRoot, assetId, "item"));
        lowerAll(blockNames);
        lowerAll(itemNames);

        copyResourcesWithRename(modRoot, outResources, assetId);

        // Blocks whose geometry is drawn entirely by a tile-entity special
        // renderer (TESR) must NOT also render a static model, or the static
        // cube_all box z-fights with / occludes the TESR geometry. Emit an
        // empty in-world model for those (item icon still visible).
        Set<String> tesrBlocks = discoverTesrBlocks(outJava, blockNames);

        int models = writeModels(outResources, assetId, blockNames, itemNames, tesrBlocks);
        int blockstates = writeBlockstates(outResources, assetId, blockNames);
        int langKeys = convertLang(modRoot, outResources, assetId, blockNames, itemNames);
        int fallback = ensureTextures(outResources, assetId, blockNames, itemNames);

        return new ResourceStats(fallback, models, blockstates, langKeys);
    }

    private static void lowerAll(Set<String> names) {
        Set<String> lower = new LinkedHashSet<>();
        for (String n : names) {
            lower.add(n.toLowerCase(java.util.Locale.ROOT));
        }
        names.clear();
        names.addAll(lower);
    }

    /** Item ids implied by the mod's item-texture folder (assumes one texture
     * per item id, which is the common 1.7.10 convention). */
    private static Set<String> discoverItemTextures(Path modRoot, String assetId, String folder) {
        Set<String> names = new LinkedHashSet<>();
        java.nio.file.Path dir = modRoot.resolve(
                "src/main/resources/assets/" + assetId + "/textures/" + folder);
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(
                            java.util.Locale.ROOT).endsWith(".png"))
                    .forEach(p -> {
                        String n = p.getFileName().toString();
                        names.add(n.substring(0, n.length() - 4)
                                .toLowerCase(java.util.Locale.ROOT));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    // ---------------- 1. discover registered names ----------------

    private static Set<String> discoverNames(Path outJava, Pattern call) {
        Set<String> names = new LinkedHashSet<>();
        if (outJava == null || !Files.isDirectory(outJava)) {
            return names;
        }
        try (var walk = Files.walk(outJava)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            Matcher m = call.matcher(text);
                            while (m.find()) {
                                names.add(m.group(1));
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    // ---------------- 1b. TESR block detection ----------------

    /**
     * Blocks whose in-world rendering is handled by a tile-entity special
     * renderer (TESR). These are detected statically in the transformed
     * sources: a block flags as TESR when it overrides {@code create(New)
     * TileEntity} and its own source references a tile class the mod binds a
     * special renderer to. Such blocks get an empty baked model so the static
     * block doesn't overlap or z-fight with the TESR geometry.
     */
    private static Set<String> discoverTesrBlocks(Path outJava, Set<String> blockNames) {
        if (outJava == null || !Files.isDirectory(outJava) || blockNames.isEmpty()) {
            return java.util.Set.of();
        }
        Set<String> bound = resolveBoundTileClasses(outJava);
        if (bound.isEmpty()) {
            return java.util.Set.of();
        }
        Set<String> tesr = new LinkedHashSet<>();
        for (String name : blockNames) {
            Path blockSrc = findBlockSource(outJava, name);
            if (blockSrc == null) {
                continue;
            }
            String text;
            try {
                text = Files.readString(blockSrc, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!CREATE_TILE.matcher(text).find()) {
                continue;
            }
            for (String b : bound) {
                if (Pattern.compile("\\b" + Pattern.quote(b) + "\\b").matcher(text).find()) {
                    tesr.add(name);
                    break;
                }
            }
        }
        return tesr;
    }

    /**
     * The set of tile-entity classes with a bound special renderer. Direct
     * {@code bindTileEntitySpecialRenderer(X.class, ...)} calls are resolved
     * literally; when the binding is data-driven ({@code typ.clazz}, an enum
     * field), every {@code TileEntity*.class} literal in the sources is used.
     */
    private static Set<String> resolveBoundTileClasses(Path outJava) {
        Set<String> bound = new LinkedHashSet<>();
        List<Path> javas = new java.util.ArrayList<>();
        try (var walk = Files.walk(outJava)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(javas::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path p : javas) {
            try {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = BIND_TESR.matcher(s);
                while (m.find()) {
                    bound.add(m.group(1));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (!bound.isEmpty()) {
            return bound;
        }
        for (Path p : javas) {
            try {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = TILE_CLASS_LITERAL.matcher(s);
                while (m.find()) {
                    String n = m.group(1);
                    if (!bound.contains(n) && !n.equals("TileEntity")) {
                        bound.add(n);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return bound;
    }

    /** Finds a block source file {@code <name>.java} anywhere in {@code outJava},
     * matching case-insensitively (registry names often differ in casing from
     * the class name, e.g. {@code BlockIronChest}). */
    private static Path findBlockSource(Path outJava, String name) {
        String wanted = name.toLowerCase(java.util.Locale.ROOT) + ".java";
        try (var walk = Files.walk(outJava)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).equals(wanted))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------- 2. resource copy + rename ----------------

    /**
     * Copies every {@code src/<name>/resources} root into {@code outResources},
     * merging them. Legacy {@code textures/blocks|items} folders are renamed to
     * the Fabric singular layout. Fails loudly when two roots produce the same
     * output path.
     */
    private static void copyResourcesWithRename(Path modRoot, Path outResources, String modid) {
        Path src = modRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return;
        }
        String blocksLegacy = "assets/" + modid + "/textures/blocks/";
        String itemsLegacy = "assets/" + modid + "/textures/items/";
        for (Path root : resourceRoots(modRoot)) {
            String name = resourceRootName(root);
            for (ResourceFile file : copyableResourceFiles(root, modid, blocksLegacy, itemsLegacy)) {
                Path target = outResources.resolve(file.targetRel());
                try {
                    if (Files.exists(target)) {
                        throw new TransformException(
                                "resource collision between src/" + name + "/resources and another source root: "
                                        + file.targetRel());
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(file.source(), target);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /** How many resource files each {@code src/<name>} root contributes, so the transform stage can log it. */
    static java.util.Map<String, Integer> resourceRootsWithCounts(Path modRoot, String modid) {
        java.util.Map<String, Integer> perRoot = new java.util.LinkedHashMap<>();
        Path src = modRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return perRoot;
        }
        String blocksLegacy = "assets/" + modid + "/textures/blocks/";
        String itemsLegacy = "assets/" + modid + "/textures/items/";
        for (Path root : resourceRoots(modRoot)) {
            perRoot.put(resourceRootName(root),
                    copyableResourceFiles(root, modid, blocksLegacy, itemsLegacy).size());
        }
        return perRoot;
    }

    /** Every {@code src/<name>/resources} root under {@code modRoot}, sorted by name. */
    private static List<Path> resourceRoots(Path modRoot) {
        List<Path> roots = new java.util.ArrayList<>();
        Path src = modRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return roots;
        }
        try (var stream = Files.list(src)) {
            stream.filter(Files::isDirectory).sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(sub -> {
                        Path resDir = sub.resolve("resources");
                        if (Files.isDirectory(resDir)) {
                            roots.add(resDir);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return roots;
    }

    private static String resourceRootName(Path resDir) {
        return resDir.getParent() == null || resDir.getParent().getFileName() == null
                ? "main" : resDir.getParent().getFileName().toString();
    }

    /** An input resource file mapped to its output-relative path (after rename). */
    private record ResourceFile(Path source, String targetRel) {
    }

    /**
     * Lists every copyable file under one resource root as {@code (source,
     * targetRel)} pairs: all regular files except {@code mcmod.info} and
     * {@code en_US.lang} (both handled separately), with the legacy-folder
     * rename applied to the target relative path.
     */
    private static List<ResourceFile> copyableResourceFiles(Path root, String modid,
                                                            String blocksLegacy, String itemsLegacy) {
        List<ResourceFile> results = new java.util.ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String rel = toSlash(root.relativize(p));
                if (p.getFileName().toString().equals("mcmod.info")
                        || rel.contains("/lang/en_US.lang")) {
                    return;
                }
                String targetRel = rel;
                if (rel.contains(blocksLegacy)) {
                    targetRel = rel.replace(blocksLegacy, "assets/" + modid + "/textures/block/");
                } else if (rel.contains(itemsLegacy)) {
                    targetRel = rel.replace(itemsLegacy, "assets/" + modid + "/textures/item/");
                }
                // Fabric's ModNioResourcePack rejects uppercase paths, so force
                // every copied resource filename to lowercase on disk.
                int sep = targetRel.lastIndexOf('/');
                String base = sep >= 0 ? targetRel.substring(sep + 1) : targetRel;
                String lowerBase = base.toLowerCase(java.util.Locale.ROOT);
                if (!lowerBase.equals(base)) {
                    targetRel = (sep >= 0 ? targetRel.substring(0, sep + 1) : "") + lowerBase;
                }
                results.add(new ResourceFile(p, targetRel));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return results;
    }

    private static String toSlash(Path path) {
        return path.toString().replace('\\', '/');
    }

    // ---------------- 3. JSON models + blockstates ----------------

    private static int writeModels(Path outResources, String modid,
                                   Set<String> blockNames, Set<String> itemNames,
                                   Set<String> tesrBlocks) {
        int written = 0;
        try {
            for (String name : blockNames) {
                String blockTexId = resolveBlockTexture(outResources, modid, name);
                if (tesrBlocks.contains(name)) {
                    // TESR-only block: empty in-world model (no elements) so the
                    // baked model doesn't overlap the TESR geometry. Keep the
                    // particle texture for break particles.
                    ObjectNode blockModel = JSON.createObjectNode();
                    ObjectNode blockTex = blockModel.putObject("textures");
                    blockTex.put("particle", blockTexId);
                    writeJson(outResources.resolve("assets/" + modid + "/models/block/" + name + ".json"), blockModel);
                    written++;

                    // item/<name>.json — keep a visible inventory icon (the
                    // empty block model would render nothing), using the same
                    // chest sheet so the hotbar/inventory item stays usable.
                    ObjectNode itemBlock = JSON.createObjectNode();
                    itemBlock.put("parent", "block/cube_all");
                    ObjectNode itemTex = itemBlock.putObject("textures");
                    itemTex.put("all", blockTexId);
                    writeJson(outResources.resolve("assets/" + modid + "/models/item/" + name + ".json"), itemBlock);
                    written++;
                    continue;
                }
                // block/<name>.json — cube_all using a resolved block texture
                ObjectNode blockModel = JSON.createObjectNode();
                blockModel.put("parent", "block/cube_all");
                ObjectNode blockTex = blockModel.putObject("textures");
                blockTex.put("all", blockTexId);
                writeJson(outResources.resolve("assets/" + modid + "/models/block/" + name + ".json"), blockModel);
                written++;

                // item/<name>.json — block item points at the block model
                ObjectNode itemBlock = JSON.createObjectNode();
                itemBlock.put("parent", modid + ":block/" + name);
                writeJson(outResources.resolve("assets/" + modid + "/models/item/" + name + ".json"), itemBlock);
                written++;
            }

            for (String name : itemNames) {
                ObjectNode itemModel = JSON.createObjectNode();
                itemModel.put("parent", "item/generated");
                ObjectNode itemTex = itemModel.putObject("textures");
                itemTex.put("layer0", modid + ":item/" + name);
                writeJson(outResources.resolve("assets/" + modid + "/models/item/" + name + ".json"), itemModel);
                written++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written;
    }

    /**
     * Chooses a texture id for a block model, preferring an existing texture
     * under {@code textures/block} or {@code textures/model} (the latter is how
     * 1.7.10 chest mods store their block sheet, e.g. {@code model/ironchest.png}
     * for block id {@code blockironchest}).
     *
     * The vanilla blocks atlas only indexes {@code textures/block} and
     * {@code textures/item} as source directories, so any chest sheet found
     * under {@code textures/model} is copied into {@code textures/block} before
     * the model references it — otherwise it would load as the missing-texture
     * (black/magenta) block.
     */
    private static String resolveBlockTexture(Path outResources, String modid, String name) {
        if (textureExists(outResources, modid, "block", name)) {
            return modid + ":block/" + name;
        }
        // "blockironchest" -> model/ironchest.png
        if (name.startsWith("block") && name.length() > 5
                && textureExists(outResources, modid, "model", name.substring(5))) {
            String stripped = name.substring(5);
            copyTextureTo(outResources, modid, "model", stripped);
            return modid + ":block/" + stripped;
        }
        if (textureExists(outResources, modid, "model", name)) {
            copyTextureTo(outResources, modid, "model", name);
            return modid + ":block/" + name;
        }
        return modid + ":block/" + name;
    }

    /** Copies {@code textures/model/<name>.png} into {@code textures/block/} if
     * not already present, so the blocks atlas can index it. */
    private static void copyTextureTo(Path outResources, String modid,
                                      String fromFolder, String name) {
        Path src = outResources.resolve("assets/" + modid + "/textures/"
                + fromFolder + "/" + name + ".png");
        Path dst = outResources.resolve("assets/" + modid
                + "/textures/block/" + name + ".png");
        if (!Files.isRegularFile(src) || Files.exists(dst)) {
            return;
        }
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean textureExists(Path outResources, String modid,
                                         String folder, String name) {
        return Files.isRegularFile(outResources.resolve(
                "assets/" + modid + "/textures/" + folder + "/" + name + ".png"))
                || Files.isRegularFile(outResources.resolve(
                "assets/" + modid + "/textures/" + folder + "/" + name + ".PNG"));
    }

    private static int writeBlockstates(Path outResources, String modid, Set<String> blockNames) {
        int written = 0;
        try {
            for (String name : blockNames) {
                ObjectNode bs = JSON.createObjectNode();
                ObjectNode variants = bs.putObject("variants");
                ObjectNode variant = variants.putObject("");
                variant.put("model", modid + ":block/" + name);
                writeJson(outResources.resolve("assets/" + modid + "/blockstates/" + name + ".json"), bs);
                written++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written;
    }

    private static void writeJson(Path target, ObjectNode node) throws IOException {
        Files.createDirectories(target.getParent());
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        Files.writeString(target, text + "\n", StandardCharsets.UTF_8);
    }

    // ---------------- 4. lang conversion ----------------

    private static int convertLang(Path modRoot, Path outResources, String modid,
                                   Set<String> blockNames, Set<String> itemNames) {
        Map<String, String> out = new LinkedHashMap<>();
        Path lang = modRoot.resolve("src/main/resources/assets/" + modid + "/lang/en_US.lang");
        if (Files.isRegularFile(lang)) {
            try {
                for (String line : Files.readAllLines(lang, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq < 1) {
                        continue;
                    }
                    String key = trimmed.substring(0, eq).trim();
                    String value = trimmed.substring(eq + 1).trim();
                    out.put(convertKey(key, modid, blockNames, itemNames), value);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // en_us.json always exists (possibly just {})
        ObjectNode root = JSON.createObjectNode();
        for (Map.Entry<String, String> e : out.entrySet()) {
            root.put(e.getKey(), e.getValue());
        }
        try {
            writeJson(outResources.resolve("assets/" + modid + "/lang/en_us.json"), root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.size();
    }

    /**
     * Maps a Forge 1.7.10 lang key to Fabric:
     * {@code item.<old>.name → item.<modid>.<registry-name>} and
     * {@code tile.<old>.name → block.<modid>.<registry-name>}.
     * When the old name cannot be mapped to a registered entry reliably, the
     * original key is kept.
     */
    private static String convertKey(String key, String modid,
                                     Set<String> blockNames, Set<String> itemNames) {
        Matcher item = OLD_ITEM_KEY.matcher(key);
        if (item.matches() && itemNames.contains(item.group(1))) {
            return "item." + modid + "." + item.group(1);
        }
        Matcher tile = OLD_TILE_KEY.matcher(key);
        if (tile.matches() && blockNames.contains(tile.group(1))) {
            return "block." + modid + "." + tile.group(1);
        }
        return key;
    }

    // ---------------- 5. fallback textures ----------------

    private static int ensureTextures(Path outResources, String modid,
                                      Set<String> blockNames, Set<String> itemNames) {
        int count = 0;
        count += ensureTextureFiles(outResources, "assets/" + modid + "/textures/block",
                blockNames, 128, 128, 128);
        count += ensureTextureFiles(outResources, "assets/" + modid + "/textures/item",
                itemNames, 192, 192, 192);
        return count;
    }

    private static int ensureTextureFiles(Path outResources, String relDir, Set<String> names,
                                          int r, int g, int b) {
        int count = 0;
        for (String name : names) {
            Path target = outResources.resolve(relDir + "/" + name + ".png");
            if (Files.isRegularFile(target)) {
                continue; // never overwrite an input texture
            }
            try {
                Files.createDirectories(target.getParent());
                writeSolidPng(target, r, g, b);
                count++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return count;
    }

    private static void writeSolidPng(Path target, int r, int g, int b) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        int rgb = (r << 16) | (g << 8) | b;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                img.setRGB(x, y, rgb);
            }
        }
        try (OutputStream os = Files.newOutputStream(target)) {
            ImageIO.write(img, "png", os);
        }
    }

    /** Convenience for the transform message. */
    static String summary(ResourceStats s) {
        return "resources: " + s.textures() + " textures, " + s.models() + " models, "
                + s.blockstates() + " blockstates, lang keys=" + s.langKeys();
    }
}
