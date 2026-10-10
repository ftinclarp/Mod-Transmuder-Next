package dev.modtransmuder.stage.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    private static final Pattern REGISTER_BLOCK = Pattern.compile(
            "GameRegistry\\.registerBlock\\([^,]+,\\s*\"([^\"]+)\"\\)");
    private static final Pattern REGISTER_ITEM = Pattern.compile(
            "GameRegistry\\.registerItem\\([^,]+,\\s*\"([^\"]+)\"\\)");

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
        Set<String> blockNames = discoverNames(outJava, REGISTER_BLOCK);
        Set<String> itemNames = discoverNames(outJava, REGISTER_ITEM);

        copyResourcesWithRename(modRoot, outResources, modid);

        int models = writeModels(outResources, modid, blockNames, itemNames);
        int blockstates = writeBlockstates(outResources, modid, blockNames);
        int langKeys = convertLang(modRoot, outResources, modid, blockNames, itemNames);
        int fallback = ensureTextures(outResources, modid, blockNames, itemNames);

        return new ResourceStats(fallback, models, blockstates, langKeys);
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

    // ---------------- 2. resource copy + rename ----------------

    private static void copyResourcesWithRename(Path modRoot, Path outResources, String modid) {
        Path inResources = modRoot.resolve("src/main/resources");
        if (!Files.isDirectory(inResources)) {
            return;
        }
        String blocksLegacy = "assets/" + modid + "/textures/blocks/";
        String itemsLegacy = "assets/" + modid + "/textures/items/";
        try (var walk = Files.walk(inResources)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String rel = toSlash(inResources.relativize(p));
                // mcmod.info consumed elsewhere; lang converted (step 4), not copied verbatim.
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
                Path target = outResources.resolve(targetRel);
                try {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String toSlash(Path path) {
        return path.toString().replace('\\', '/');
    }

    // ---------------- 3. JSON models + blockstates ----------------

    private static int writeModels(Path outResources, String modid,
                                   Set<String> blockNames, Set<String> itemNames) {
        int written = 0;
        try {
            for (String name : blockNames) {
                // block/<name>.json — cube_all using the block texture
                ObjectNode blockModel = JSON.createObjectNode();
                blockModel.put("parent", "block/cube_all");
                ObjectNode blockTex = blockModel.putObject("textures");
                blockTex.put("all", modid + ":block/" + name);
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
