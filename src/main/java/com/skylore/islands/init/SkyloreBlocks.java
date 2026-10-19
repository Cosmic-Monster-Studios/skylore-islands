package com.skylore.islands.init;

import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.block.BlackBoxBlock;
import com.skylore.islands.block.SmolderingHullBlock;
import com.skylore.islands.block.WreckContainerBlock;
import com.skylore.islands.block.WreckContainerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SkyloreBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SkyloreIslands.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SkyloreIslands.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SkyloreIslands.MOD_ID);

    public static final DeferredBlock<Block> SKY_STONE = BLOCKS.registerSimpleBlock("sky_stone",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(50.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.DEEPSLATE));

    public static final DeferredBlock<Block> SHIP_HULL = BLOCKS.registerSimpleBlock("ship_hull",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.NETHERITE_BLOCK));

    public static final DeferredBlock<SmolderingHullBlock> SMOLDERING_HULL = BLOCKS.registerBlock("smoldering_hull",
            SmolderingHullBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.NETHERITE_BLOCK)
                    .lightLevel(state -> 4)
                    .randomTicks());

    public static final DeferredBlock<IronBarsBlock> SHIP_HULL_FRAME = BLOCKS.registerBlock("ship_hull_frame",
            IronBarsBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.CHAIN)
                    .noOcclusion());

    public static final DeferredBlock<BlackBoxBlock> BLACK_BOX = BLOCKS.registerBlock("black_box",
            BlackBoxBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(50.0F, 1200.0F)
                    .sound(SoundType.DEEPSLATE)
                    .lightLevel(state -> state.getValue(BlackBoxBlock.SIGNAL) ? 7 : 3));

    public static final DeferredBlock<WreckContainerBlock> CARGO_POD = BLOCKS.registerBlock("cargo_pod",
            WreckContainerBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.NETHERITE_BLOCK));

    public static final DeferredBlock<WreckContainerBlock> SKY_CACHE = BLOCKS.registerBlock("sky_cache",
            WreckContainerBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.STONE));

    /** Shared by the black box, cargo pods and sky caches; id kept as "black_box" so existing boxes keep their contents. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WreckContainerBlockEntity>> WRECK_CONTAINER =
            BLOCK_ENTITIES.register("black_box",
                    () -> BlockEntityType.Builder.of(WreckContainerBlockEntity::new, BLACK_BOX.get(), CARGO_POD.get(), SKY_CACHE.get()).build(null));

    public static final DeferredItem<Item> SHIP_SCRAP = ITEMS.registerSimpleItem("ship_scrap");

    static {
        ITEMS.registerSimpleBlockItem(SKY_STONE);
        ITEMS.registerSimpleBlockItem(SHIP_HULL);
        ITEMS.registerSimpleBlockItem(SMOLDERING_HULL);
        ITEMS.registerSimpleBlockItem(SHIP_HULL_FRAME);
        ITEMS.registerSimpleBlockItem(BLACK_BOX);
        ITEMS.registerSimpleBlockItem(CARGO_POD);
        ITEMS.registerSimpleBlockItem(SKY_CACHE);
    }

    private SkyloreBlocks() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(SkyloreBlocks::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(SKY_STONE);
            event.accept(SHIP_HULL);
            event.accept(SMOLDERING_HULL);
            event.accept(SHIP_HULL_FRAME);
        } else if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(BLACK_BOX);
            event.accept(CARGO_POD);
            event.accept(SKY_CACHE);
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(SHIP_SCRAP);
        }
    }
}
