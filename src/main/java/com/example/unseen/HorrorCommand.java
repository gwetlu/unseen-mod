package com.example.unseen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

/** Debug/test commands (op level 2): /horror reset | phase <1-3> | scare | status | on | off */
public final class HorrorCommand {
    private HorrorCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("horror")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("reset").executes(ctx -> {
                            HorrorManager.reset(ctx.getSource().getPlayerOrThrow());
                            ctx.getSource().sendFeedback(() -> Text.literal("Horror timer reset."), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("phase")
                                .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 3)).executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                    HorrorManager.setPhase(ctx.getSource().getPlayerOrThrow(), n);
                                    ctx.getSource().sendFeedback(() -> Text.literal("Phase set to " + n), false);
                                    return 1;
                                })))
                        .then(CommandManager.literal("scare").executes(ctx -> {
                            HorrorManager.forceScare(ctx.getSource().getPlayerOrThrow());
                            return 1;
                        }))
                        .then(CommandManager.literal("status").executes(ctx -> {
                            String msg = HorrorManager.status(ctx.getSource().getPlayerOrThrow());
                            ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("on").executes(ctx -> {
                            HorrorManager.enabled = true;
                            ctx.getSource().sendFeedback(() -> Text.literal("Horror enabled."), true);
                            return 1;
                        }))
                        .then(CommandManager.literal("off").executes(ctx -> {
                            HorrorManager.enabled = false;
                            ctx.getSource().sendFeedback(() -> Text.literal("Horror disabled."), true);
                            return 1;
                        }))));
    }
}
