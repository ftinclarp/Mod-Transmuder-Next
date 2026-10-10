package dev.modtransmuder.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.modtransmuder.error.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates the JSON config (ARCHITECTURE §4, §5). Contract:
 * {@code JSON → Config}, throws {@link ConfigException} on any problem.
 * Unknown keys are rejected; required keys with wrong types are rejected.
 */
public final class ConfigLoader {

    /** All accepted top-level keys. */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "template_zip_url",
            "transmudation_output",
            "transmudation_input",
            "rewrite_data",
            "stop_if_fail",
            "cache_dir",
            "timeout_seconds",
            "template_sha256",
            "verbose",
            "use_local_layer",
            "local_layer_version",
            "remote_layer_version");

    private final ObjectMapper mapper = new ObjectMapper();

    public Config load(Path configFile) throws ConfigException {
        if (configFile == null) {
            throw new ConfigException("config path must not be null");
        }
        if (!Files.isRegularFile(configFile)) {
            throw new ConfigException("config file not found: " + configFile);
        }
        JsonNode root = parseIntoTree(configFile);
        if (!root.isObject()) {
            throw new ConfigException("config root must be a JSON object: " + configFile);
        }
        return build(root);
    }

    private JsonNode parseIntoTree(Path configFile) throws ConfigException {
        try (InputStream in = Files.newInputStream(configFile)) {
            JsonNode node = mapper.readTree(in);
            if (node == null) {
                throw new ConfigException("config file is empty: " + configFile);
            }
            return node;
        } catch (IOException e) {
            throw new ConfigException("cannot read config file " + configFile + ": " + e.getMessage(), e);
        }
    }

    private Config build(JsonNode root) throws ConfigException {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigException("unknown config key: '" + key + "'");
            }
            values.put(key, entry.getValue());
        }

        String templateZipUrl = requiredText(values, "template_zip_url");
        String output = requiredText(values, "transmudation_output");
        String input = requiredText(values, "transmudation_input");
        JsonNode rewriteData = required(values, "rewrite_data");
        if (!rewriteData.isArray()) {
            throw new ConfigException("'rewrite_data' must be a JSON array");
        }
        boolean stopIfFail = requiredBoolean(values, "stop_if_fail");

        String cacheDir = optionalText(values, "cache_dir");
        Integer timeoutSeconds = optionalPositiveInt(values, "timeout_seconds");
        String templateSha256 = optionalText(values, "template_sha256");
        Boolean verbose = optionalBoolean(values, "verbose");
        Boolean useLocalLayer = optionalBoolean(values, "use_local_layer");
        String localLayerVersion = optionalText(values, "local_layer_version");
        String remoteLayerVersion = optionalText(values, "remote_layer_version");

        return new Config(templateZipUrl, output, input, rewriteData, stopIfFail,
                cacheDir, timeoutSeconds, templateSha256, verbose,
                useLocalLayer, localLayerVersion, remoteLayerVersion);
    }

    private static JsonNode required(Map<String, JsonNode> values, String key) throws ConfigException {
        JsonNode node = values.get(key);
        if (node == null || node.isNull()) {
            throw new ConfigException("missing required config field '" + key + "'");
        }
        return node;
    }

    private static String requiredText(Map<String, JsonNode> values, String key) throws ConfigException {
        JsonNode node = required(values, key);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new ConfigException("config field '" + key + "' must be a non-blank string");
        }
        return node.asText();
    }

    private static boolean requiredBoolean(Map<String, JsonNode> values, String key) throws ConfigException {
        JsonNode node = required(values, key);
        if (!node.isBoolean()) {
            throw new ConfigException("config field '" + key + "' must be a boolean (true/false)");
        }
        return node.asBoolean();
    }

    private static String optionalText(Map<String, JsonNode> values, String key) throws ConfigException {
        JsonNode node = values.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ConfigException("config field '" + key + "' must be a string when present");
        }
        return node.asText();
    }

    private static Integer optionalPositiveInt(Map<String, JsonNode> values, String key) throws ConfigException {
        JsonNode node = values.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.asInt() <= 0) {
            throw new ConfigException("config field '" + key + "' must be a positive integer when present");
        }
        return node.asInt();
    }

    private static Boolean optionalBoolean(Map<String, JsonNode> values, String key) throws ConfigException {
        JsonNode node = values.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new ConfigException("config field '" + key + "' must be a boolean when present");
        }
        return node.asBoolean();
    }
}
