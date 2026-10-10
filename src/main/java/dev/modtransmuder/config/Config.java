package dev.modtransmuder.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.modtransmuder.stage.transform.TransformStage;

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
 * {@code template_sha256}, {@code verbose}, {@code use_local_layer},
 * {@code local_layer_version}, {@code remote_layer_version}.
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
 * @param useLocalLayer      optional; when true, wire the compatibility layer
 *                           from the local Maven repo (publishToMavenLocal)
 *                           instead of JitPack (dev iteration path)
 * @param localLayerVersion  optional layer version for the local repo path;
 *                           effective default {@code 1.0.2-SNAPSHOT}
 * @param remoteLayerVersion optional layer version for the JitPack path;
 *                           effective default
 *                           {@link TransformStage#DEFAULT_REMOTE_LAYER_VERSION}
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
        Boolean verbose,
        Boolean useLocalLayer,
        String localLayerVersion,
        String remoteLayerVersion) {

    public Config {
        if (templateZipUrl == null || transmudationOutput == null
                || transmudationInput == null || rewriteData == null) {
            throw new IllegalArgumentException("Config required fields must not be null");
        }
    }

    /** True when the local-Maven (publishToMavenLocal) layer path is active. */
    public boolean useLocalLayerOrFalse() {
        return Boolean.TRUE.equals(useLocalLayer);
    }

    /** Layer version for the local-Maven path; {@code 1.0.2-SNAPSHOT} when unset/blank. */
    public String effectiveLocalLayerVersion() {
        return nonBlankOr(localLayerVersion, "1.0.2-SNAPSHOT");
    }

    /** Layer version for the JitPack path; default derived from
     * {@link TransformStage#DEFAULT_REMOTE_LAYER_VERSION} when unset/blank. */
    public String effectiveRemoteLayerVersion() {
        return nonBlankOr(remoteLayerVersion, TransformStage.DEFAULT_REMOTE_LAYER_VERSION);
    }

    private static String nonBlankOr(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
