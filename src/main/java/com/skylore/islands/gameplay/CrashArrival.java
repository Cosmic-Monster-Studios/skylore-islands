package com.skylore.islands.gameplay;

import com.skylore.islands.block.WreckContainerBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;
import java.util.function.Consumer;

/** First moments after the crash: waking up dazed, and the black box's corrupted flight log. */
public final class CrashArrival {
    private static final int LOG_LINE_TICKS = 30;

    private CrashArrival() {}

    /** Dark, ringing, hurt and hungry, then the title once the eyes clear. */
    public static void wakeUp(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 90, 0, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 280, 0, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 240, 1, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600, 0, false, false));
        player.setHealth(12.0F);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0.0F);

        sound(player, SoundEvents.GENERIC_EXPLODE.value(), 0.5F, 0.5F);
        later(player, 10, p -> sound(p, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.9F));
        later(player, 30, p -> sound(p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6F, 1.95F));
        later(player, 55, p -> sound(p, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.3F, 2.0F));
        later(player, 80, p -> {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(20, 70, 30));
            p.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("SIGNAL LOST").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("Location: ").withStyle(ChatFormatting.GRAY).append(glitch("unknown"))));
            sound(p, SoundEvents.BEACON_DEACTIVATE, 0.8F, 0.6F);
        });
    }

    /** Plays the recovered log in chat line by line and drops the log book into the box. */
    public static void playFlightLog(ServerPlayer player, WreckContainerBlockEntity box) {
        box.addItem(logBook());
        List<Component> lines = List.of(
                line("Emergency recorder online."),
                line("Impact detected. Hull integrity: 3%"),
                line("NAV ARRAY ").append(glitch("....")).append(" OFFLINE"),
                line("Position: ").append(glitch("##.###")).append(" / ").append(glitch("##.###")).append(" - no fix"),
                line("Cargo pods jettisoned: 3. Status: unknown."),
                line("Recorder entering low power mode."));
        for (int i = 0; i < lines.size(); i++) {
            Component text = lines.get(i);
            later(player, i * LOG_LINE_TICKS, p -> {
                p.sendSystemMessage(text);
                sound(p, SoundEvents.NOTE_BLOCK_BIT.value(), 0.5F, 1.4F);
            });
        }
    }

    private static ItemStack logBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<Filterable<Component>> pages = List.of(
                page(Component.literal("FLIGHT RECORDER\nUNIT 7-C\n\nLOG RECOVERED: 4%\n\n")
                        .append("> T-00:41 reactor surge, deck 2\n> T-00:33 hull breach, deck 3")),
                page(Component.literal("> T-00:20 NAV ARRAY OFFLINE\n> T-00:12 cargo pods jettisoned (3)\n"
                        + "> T-00:04 impact imminent\n> T-00:00 ").append(glitch("########"))),
                page(Component.literal("POSITION: ").append(glitch("##.###")).append("\nSTATUS: UNKNOWN\n\n")
                        .append("Recover cargo pods.\nRestore signal.\n\n// end of log")));
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough("Flight Log 7-C"), "Black Box", 3, pages, true));
        return book;
    }

    private static Filterable<Component> page(Component text) {
        return Filterable.passThrough(text);
    }

    private static MutableComponent line(String text) {
        return Component.literal("[BLACK BOX] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    private static MutableComponent glitch(String text) {
        return Component.literal(text).withStyle(ChatFormatting.OBFUSCATED, ChatFormatting.DARK_GRAY);
    }

    private static void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
    }

    private static void later(ServerPlayer player, int ticks, Consumer<ServerPlayer> action) {
        PlayerTasks.schedule(player, ticks, action);
    }
}
