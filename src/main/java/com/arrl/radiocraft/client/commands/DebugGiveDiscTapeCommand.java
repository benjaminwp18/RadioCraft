package com.arrl.radiocraft.client.commands;

import com.arrl.radiocraft.common.data.TapeRecordingSavedData;
import com.arrl.radiocraft.common.network.serverbound.SAudioUploadPacket;
import com.arrl.radiocraft.common.radio.tape.OggDecoder;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.JukeboxSong;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Debug command to upload music disc files to the server & give the player the corresponding tape.
 * Separated from common debug commands, as the portion of the command logic before the upload must
 * be done on the client (where the music disc files are).
 */
public class DebugGiveDiscTapeCommand {
	private static final int CHUNK_SAMPLES = SAudioUploadPacket.MAX_CHUNK_BYTES / 2;

	public static HashMap<String, JukeboxSong> songs = new HashMap<>();

	public static LiteralArgumentBuilder<CommandSourceStack> makeBuilder() {
		LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal("music_disc");

		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			// TODO: is this possible?
			throw new RuntimeException("Could not connect to minecraft for tape command init");
		}

		// Enumerate legal jukebox songs & add each to the command
		RegistryAccess access = connection.registryAccess();
		HolderLookup.RegistryLookup<JukeboxSong> jukeboxRegistry = access.lookupOrThrow(Registries.JUKEBOX_SONG);
		for (var holder : jukeboxRegistry.listElements().toList()) {
			ResourceKey<JukeboxSong> key = holder.key();
			String discLocation = key.location().toString();
			JukeboxSong song = holder.value();
			songs.put(discLocation, song);
			builder = builder.then(Commands.literal(discLocation)
					.executes(command -> makeTape(command, discLocation)));
			// key.location(), song.description().getString(), song.lengthInSeconds()
		}

		return Commands.literal("rcdebug")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("tapes")
						.then(Commands.literal("give")
								.then(builder))
						.then(Commands.literal("upload_all_discs").executes(DebugGiveDiscTapeCommand::uploadAllDiscs)));
	}

	private static int uploadAllDiscs(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		List<String> failedLocations = new ArrayList<>();
		for (String discLocation : songs.keySet()) {
			try {
				makeTape(ctx, discLocation);
			} catch (CommandSyntaxException ignored) {
				failedLocations.add(discLocation);
			}
		}

		final int numSuccesses = songs.size() - failedLocations.size();
		List<String> lines = new ArrayList<>();
		for (String discLocation : failedLocations) {
			lines.add("Failed to start upload of " + songs.get(discLocation).description());
		}
		lines.add("Total: " + numSuccesses + "/" + songs.size() + " disc uploads started successfully");

		source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int makeTape(CommandContext<CommandSourceStack> ctx, String discLocation) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			throw new SimpleCommandExceptionType(Component.literal("Not connected")).create();
		}

		JukeboxSong song = songs.get(discLocation);
		ResourceLocation eventId = song.soundEvent().value().getLocation();  // TODO: switch to .location() on newer versions

		WeighedSoundEvents events = mc.getSoundManager().getSoundEvent(eventId);
		if (events == null) {
			throw new SimpleCommandExceptionType(Component.literal("No sounds.json entry for " + eventId)).create();
		}
		Sound sound = events.getSound(RandomSource.create());
		ResourceLocation oggLocation = sound.getPath();

		Resource resource;
		try {
			resource = mc.getResourceManager().getResourceOrThrow(oggLocation);
		} catch (IOException e) {
			throw new SimpleCommandExceptionType(Component.literal("Missing " + oggLocation)).create();
		}

		// Decoding and sending ~1000 packets would stall the render thread, so do it in the background
		CompletableFuture.runAsync(
			() -> {
				short[] audio;
				try (InputStream in = resource.open()) {
					audio = OggDecoder.decode(new BufferedInputStream(in));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}

				int total = audio.length;
				int index = 0;
				for (int off = 0; off < total; off += CHUNK_SAMPLES, index++) {
					int n = Math.min(CHUNK_SAMPLES, total - off);
					ByteBuffer byteBuffer = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
					byteBuffer.asShortBuffer().put(audio, off, n);
					SAudioUploadPacket.send(song.description().getString(), total, index, byteBuffer);
				}
			},
			Util.backgroundExecutor()
		).whenComplete((v, err) -> mc.execute(() ->
				source.sendSuccess(() -> err == null
						? Component.literal("Tape upload sent")
						: Component.literal("Tape upload failed: " + err.getMessage()), false)));

		source.sendSuccess(() -> Component.literal("Uploading music disc file from " + discLocation + " (" + song.description().getString() + ") to server..."), false);
		return Command.SINGLE_SUCCESS;
	}

//    private static boolean isPrivileged(@NotNull ServerPlayer player) {
//        if (player.serverLevel().getServer().isSingleplayer()) {
//            return true;
//        }
//        // https://minecraft.wiki/Permission_level
//        return player.hasPermissions(3);
//    }
}
