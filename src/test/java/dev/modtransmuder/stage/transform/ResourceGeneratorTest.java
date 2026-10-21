package dev.modtransmuder.stage.transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resource model/blockstate generation: verifies that a block registered with
 * the 3-arg Forge form and items registered with variable names / texture
 * folders get lowercase-suffixed model JSONs and blockstates in the output.
 */
class ResourceGeneratorTest {

    @TempDir
    Path tmp;

    private Path makeMod(String modid) throws IOException {
        Path mod = tmp.resolve("mod-" + modid);
        // Real 1.7.10 mods: capitalized id in mcmod.info, lowercase asset folder
        Path res = mod.resolve("src/main/resources/assets/"
                + modid.toLowerCase(java.util.Locale.ROOT));
        Files.createDirectories(res.resolve("textures/model"));
        Files.createDirectories(res.resolve("textures/items"));
        Files.createDirectories(mod.resolve("src/main/resources"));
        Files.writeString(mod.resolve("src/main/resources/mcmod.info"),
                "{\"modid\":\"" + modid + "\",\"name\":\"" + modid + "\"}\n");
        // block texture for the 3-arg block form ("BlockCheese" -> blockcheese
        // -> resolves to model/cheese.png, like IronChest's model/ironchest.png)
        Files.writeString(res.resolve("textures/model/cheese.png"), "png(placeholder)");
        // item texture -> item model (registerItem(item, variableName) form)
        Files.writeString(res.resolve("textures/items/cheese_round.png"), "png(placeholder)");
        return mod;
    }

    @Test
    void generatesModelsAndBlockstatesForRegisteredNames() throws IOException {
        Path mod = makeMod("YourMod");
        Path outRes = tmp.resolve("out/src/main/resources");
        Path outJava = tmp.resolve("out/src/main/java");
        Files.createDirectories(outJava.resolve("com/example"));
        Files.writeString(outJava.resolve("com/example/Mod.java"),
                "package com.example;\n"
                        + "import mtn.forge_layer.cpw.mods.fml.common.registry.GameRegistry;\n"
                        + "public class Mod {\n"
                        + "  static void init() {\n"
                        + "    GameRegistry.registerBlock(ironChestBlock, Item.class, \"BlockCheese\");\n"
                        + "    GameRegistry.registerItem(item, itemName);\n"
                        + "  }\n"
                        + "}\n");

        ResourceGenerator.generate(mod, outJava, outRes, "YourMod");

        Path asset = outRes.resolve("assets/yourmod");
        // lowercase-namespaced model/blockstate files
        assertTrue(Files.isRegularFile(asset.resolve("models/block/blockcheese.json")),
                "block model generated for 3-arg registerBlock");
        assertTrue(Files.isRegularFile(asset.resolve("models/item/blockcheese.json")),
                "block-item model generated");
        assertTrue(Files.isRegularFile(asset.resolve("blockstates/blockcheese.json")),
                "blockstate generated");
        // item model from the texture folder (variable-name registerItem)
        assertTrue(Files.isRegularFile(asset.resolve("models/item/cheese_round.json")),
                "item model generated from textures/items folder");
        // content references the lowercase registry id
        String blockstate = Files.readString(asset.resolve("blockstates/blockcheese.json"));
        assertTrue(blockstate.contains("yourmod:block/blockcheese"),
                "blockstate must reference the lowercase model id: " + blockstate);
        String blockModel = Files.readString(asset.resolve("models/block/blockcheese.json"));
        // "blockcheese" strips "block" -> "cheese" found as model/cheese.png,
        // copied into textures/block so the blocks atlas indexes it
        assertTrue(blockModel.contains("yourmod:block/cheese"),
                "block model should reference a block-visible texture: " + blockModel);
        assertTrue(Files.isRegularFile(asset.resolve("textures/block/cheese.png")),
                "chest sheet copied into textures/block for the blocks atlas");
        String itemModel = Files.readString(asset.resolve("models/item/cheese_round.json"));
        assertTrue(itemModel.contains("yourmod:item/cheese_round"),
                "item model must reference the lowercase texture id: " + itemModel);
    }

    @Test
    void tesrBlockGetsEmptyInWorldModelButVisibleItemIcon() throws IOException {
        Path mod = makeMod("Cr8Mod");
        Path outRes = tmp.resolve("out2/src/main/resources");
        Path outJava = tmp.resolve("out2/src/main/java");
        Files.createDirectories(outJava.resolve("cpw/mods/cr8"));
        // Registrar so the block name is discovered.
        Files.writeString(outJava.resolve("cpw/mods/cr8/Mod.java"),
                "package cpw.mods.cr8;\n"
                        + "import mtn.forge_layer.cpw.mods.fml.common.registry.GameRegistry;\n"
                        + "public class Mod {\n"
                        + "  static void init(){\n"
                        + "    GameRegistry.registerBlock(block, \"BlockMagicChest\");\n"
                        + "  }\n"
                        + "}\n");
        // Block source: a block that creates a tile entity.
        Files.writeString(outJava.resolve("cpw/mods/cr8/BlockMagicChest.java"),
                "package cpw.mods.cr8;\n"
                        + "import mtn.minecraft_layer.net.minecraft.tileentity.TileEntity;\n"
                        + "public class BlockMagicChest {\n"
                        + "  public TileEntity createNewTileEntity(Object w, int i){return new TileEntityMagicChest();}\n"
                        + "}\n");
        // TESR binding site for the block's tile class.
        Files.writeString(outJava.resolve("cpw/mods/cr8/Proxy.java"),
                "package cpw.mods.cr8;\n"
                        + "import mtn.forge_layer.cpw.mods.fml.client.registry.ClientRegistry;\n"
                        + "public class Proxy {\n"
                        + "  static void reg(){ ClientRegistry.bindTileEntitySpecialRenderer(TileEntityMagicChest.class, null); }\n"
                        + "}\n");
        Files.writeString(outJava.resolve("cpw/mods/cr8/TileEntityMagicChest.java"),
                "package cpw.mods.cr8;\n"
                        + "public class TileEntityMagicChest {}\n");

        ResourceGenerator.generate(mod, outJava, outRes, "Cr8Mod");

        Path asset = outRes.resolve("assets/cr8mod");
        // Block model: no cube elements, no cube_all parent => nothing baked,
        // so no static box overlaps the TESR geometry.
        String blockModel = Files.readString(asset.resolve("models/block/blockmagicchest.json"));
        assertTrue(blockModel.contains("\"particle\""),
                "TESR block model should keep a particle texture: " + blockModel);
        assertTrue(!blockModel.contains("cube_all") && !blockModel.contains("\"elements\""),
                "TESR block model must not bake any cube elements: " + blockModel);
        // Item icon: still a visible cube_all so the hotbar/inventory item shows.
        String itemModel = Files.readString(asset.resolve("models/item/blockmagicchest.json"));
        assertTrue(itemModel.contains("block/cube_all"),
                "TESR block item icon should keep a visible cube_all parent: " + itemModel);
    }
}
