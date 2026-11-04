package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;

/**
 * A block that takes the DTMF keys a radio copies, when it touches the radio or is on its data wire: the DTMF decoder,
 * the repeater autopatch. While one is linked the radio looks for key tones in what it hears
 * ({@link com.arrl.radiocraft.common.radio.voice.DtmfListener}).
 */
public interface DtmfKeyReceiver {

	/** A key {@code radio} copied. Server thread. */
	void receiveDtmf(RadioBlockEntity radio, char key);
}
