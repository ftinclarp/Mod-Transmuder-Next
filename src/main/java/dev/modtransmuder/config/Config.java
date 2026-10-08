package dev.modtransmuder.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable, 1:1 view of the JSON config (ARCHITECTURE §4). Fields follow
 * the JSON key naming prefixed as camelCase; optional fields are {@code null}
 * when absent. {@code rewriteData} is kept as the raw {@link JsonNode} for
 * now — the typed {@code RewriteData} model arrives in step 4.
 *
 * <p>Required (by prototype same-shape constraint): {@code template_zip_url},
 * {@code transmudation_output}, {@code transmudation_input},
 * {@code rewrite_data}, {@code stop_if_fail}.
 * <p>Optional extensions: {@code cache_dir}, {@code timeout_seconds},
 * {@code template_sha256}, {@code verbose}.
 *
 * @param templateZipUrl     URL of the Fabric template zip
 * @param transmudationOutput output directory path
 * @param transmudationInput  input (Forge mod) directory path
 * @param rewriteData         raw inline rewrite rules (array) — parsed
 *                            strictly later
 * @param stopIfFail         abort pipeline on first failure if true
 * @param cacheDir           optional download cache dir
 * @param timeoutSeconds     optional overall timeout for network/build steps
 * @param templateSha256     optional template zip SHA-256 for verification
 * @param verbose            optional flag to enable DEBUG logging
 */
public record Config(
        String templateZipUrl,
        String transmudationOutput,
        String transmudationInput,
        JsonNode rewriteData,
        boolean stopIfFail,
        String cacheDir,
        Integer timeoutSeconds,
        String templateSha256,
        Boolean verbose) {

    public Config {
        if (templateZipUrl == null || transmudationOutput == null
                || transmudationInput == null || rewriteData == null) {
            throw new IllegalArgumentException("Config required fields must not be null");
        }
    }
}
