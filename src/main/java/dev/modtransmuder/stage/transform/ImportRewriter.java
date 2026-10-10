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
 *       (MTN-minecraft-layer) provides the stand-in stubs; except
 *       {@code net.minecraft.network.FriendlyByteBuf}, which is the Mojmap
 *       name of a real class and is rewritten to the Yarn name used by the
 *       output project ({@code net.minecraft.network.PacketByteBuf}) so
 *       ported code can override the forge-layer network interfaces
 *       ({@code mtn.forge_layer.cpw.mods.fml.common.network.simpleimpl.IMessage}).</li>
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

    /** Real Mojmap class mapped to its Yarn equivalent for the output project. */
    private static final Pattern PROTECT_PATTERN =
            Pattern.compile("\\bnet\\.minecraft\\.network\\.FriendlyByteBuf\\b");
    private static final String PROTECT_TOKEN = "@@MTN_REAL_FRIENDLY_BYTE_BUF@@";
    private static final String YARN_EQUIVALENT = "net.minecraft.network.PacketByteBuf";

    private ImportRewriter() {
    }

    /**
     * @param javaSource the raw text of a ported Java file
     * @return the text with every {@code cpw.mods.fml.} rewritten to
     *         {@code mtn.forge_layer.cpw.mods.fml.} and every
     *         {@code net.minecraft.} rewritten to
     *         {@code mtn.minecraft_layer.net.minecraft.} (except the real
     *         {@code net.minecraft.network.FriendlyByteBuf}, which is rewritten
     *         to the output project's Yarn name {@code net.minecraft.network.PacketByteBuf});
     *         other content unchanged
     */
    static String rewriteFml(String javaSource) {
        String result = PROTECT_PATTERN.matcher(javaSource).replaceAll(PROTECT_TOKEN);
        for (int i = 0; i < PACKAGE_PATTERNS.length; i++) {
            result = PACKAGE_PATTERNS[i].matcher(result).replaceAll(REWRITTEN_TO[i]);
        }
        return result.replace(PROTECT_TOKEN, YARN_EQUIVALENT);
    }
}
