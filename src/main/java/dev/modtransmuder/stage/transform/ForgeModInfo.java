package dev.modtransmuder.stage.transform;

import java.util.List;

/**
 * Minimal read-only model of a Forge 1.7.10 mod's {@code mcmod.info} metadata
 * (ARCHITECTURE §4 {@code ModModel}-lite). Fields required by stage 4a.
 *
 * @param modid       the mod's id, e.g. {@code "mtnexample"}
 * @param name        human-readable name, e.g. {@code "MTN Example"}
 * @param version     version string, e.g. {@code "1.0"}
 * @param description free-text description (may be empty)
 * @param authors     author names from {@code authorList} (may be empty)
 */
public record ForgeModInfo(
        String modid,
        String name,
        String version,
        String description,
        List<String> authors) {

    public ForgeModInfo {
        authors = List.copyOf(authors);
        if (modid == null || modid.isBlank()
                || name == null || name.isBlank()
                || version == null || version.isBlank()) {
            throw new IllegalArgumentException("ForgeModInfo requires non-blank modid/name/version");
        }
    }
}
