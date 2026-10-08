package dev.modtransmuder.config;

import dev.modtransmuder.error.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    private Path writeConfig(@TempDir Path dir, String body) throws IOException {
        Path p = dir.resolve("config.json");
        Files.writeString(p, body);
        return p;
    }

    private static final String VALID = """
            {
              "template_zip_url": "https://example.com/template.zip",
              "transmudation_output": "out",
              "transmudation_input": "in-mods",
              "rewrite_data": [
                { "type": "REPLACE_LITERAL", "pattern": "1.19.4", "replacement": "1.21.1" }
              ],
              "stop_if_fail": true,
              "cache_dir": "cache/x",
              "timeout_seconds": 120,
              "template_sha256": "abc123",
              "verbose": true
            }
            """;

    @Test
    void happyPathParsesAllFields(@TempDir Path dir) throws IOException {
        Config c = loader.load(writeConfig(dir, VALID));
        assertEquals("https://example.com/template.zip", c.templateZipUrl());
        assertEquals("out", c.transmudationOutput());
        assertEquals("in-mods", c.transmudationInput());
        assertTrue(c.rewriteData().isArray());
        assertTrue(c.stopIfFail());
        assertEquals("cache/x", c.cacheDir());
        assertEquals(120, c.timeoutSeconds());
        assertEquals("abc123", c.templateSha256());
        assertEquals(Boolean.TRUE, c.verbose());
    }

    @Test
    void optionalFieldsDefaultToNull(@TempDir Path dir) throws IOException {
        String body = """
                {
                  "template_zip_url": "u",
                  "transmudation_output": "o",
                  "transmudation_input": "i",
                  "rewrite_data": [],
                  "stop_if_fail": false
                }
                """;
        Config c = loader.load(writeConfig(dir, body));
        assertNull(c.cacheDir());
        assertNull(c.timeoutSeconds());
        assertNull(c.templateSha256());
        assertNull(c.verbose());
    }

    @Test
    void rejectsUnknownKey(@TempDir Path dir) throws IOException {
        String body = """
                {
                  "template_zip_url": "u",
                  "transmudation_output": "o",
                  "transmudation_input": "i",
                  "rewrite_data": [],
                  "stop_if_fail": false,
                  "bogus": 1
                }
                """;
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(writeConfig(dir, body)));
        assertEquals("unknown config key: 'bogus'", e.getMessage());
    }

    @Test
    void rejectsMissingRequiredField(@TempDir Path dir) throws IOException {
        String body = """
                {
                  "template_zip_url": "u",
                  "transmudation_output": "o",
                  "rewrite_data": [],
                  "stop_if_fail": false
                }
                """;
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(writeConfig(dir, body)));
        assertTrue(e.getMessage().contains("transmudation_input"));
    }

    @Test
    void rejectsMissingFile(@TempDir Path dir) {
        ConfigException e = assertThrows(ConfigException.class,
                () -> loader.load(dir.resolve("nope.json")));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void rejectsMalformedJson(@TempDir Path dir) throws IOException {
        ConfigException e = assertThrows(ConfigException.class,
                () -> loader.load(writeConfig(dir, "{ not json")));
        assertTrue(e.getMessage().startsWith("cannot read config file"));
    }
}
