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
 * Parses a Forge 1.7.10 {@code mcmod.info} file into a {@link ForgeModInfo}.
 *
 * <p>Accepts both the modern schema — a JSON object with a {@code modList}
 * array whose first element carries the mod record — and the legacy Forge
 * 1.7.10 schema — a single-element JSON array whose first element is the mod
 * record. Field mapping reads {@code modid}, {@code name}, {@code version},
 * {@code mcversion}, {@code description}, and {@code authorList} (falling back
 * to {@code authors} when {@code authorList} is absent). Missing fields
 * default to an empty string / empty list rather than failing.
 *
 * <p>Throws {@link TransformException} when the file is missing, unreadable,
 * or has no parseable record — the stage never silently continues.
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
        JsonNode record = firstRecord(root, mcmodInfo);
        return toModel(record, mcmodInfo);
    }

    /**
     * Extracts the mod record from either accepted top-level shape:
     * object-with-{@code modList} (first element) or legacy array (first
     * element). Fails with a clear message on any other shape.
     */
    private static JsonNode firstRecord(JsonNode root, Path mcmodInfo) {
        if (root == null) {
            throw new TransformException("mcmod.info must be a JSON object: " + mcmodInfo);
        }
        if (root.isObject()) {
            JsonNode modList = root.get("modList");
            if (modList == null || !modList.isArray() || modList.isEmpty()) {
                throw new TransformException("mcmod.info has no non-empty 'modList' array: " + mcmodInfo);
            }
            JsonNode first = modList.get(0);
            if (first == null || !first.isObject()) {
                throw new TransformException("mcmod.info modList[0] must be an object: " + mcmodInfo);
            }
            return first;
        }
        if (root.isArray()) {
            if (root.isEmpty()) {
                throw new TransformException("mcmod.info has an empty mod array: " + mcmodInfo);
            }
            JsonNode first = root.get(0);
            if (first == null || !first.isObject()) {
                throw new TransformException("mcmod.info array[0] must be an object: " + mcmodInfo);
            }
            return first;
        }
        throw new TransformException("mcmod.info must be a JSON object: " + mcmodInfo);
    }

    private static ForgeModInfo toModel(JsonNode node, Path source) {
        String modid = optionalText(node, "modid");
        String name = optionalText(node, "name");
        String version = optionalText(node, "version");
        String description = optionalText(node, "description");
        List<String> authors = authorList(node);
        return new ForgeModInfo(modid, name, version, description, authors);
    }

    private static List<String> authorList(JsonNode node) {
        // authorList (newer schema) takes precedence; legacy mods use "authors".
        JsonNode authorList = node.get("authorList");
        if (authorList == null || !authorList.isArray()) {
            authorList = node.get("authors");
        }
        List<String> authors = new ArrayList<>();
        if (authorList != null && authorList.isArray()) {
            for (JsonNode a : authorList) {
                if (a.isTextual() && !a.asText().isBlank()) {
                    authors.add(a.asText());
                }
            }
        }
        return authors;
    }

    private static String optionalText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return (v != null && v.isTextual()) ? v.asText() : "";
    }
}
