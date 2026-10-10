package dev.modtransmuder.stage.transform;

import java.util.regex.Pattern;

/**
 * Rewrites Forge 1.7.10 namespaces to the MTN compatibility layer (stage 4c).
 *
 * <p>Rules (in order):
 * <ol>
 *   <li>every occurrence of {@code cpw.mods.fml.} (imports and
 *       fully-qualified references in code) becomes
 *       {@code mtn.forge_layer.cpw.mods.fml.};</li>
 *   <li>every occurrence of {@code net.minecraft.} becomes
 *       {@code mtn.forge_layer.net.minecraft.} — the layer now provides
 *       stand-in stubs so ported Forge block/item code compiles.</li>
 * </ol>
 */
final class ImportRewriter {

    /** Order matters: the FML prefix is replaced first (both share no overlap). */
    private static final Pattern[] PACKAGE_PATTERNS = {
        Pattern.compile("\\bcpw\\.mods\\.fml\\."),
        Pattern.compile("\\bnet\\.minecraft\\."),
    };
    private static final String[] REWRITTENS = {
        "mtn.forge_layer.cpw.mods.fml.",
        "mtn.forge_layer.net.minecraft.",
    };

    private ImportRewriter() {
    }

    /**
     * @param javaSource the raw text of a ported Java file
     * @return the text with every {@code cpw.mods.fml.} and
     *         {@code net.minecraft.} prefixed by the layer, i.e.
     *         {@code mtn.forge_layer.cpw.mods.fml.} /
     *         {@code mtn.forge_layer.net.minecraft.}; other content unchanged
     */
    static String rewriteFml(String javaSource) {
        String result = javaSource;
        for (int i = 0; i < PACKAGE_PATTERNS.length; i++) {
            result = PACKAGE_PATTERNS[i].matcher(result).replaceAll(REWRITTENS[i]);
        }
        return result;
    }
}
