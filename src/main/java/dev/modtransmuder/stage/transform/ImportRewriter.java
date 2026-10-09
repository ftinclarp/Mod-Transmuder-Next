package dev.modtransmuder.stage.transform;

import java.util.regex.Pattern;

/**
 * Rewrites Forge 1.7.10 namespaces to the MTN compatibility layer (stage 4c).
 *
 * <p>v1 rule: every occurrence of {@code cpw.mods.fml.} (imports and
 * fully-qualified references in code) becomes
 * {@code mtn.forge_layer.cpw.mods.fml.}. {@code net.minecraft.*} is left
 * untouched — there is no layer for it yet, and it would collide with Fabric.
 */
final class ImportRewriter {

    private static final Pattern FML_PACKAGE = Pattern.compile("\\bcpw\\.mods\\.fml\\.");
    private static final String REWRITTEN = "mtn.forge_layer.cpw.mods.fml.";

    private ImportRewriter() {
    }

    /**
     * @param javaSource the raw text of a ported Java file
     * @return the text with every {@code cpw.mods.fml.} prefixed by the layer,
     *         i.e. {@code mtn.forge_layer.cpw.mods.fml.}; other content unchanged
     */
    static String rewriteFml(String javaSource) {
        return FML_PACKAGE.matcher(javaSource).replaceAll(REWRITTEN);
    }
}
