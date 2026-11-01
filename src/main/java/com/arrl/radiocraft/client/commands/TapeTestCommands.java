package com.arrl.radiocraft.client.commands;

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
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongs;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class TapeTestCommands {
	private static final int CHUNK_SAMPLES = SAudioUploadPacket.MAX_CHUNK_BYTES / 2;

	public static final LiteralArgumentBuilder<CommandSourceStack> BUILDER =
			Commands.literal("tape")
					.requires(source -> source.hasPermission(3))
					.executes(TapeTestCommands::makeTape);

	private static int makeTape(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
//		CommandSourceStack source = command.getSource();
//		ServerPlayer player = source.getPlayerOrException();
//		var soundCache = Sound.SOUND_LISTER.listMatchingResources();
//		InputStream inputStream = this.resourceManager.open(JukeboxSongs.CREATOR.location());
//		//.getGameProfile();
//		source.sendSuccess(() -> Component.literal("Made tape successfully"), false);  //Component.translatable("message.radiocraft.callsign.claimed", c)
//		//source.sendFailure(Component.translatable("message.radiocraft.callsign." + result.name().toLowerCase(java.util.Locale.ROOT), com.arrl.radiocraft.common.radio.Callsigns.allowedDigits()));
//		return 1;

		CommandSourceStack source = command.getSource();
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			throw new SimpleCommandExceptionType(Component.literal("Not connected")).create();
		}

		// Client command sources have no server, so take the registry access from the connection.
		JukeboxSong song = connection.registryAccess()
				.lookupOrThrow(Registries.JUKEBOX_SONG)
				.getOrThrow(JukeboxSongs.CREATOR)
				.value();
		ResourceLocation eventId = song.soundEvent().value().getLocation();  // TODO: switch to .location() on newer versions

		WeighedSoundEvents events = mc.getSoundManager().getSoundEvent(eventId);
		if (events == null) {
			throw new SimpleCommandExceptionType(Component.literal("No sounds.json entry for " + eventId)).create();
		}
		Sound sound = events.getSound(RandomSource.create());
		// new FileToIdConverter("sounds", ".ogg").idToFile(
		ResourceLocation oggLocation = sound.getPath();

		Resource resource;
		try {
			resource = mc.getResourceManager().getResourceOrThrow(oggLocation);
		} catch (IOException e) {
			throw new SimpleCommandExceptionType(Component.literal("Missing " + oggLocation)).create();
		}

		// Decoding and sending ~1000 packets would stall the render thread, so do it in the background.
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
					SAudioUploadPacket.send(index, total, byteBuffer);
				}
			},
			Util.backgroundExecutor()
		).whenComplete((v, err) -> mc.execute(() ->
				source.sendSuccess(() -> err == null
						? Component.literal("Tape upload sent")
						: Component.literal("Tape upload failed: " + err.getMessage()), false)));

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
