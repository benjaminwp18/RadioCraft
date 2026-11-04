# RadioCraft alpha 1.11

Minecraft 1.21.1, NeoForge. Needs Simple Voice Chat and GeckoLib;
[Another Weather Mod](https://github.com/RadiocraftARRL/AnotherWeatherMod) is optional.
Everything below is explained in the in-game guide and on the [wiki](https://radiocraft.bigtimeradio.us/wiki/).

## 1.11.0

### New

- **Handheld memory channels**: hold MW to store, tap MW to switch; channels can have 7-letter names.
- **APRS module** for the handheld: nearby stations with a compass, messages, and a position beacon.
- **DTMF**: # on the handheld sends tones; new DTMF Encoder and Decoder blocks (codes to redstone).
- **Repeater Autopatch Controller**: phone calls through a repeater by DTMF.
- **Field phone** (party line on data wire), **Phone PA**, and linked PABXes with All-Call.
- **Radio Moderation Computer** (admins only): live listening (to a player, or to everything on a frequency),
  recording archive, map, and who-hears-whom, with sortable lists and a Freeze for the live view. It stays where you
  left it for a while and shows its screen on its monitor. Recording is off by default (server config).
- **Scroll tuning**: sneak and scroll while looking at a radio (can be turned off in the client config).
- Dyed data wire, and new sounds (H19, power buttons, FM static).

### Changes

- **Handheld**: new LCD screen, and a 3D model in hand, on the belt and in the charger, with a live screen.
- **Desk charger**: the radio keeps working in it, it takes power wire, sneak-use docks the radio, charging is slower.
- Radios can't hear while transmitting (repeaters excepted).
- 3D hand microphone.
- PABX numbering and naming reworked; phones draw power through the PABX.
- More realistic SSB mixing and static; nearby transmitters desense receivers.
- WEFAX bulletins are real fax audio; local HF works at half range in a blackout.
- Signals pass through wood, glass, wool and leaves.
- Better antenna analyzer readout, `/solarweather` controls, tooltips on everything.
- The Config button in the mod list is back, with readable names for every setting.
- The handheld volume knob turns down more naturally; the direction finder's beep is quieter.

### Fixes

- A shorted power wire no longer keeps burning after it is replaced.
- Antenna analyzer crash, tower beacons not blinking, desk charger tooltip.
