package com.arrl.radiocraft.common.commands;

import com.arrl.radiocraft.common.blockentities.ModerationComputerBlockEntity;
import com.arrl.radiocraft.common.moderation.ModerationLog;
import com.arrl.radiocraft.common.moderation.TransmissionQuery;
import com.arrl.radiocraft.common.moderation.TransmissionRecord;
import com.arrl.radiocraft.common.radio.voice.RadioModeration;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * {@code /radio moderateall}, {@code /radio moderate <player>} and {@code /radio moderate off} (also under
 * {@code /radiocraft}), for operators: listen to radio traffic clean, with who is talking shown (see
 * {@link RadioModeration}). And {@code /radio moderation status|list|purge|flag|unflag|export} for the moderation log
 * ({@link ModerationLog}), for testing and for servers without a moderation computer, and {@code /radio moderation
 * unlock <pos>} to clear a computer's lock.
 */
public class ModerationCommands {

	public static final LiteralArgumentBuilder<CommandSourceStack> BUILDER = root("radiocraft");
	public static final LiteralArgumentBuilder<CommandSourceStack> SHORT_BUILDER = root("radio");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
		return Commands.literal(name)
				.then(Commands.literal("moderateall").requires(source -> source.hasPermission(2)).executes(ModerationCommands::all))
				.then(Commands.literal("moderate").requires(source -> source.hasPermission(2))
						.then(Commands.literal("off").executes(ModerationCommands::off))
						.then(Commands.argument("player", EntityArgument.player()).executes(ModerationCommands::one)))
				.then(Commands.literal("moderation").requires(source -> source.hasPermission(3))
						.then(Commands.literal("status").executes(ModerationCommands::status))
						.then(Commands.literal("list").executes(ctx -> list(ctx, 10))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 50)).executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
						.then(Commands.literal("purge").executes(ModerationCommands::purge))
						.then(Commands.literal("flag").then(Commands.argument("id", LongArgumentType.longArg(0L)).executes(ctx -> flag(ctx, true))))
						.then(Commands.literal("unflag").then(Commands.argument("id", LongArgumentType.longArg(0L)).executes(ctx -> flag(ctx, false))))
						.then(Commands.literal("export").then(Commands.argument("id", LongArgumentType.longArg(0L)).executes(ModerationCommands::export)))
						.then(Commands.literal("unlock").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ModerationCommands::unlock))));
	}

	/**
	 * Clears a moderation computer's lock (owner and admins), so one whose owner is gone is never stranded; the next
	 * operator to use it owns it.
	 */
	private static int unlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		if(!(ctx.getSource().getLevel().getBlockEntity(pos) instanceof ModerationComputerBlockEntity computer)) {
			ctx.getSource().sendFailure(Component.translatable("commands.radiocraft.moderation.not_computer", pos.toShortString()));
			return 0;
		}
		String owner = computer.getOwnerName();
		computer.unlock();
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.radiocraft.moderation.unlocked", pos.toShortString(), owner.isEmpty() ? "-" : owner), true);
		return 1;
	}

	private static int all(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer moderator = ctx.getSource().getPlayerOrException();
		RadioModeration.listen(moderator, RadioModeration.EVERYONE);
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.radiocraft.moderate.all"), true);
		return 1;
	}

	private static int one(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer moderator = ctx.getSource().getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		RadioModeration.listen(moderator, target.getUUID());
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.radiocraft.moderate.one", target.getDisplayName()), true);
		return 1;
	}

	private static int off(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer moderator = ctx.getSource().getPlayerOrException();
		boolean was = RadioModeration.stop(moderator.getUUID());
		ctx.getSource().sendSuccess(() -> Component.translatable(was ? "commands.radiocraft.moderate.off" : "commands.radiocraft.moderate.not_on"), true);
		return was ? 1 : 0;
	}

	// -------------------- THE MODERATION LOG --------------------

	private static ModerationLog log(CommandContext<CommandSourceStack> ctx) {
		ModerationLog log = ModerationLog.get(ctx.getSource().getServer());
		if(log == null)
			ctx.getSource().sendFailure(Component.translatable("commands.radiocraft.moderation.not_running"));
		return log;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		ModerationLog log = log(ctx);
		if(log == null)
			return 0;
		ModerationLog.Status status = log.status();
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.radiocraft.moderation.status", status.recordRadio(), status.recordProximity(), status.metadataOnly(),
				status.retentionDays(), status.records(), status.open(), String.format(Locale.ROOT, "%.1f", status.storageBytes() / 1_048_576.0D), status.dropped()), false);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx, int count) {
		ModerationLog log = log(ctx);
		if(log == null)
			return 0;
		TransmissionQuery.Page page = log.query(TransmissionQuery.all().withPage(0, count));
		for(ModerationLog.LiveTransmission live : log.live())
			ctx.getSource().sendSuccess(() -> Component.literal("ON AIR " + live.id() + " " + live.kind().id() + " " + live.radioKind() + " "
					+ String.format(Locale.ROOT, "%.4f", live.frequency() / 1_000_000.0D) + " MHz, " + live.hearers() + " hearing"), false);
		for(TransmissionRecord record : page.items()) {
			TransmissionRecord.Talker talker = record.talker();
			String line = record.id() + " " + TIME.format(Instant.ofEpochMilli(record.startMs())) + " " + record.kind().id() + " "
					+ (talker == null ? record.radioKind() : talker.name()) + " " + String.format(Locale.ROOT, "%.4f", record.frequency() / 1_000_000.0D) + " MHz "
					+ (record.durationMs() / 1000L) + "s, heard by " + record.hearers().size() + (record.hasAudio() ? ", " + record.audioFrames() + " frames" : "")
					+ (record.flagged() ? ", flagged" : "") + (record.text().isEmpty() ? "" : ": " + record.text());
			ctx.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.radiocraft.moderation.listed", page.items().size(), page.total()), false);
		return page.items().size();
	}

	private static int purge(CommandContext<CommandSourceStack> ctx) {
		ModerationLog log = log(ctx);
		if(log == null)
			return 0;
		reply(ctx, log.purge(), deleted -> Component.translatable("commands.radiocraft.moderation.purged", deleted));
		return 1;
	}

	private static int flag(CommandContext<CommandSourceStack> ctx, boolean flag) {
		ModerationLog log = log(ctx);
		if(log == null)
			return 0;
		long id = LongArgumentType.getLong(ctx, "id");
		UUID who = ctx.getSource().getEntity() == null ? null : ctx.getSource().getEntity().getUUID();
		String name = ctx.getSource().getTextName();
		reply(ctx, flag ? log.flag(id, who, name) : log.unflag(id, who, name), record -> record == null && flag
				? Component.translatable("commands.radiocraft.moderation.unknown", id)
				: Component.translatable(flag ? "commands.radiocraft.moderation.flagged" : "commands.radiocraft.moderation.unflagged", id));
		return 1;
	}

	private static int export(CommandContext<CommandSourceStack> ctx) {
		ModerationLog log = log(ctx);
		if(log == null)
			return 0;
		long id = LongArgumentType.getLong(ctx, "id");
		UUID who = ctx.getSource().getEntity() == null ? null : ctx.getSource().getEntity().getUUID();
		reply(ctx, log.export(id, who, ctx.getSource().getTextName()), files -> files == null
				? Component.translatable("commands.radiocraft.moderation.unknown", id)
				: Component.translatable("commands.radiocraft.moderation.exported", files.size(), log.folder().resolve("exports").toString()));
		return 1;
	}

	/** Tells the command's source the outcome of {@code future} once it is done, on the server thread. */
	private static <T> void reply(CommandContext<CommandSourceStack> ctx, CompletableFuture<T> future, Function<T, Component> message) {
		CommandSourceStack source = ctx.getSource();
		future.whenComplete((result, error) -> source.getServer().execute(() -> {
			if(error != null)
				source.sendFailure(Component.literal(String.valueOf(error.getMessage())));
			else
				source.sendSuccess(() -> message.apply(result), true);
		}));
	}
}
