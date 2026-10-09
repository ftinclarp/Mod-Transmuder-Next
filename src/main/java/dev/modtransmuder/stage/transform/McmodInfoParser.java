package dev.modtransmuder.stage.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.modtransmuder.error.TransformException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Forge 1.7.10 {@code mcmod.info} file (JSON with a {@code modList}
 * array whose first element carries {@code modid}, {@code name},
 * {@code version}, {@code description}, {@code authorList}).
 *
 * <p>Throws {@link TransformException} when the file is missing, unreadable,
 * or malformed — the stage never silently continues.
 */
final class McmodInfoParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private McmodInfoParser() {
    }

    static ForgeModInfo parse(Path mcmodInfo) {
        if (mcmodInfo == null || !Files.isRegularFile(mcmodInfo)) {
            throw new TransformException("mcmod.info not found: " + mcmodInfo);
        }
        JsonNode root;
        try {
            root = JSON.readTree(Files.readAllBytes(mcmodInfo));
        } catch (IOException e) {
            throw new TransformException("cannot read mcmod.info " + mcmodInfo + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new TransformException("mcmod.info must be a JSON object: " + mcmodInfo);
        }
        JsonNode modList = root.get("modList");
        if (modList == null || !modList.isArray() || modList.isEmpty()) {
            throw new TransformException("mcmod.info has no non-empty 'modList' array: " + mcmodInfo);
        }
        JsonNode first = modList.get(0);
        if (first == null || !first.isObject()) {
            throw new TransformException("mcmod.info modList[0] must be an object: " + mcmodInfo);
        }
        return toModel(first, mcmodInfo);
    }

    private static ForgeModInfo toModel(JsonNode node, Path source) {
        String modid = requiredText(node, "modid", source);
        String name = requiredText(node, "name", source);
        String version = requiredText(node, "version", source);
        String description = optionalText(node, "description");
        List<String> authors = new ArrayList<>();
        JsonNode authorList = node.get("authorList");
        if (authorList != null && authorList.isArray()) {
            for (JsonNode a : authorList) {
                if (a.isTextual() && !a.asText().isBlank()) {
                    authors.add(a.asText());
                }
            }
        }
        return new ForgeModInfo(modid, name, version, description, authors);
    }

    private static String requiredText(JsonNode node, String key, Path source) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new TransformException("mcmod.info modList[0] is missing a non-blank '"
                    + key + "' field: " + source);
        }
        return v.asText();
    }

    private static String optionalText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return (v != null && v.isTextual()) ? v.asText() : "";
    }
}
