package dev.modtransmuder.stage.transform;

import java.util.regex.Pattern;

/**
 * Rewrites Forge 1.7.10 namespaces to the MTN compatibility layers
 * (stage 4c).
 *
 * <p>Rules (in order):
 * <ol>
 *   <li>every occurrence of {@code cpw.mods.fml.} (imports and
 *       fully-qualified references in code) becomes
 *       {@code mtn.forge_layer.cpw.mods.fml.};</li>
 *   <li>every occurrence of {@code net.minecraft.} becomes
 *       {@code mtn.minecraft_layer.net.minecraft.} — the Minecraft API layer
 *       (MTN-minecraft-layer) provides the stand-in stubs.</li>
 * </ol>
 */
final class ImportRewriter {

    /** Order matters: the FML prefix is replaced first (both share no overlap). */
    private static final Pattern[] PACKAGE_PATTERNS = {
        Pattern.compile("\\bcpw\\.mods\\.fml\\."),
        Pattern.compile("\\bnet\\.minecraft\\."),
    };
    private static final String[] REWRITTEN_TO = {
        "mtn.forge_layer.cpw.mods.fml.",
        "mtn.minecraft_layer.net.minecraft.",
    };

    private ImportRewriter() {
    }

    /**
     * @param javaSource the raw text of a ported Java file
     * @return the text with every {@code cpw.mods.fml.} rewritten to
     *         {@code mtn.forge_layer.cpw.mods.fml.} and every
     *         {@code net.minecraft.} rewritten to
     *         {@code mtn.minecraft_layer.net.minecraft.}; other content
     *         unchanged
     */
    static String rewriteFml(String javaSource) {
        String result = javaSource;
        for (int i = 0; i < PACKAGE_PATTERNS.length; i++) {
            result = PACKAGE_PATTERNS[i].matcher(result).replaceAll(REWRITTEN_TO[i]);
        }
        return result;
    }
}
