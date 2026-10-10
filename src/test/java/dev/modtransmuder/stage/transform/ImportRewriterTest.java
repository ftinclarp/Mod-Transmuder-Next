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
    void rewritesFmlImportStatement() {
        assertEquals(
                "import mtn.forge_layer.cpw.mods.fml.common.Mod;",
                ImportRewriter.rewriteFml("import cpw.mods.fml.common.Mod;"));
    }

    @Test
    void rewritesMinecraftImportStatement() {
        assertEquals(
                "import mtn.forge_layer.net.minecraft.block.Block;",
                ImportRewriter.rewriteFml("import net.minecraft.block.Block;"));
    }

    @Test
    void rewritesFullyQualifiedReferencesInCode() {
        assertEquals(
                "class X { mtn.forge_layer.cpw.mods.fml.common.event.FMLPreInitializationEvent e; }",
                ImportRewriter.rewriteFml("class X { cpw.mods.fml.common.event.FMLPreInitializationEvent e; }"));
    }

    @Test
    void rewritesBlockAndItemSourceTogether() {
        String src = ""
                + "import net.minecraft.block.Block;\n"
                + "import cpw.mods.fml.common.registry.GameRegistry;\n"
                + "import net.minecraft.item.Item;\n"
                + "class Test { }\n";
        String out = ImportRewriter.rewriteFml(src);
        assertTrue(out.contains("import mtn.forge_layer.net.minecraft.block.Block;"),
                "net.minecraft.block must rewrite");
        assertTrue(out.contains("import mtn.forge_layer.net.minecraft.item.Item;"),
                "net.minecraft.item must rewrite");
        assertTrue(out.contains("import mtn.forge_layer.cpw.mods.fml.common.registry.GameRegistry;"),
                "cpw.mods.fml must rewrite");
    }

    @Test
    void leavesNonForgeContentUntouched() {
        assertEquals("System.out.println(\"hello\");",
                ImportRewriter.rewriteFml("System.out.println(\"hello\");"));
        assertFalse(ImportRewriter.rewriteFml("import java.util.List;").contains("mtn.forge_layer"));
    }
}
