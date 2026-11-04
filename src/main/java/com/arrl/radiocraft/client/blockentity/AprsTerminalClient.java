package com.arrl.radiocraft.client.blockentity;

import com.arrl.radiocraft.common.blockentities.AprsTerminalBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The H19's running noise (S0lver's {@code h19_whirr_2}, 2026-11-04): looped at the terminal for as long as it has power,
 * screen open or not, spinning up as it comes on and running down as it goes off.
 */
public final class AprsTerminalClient {

	/** Heard from the room, and louder with its screen open (you are sat at it). */
	private static final float VOLUME = 0.175F, VOLUME_AT_SCREEN = 0.35F;
	private static final Map<AprsTerminalBlockEntity, Running> PLAYING = new WeakHashMap<>();

	private AprsTerminalClient() {
	}

	/** Each client tick of a terminal: starts its noise when it is on and not already playing (or dropped by the engine). */
	public static void tick(AprsTerminalBlockEntity terminal) {
		if(!terminal.isOn())
			return;
		Running running = PLAYING.get(terminal);
		var sounds = Minecraft.getInstance().getSoundManager();
		if(running == null || running.isStopped() || !sounds.isActive(running)) {
			if(running != null && !running.isStopped())
				running.stopNow(); // Dropped by the engine: a fresh one.
			running = new Running(terminal);
			PLAYING.put(terminal, running);
			sounds.play(running);
		}
	}

	private static final class Running extends AbstractTickableSoundInstance {
		private final AprsTerminalBlockEntity terminal;

		Running(AprsTerminalBlockEntity terminal) {
			super(RadiocraftSoundEvents.H19_RUNNING.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.terminal = terminal;
			looping = true;
			delay = 0;
			volume = 0.0F;
			x = terminal.getBlockPos().getX() + 0.5D;
			y = terminal.getBlockPos().getY() + 0.5D;
			z = terminal.getBlockPos().getZ() + 0.5D;
		}

		@Override
		public void tick() {
			if(terminal.isRemoved()) {
				stop();
				return;
			}
			if(terminal.isOn()) {
				boolean atScreen = Minecraft.getInstance().screen instanceof com.arrl.radiocraft.client.screens.AprsTerminalScreen screen
						&& screen.getMenu().blockEntity == terminal;
				float target = atScreen ? VOLUME_AT_SCREEN : VOLUME;
				volume = volume < target ? Math.min(target, volume + VOLUME / 20.0F) // Spins up over a second,
						: Math.max(target, volume - VOLUME / 10.0F); // and eases back when the screen closes.
			}
			else if((volume -= VOLUME / 30.0F) <= 0.0F) { // Runs down over a second and a half.
				volume = 0.0F;
				stop();
			}
		}

		void stopNow() {
			stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
