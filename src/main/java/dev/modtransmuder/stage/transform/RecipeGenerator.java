package dev.modtransmuder.stage.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code stage-transform} recipe generation. Scans the rewritten source
 * ({@code outJava}) for two kinds of registrations and emits Fabric JSON:
 * <ol>
 *   <li>{@code OreDictionary.registerOre(name, item)} literals → item tag
 *       JSONs under {@code data/<namespace>/tags/items/...} using the
 *       ore-name → {@code c:} tag mapping table;</li>
 *   <li>{@code GameRegistry.addRecipe(new ShapedOreRecipe(result, shape…,
 *       'k', ingredient…))} fully-literal constructions → shaped recipe
 *       JSONs under {@code data/<modid>/recipes/}.</li>
 * </ol>
 * Dynamic registrations (the argument is a variable/call, e.g. IronChest's
 * helper-loop {@code addRecipe(new ShapedOreRecipe(is, parts))}) are NOT
 * heuristically parsed — they are counted and logged as skipped, per the
 * module contract: reliable static extraction only.
 */
final class RecipeGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, String> ORE_TAG = new LinkedHashMap<>();

    private static final Map<String, String> VANILLA_IDS = new LinkedHashMap<>();

    static {
        ORE_TAG.put("ingotIron", "c:ingots/iron");
        ORE_TAG.put("ingotGold", "c:ingots/gold");
        ORE_TAG.put("ingotCopper", "c:ingots/copper");
        ORE_TAG.put("ingotSilver", "c:ingots/silver");
        ORE_TAG.put("gemDiamond", "c:gems/diamond");
        ORE_TAG.put("blockGlass", "c:glass_blocks");
        ORE_TAG.put("chestWood", "c:chests/wooden");
        ORE_TAG.put("plankWood", "c:planks");
        ORE_TAG.put("ingotRefinedIron", "c:ingots/iron");
        ORE_TAG.put("obsidian", "c:obsidian");
        ORE_TAG.put("dirt", "c:dirt");

        VANILLA_IDS.put("Blocks.chest", "minecraft:chest");
        VANILLA_IDS.put("Blocks.glass", "minecraft:glass");
        VANILLA_IDS.put("Blocks.obsidian", "minecraft:obsidian");
        VANILLA_IDS.put("Blocks.dirt", "minecraft:dirt");
        VANILLA_IDS.put("Blocks.stone", "minecraft:stone");
        VANILLA_IDS.put("Blocks.wood", "minecraft:oak_planks");
        VANILLA_IDS.put("Items.written_book", "minecraft:written_book");
    }

    record RecipeStats(int recipes, int tags, int warnings) {
    }

    record RecipeStatsFile(int recipes, int tags, int warnings, List<String> dynamic) {
    }

    private RecipeGenerator() {
    }

    static RecipeStats generate(Path outJava, Path outResources, String modid) {
        int recipes = 0;
        int tags = 0;
        int warnings = 0;
        List<String> dynamic = new ArrayList<>();
        if (outJava != null && Files.isDirectory(outJava)) {
            try (var walk = Files.walk(outJava)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))::iterator) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    RecipeStatsFile s = scan(text, p, outResources, modid);
                    recipes += s.recipes();
                    tags += s.tags();
                    warnings += s.warnings();
                    dynamic.addAll(s.dynamic());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String w : dynamic) {
            System.out.println("[MTN-IGNORE] dynamic recipe registration: " + w);
        }
        return new RecipeStats(recipes, tags, warnings);
    }

    private static RecipeStatsFile scan(String text, Path file, Path outResources, String modid) {
        List<String> dynamic = new ArrayList<>();
        int recipes = 0;
        int tags = 0;
        int warnings = 0;

        // --- shaped recipes: fully-literal construction ---
        Pattern literal = Pattern.compile(
                "GameRegistry\\.addRecipe\\s*\\(\\s*new\\s+ShapedOreRecipe\\s*\\(([\\s\\S]*?)\\)\\s*\\)");
        Matcher lr = literal.matcher(text);
        List<int[]> literalSpans = new ArrayList<>();
        while (lr.find()) {
            literalSpans.add(new int[]{lr.start(), lr.end()});
            RecipeData data = parseShapedRecipe(lr.group(1));
            if (data == null) {
                dynamic.add(file.getFileName() + ":" + lineOf(text, lr.start())
                        + " addRecipe(new ShapedOreRecipe(" + lr.group(1) + ")) not statically parseable — skipped");
                continue;
            }
            String resultId = resolveItemRef(data.resultRef, modid);
            if (resultId == null) {
                warnings++;
                System.out.println("[MTN-WARN] cannot resolve recipe result for "
                        + file.getFileName() + ":" + lineOf(text, lr.start())
                        + " — result ref: " + data.resultRef);
                continue;
            }
            try {
                writeShapedRecipeJSON(outResources, modid, data, resultId, recipes);
                recipes++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // --- non-literal addRecipe arguments → dynamic ---
        Pattern dyn = Pattern.compile("GameRegistry\\.addRecipe\\s*\\(([^;]*?)\\)");
        Matcher d = dyn.matcher(text);
        while (d.find()) {
            String arg = d.group(1).trim();
            if (arg.isBlank() || arg.startsWith("new ShapedOreRecipe")
                    || arg.startsWith("new ShapedRecipes")) {
                continue;
            }
            boolean covered = false;
            for (int[] span : literalSpans) {
                if (span[0] <= d.start() && d.start() <= span[1]) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                dynamic.add(file.getFileName() + ":" + lineOf(text, d.start())
                        + " addRecipe(" + arg + ") — dynamic, skipped");
            }
        }

        // --- oredict tag registrations ---
        Pattern ore = Pattern.compile(
                "OreDictionary\\.registerOre\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*([^;]+?)\\)");
        Matcher m = ore.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String ref = m.group(2).trim();
            String tagKey = ORE_TAG.get(name);
            if (tagKey == null) {
                warnings++;
                System.out.println("[MTN-WARN] unknown oredict name \"" + name
                        + "\" — no tag mapping (" + file.getFileName() + ":" + lineOf(text, m.start()) + ")");
                continue;
            }
            String itemId = resolveItemRef(ref, null);
            if (itemId == null) {
                warnings++;
                System.out.println("[MTN-WARN] cannot resolve oredict value for \"" + name
                        + "\" from " + file.getFileName() + ":" + lineOf(text, m.start())
                        + " — expression: " + ref);
                continue;
            }
            String ns = tagKey.startsWith("c:") ? "c" : tagKey.substring(0, tagKey.indexOf(':'));
            String path = tagKey.substring(tagKey.indexOf(':') + 1);
            try {
                writeTagJSON(outResources, ns, path, itemId);
                tags++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new RecipeStatsFile(recipes, tags, warnings, dynamic);
    }

    private static int lineOf(String text, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ---------------------- shaped recipe model ----------------------

    private static final class RecipeData {
        List<String> rows = new ArrayList<>();
        Map<Character, String> keyToRef = new LinkedHashMap<>();
        String resultRef;
    }

    private static RecipeData parseShapedRecipe(String args) {
        String[] tokens = splitTopLevel(args);
        if (tokens.length < 3) {
            return null;
        }
        RecipeData data = new RecipeData();
        int i = 0;
        if (!tokens[i].startsWith("new ItemStack(")) {
            return null;
        }
        data.resultRef = tokens[i];
        i++;
        while (i < tokens.length && isStringLiteral(tokens[i])) {
            data.rows.add(stripQuotes(tokens[i]));
            i++;
        }
        if (data.rows.isEmpty() || data.rows.size() > 3) {
            return null;
        }
        while (i + 1 < tokens.length) {
            String keyTok = tokens[i].trim();
            if (keyTok.length() != 3 || keyTok.charAt(0) != '\''
                    || keyTok.charAt(2) != '\'') {
                return null;
            }
            char key = keyTok.charAt(1);
            String ref = tokens[i + 1].trim();
            if (key != ' ') {
                data.keyToRef.put(key, ref);
            }
            i += 2;
        }
        if (data.keyToRef.isEmpty()) {
            return null;
        }
        return data;
    }

    private static String resolveItemRef(String ref, String modid) {
        String r = ref.trim();
        String inner = null;
        Matcher comma = Pattern.compile("^new\\s+ItemStack\\s*\\(\\s*([^,)]+)\\s*,.*\\)").matcher(r);
        if (comma.find()) {
            inner = comma.group(1).trim();
        } else {
            Matcher plain = Pattern.compile("^new\\s+ItemStack\\s*\\(([^,)]*)\\)").matcher(r);
            if (plain.find()) {
                inner = plain.group(1).trim();
            } else if (VANILLA_IDS.containsKey(r)) {
                return VANILLA_IDS.get(r);
            }
        }
        if (inner == null) {
            return null;
        }
        String known = VANILLA_IDS.get(inner);
        if (known != null) {
            return known;
        }
        if (inner.startsWith("Blocks.") || inner.startsWith("Item.")
                || inner.startsWith("Items.")) {
            if (modid != null) {
                return modid + ":" + inner.substring(inner.indexOf('.') + 1).toLowerCase();
            }
            return null;
        }
        return null;
    }

    /**
     * Emit a {@code crafting_shaped} JSON recipe. Ore-name ingredients (string
     * literals) resolve to tags; {@code new ItemStack(...)} ingredients resolve
     * to item ids.
     */
    private static void writeShapedRecipeJSON(Path outResources, String modid,
                                              RecipeData data, String resultId,
                                              int index) throws IOException {
        int width = data.rows.stream().mapToInt(String::length).max().orElse(1);
        List<String> padded = new ArrayList<>();
        for (String row : data.rows) {
            StringBuilder sb = new StringBuilder(row);
            while (sb.length() < width) {
                sb.append(' ');
            }
            padded.add(sb.toString());
        }

        ObjectNode node = JSON.createObjectNode();
        node.put("type", "minecraft:crafting_shaped");
        ArrayNode pattern = node.putArray("pattern");
        for (String row : padded) {
            pattern.add(row);
        }
        ObjectNode key = node.putObject("key");
        for (Map.Entry<Character, String> e : data.keyToRef.entrySet()) {
            String ref = e.getValue().trim();
            ObjectNode cell = key.putObject(String.valueOf(e.getKey()));
            if (isStringLiteral(ref)) {
                String oreName = stripQuotes(ref);
                String tagKey = ORE_TAG.get(oreName);
                if (tagKey != null) {
                    cell.put("tag", tagKey);
                } else {
                    cell.put("item", modid + ":" + oreName.toLowerCase());
                }
            } else {
                String itemId = resolveItemRef(ref, modid);
                if (itemId != null) {
                    cell.put("item", itemId);
                } else {
                    cell.put("item", modid + ":unknown");
                }
            }
        }
        ObjectNode result = node.putObject("result");
        result.put("item", resultId);
        result.put("count", 1);

        String base = resultId.substring(resultId.indexOf(':') + 1);
        String name = base + (index == 0 ? "" : "_" + index);
        Path target = outResources.resolve("data/" + modid + "/recipes/" + name + ".json");
        Files.createDirectories(target.getParent());
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        Files.writeString(target, text + "\n", StandardCharsets.UTF_8);
        System.out.println("[MTN-RECIPE] recipe " + target.getFileName());
    }

    private static boolean isStringLiteral(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"");
    }

    private static String stripQuotes(String s) {
        return s.trim().substring(1, s.trim().length() - 1);
    }

    private static String[] splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out.toArray(new String[0]);
    }

    private static void writeTagJSON(Path outResources, String ns, String path,
                                     String itemId) throws IOException {
        ObjectNode tag = JSON.createObjectNode();
        tag.put("replace", false);
        ArrayNode values = tag.putArray("values");
        values.add(itemId);
        Path target = outResources.resolve("data/" + ns + "/tags/items/" + path + ".json");
        Files.createDirectories(target.getParent());
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tag);
        Files.writeString(target, text + "\n", StandardCharsets.UTF_8);
        System.out.println("[MTN-RECIPE] tag " + ns + ":" + path + " += " + itemId);
    }

    /** Convenience for the transform message. */
    static String summary(RecipeStats s) {
        return "recipes: " + s.recipes() + " json, " + s.tags() + " tags, "
                + s.warnings() + " warnings";
    }
}
