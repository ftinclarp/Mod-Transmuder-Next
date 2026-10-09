package dev.modtransmuder.stage.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4c — the import rewriter.
 */
class ImportRewriterTest {

    @Test
    void rewritesImportStatement() {
        assertEquals(
                "import mtn.forge_layer.cpw.mods.fml.common.Mod;",
                ImportRewriter.rewriteFml("import cpw.mods.fml.common.Mod;"));
    }

    @Test
    void rewritesFullyQualifiedReferencesInCode() {
        assertEquals(
                "class X { mtn.forge_layer.cpw.mods.fml.common.event.FMLPreInitializationEvent e; }",
                ImportRewriter.rewriteFml("class X { cpw.mods.fml.common.event.FMLPreInitializationEvent e; }"));
    }

    @Test
    void leavesNonFmlContentUntouched() {
        assertEquals("import net.minecraft.server.MinecraftServer;",
                ImportRewriter.rewriteFml("import net.minecraft.server.MinecraftServer;"));
        assertFalse(ImportRewriter.rewriteFml("import net.minecraft.block.Block;")
                .contains("mtn.forge_layer"));
    }
}
