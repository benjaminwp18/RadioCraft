package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.common.be_networks.network_objects.RadioNetworkObject;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.moderation.FrameFile;
import com.arrl.radiocraft.common.moderation.ModerationHooks;
import com.arrl.radiocraft.common.moderation.ModerationLog;
import com.arrl.radiocraft.common.moderation.TransmissionKind;
import com.arrl.radiocraft.common.moderation.TransmissionQuery;
import com.arrl.radiocraft.common.moderation.TransmissionRecord;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The moderation log (maintainer's design, 2026-11-04): a transmission is logged once at its source with the radios
 * that heard it, data as data, no audio with moderation_metadata_only, and retention deletes whole old files. Recording
 * is off for the rest of the run ({@link GameTestDefaults}), so these batches switch it on for themselves.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class ModerationLogGameTests {

    private static final String BATCH = "moderation_log";
    private static final String META_BATCH = "moderation_log_metadata";
    private static final UUID TALKER = UUID.nameUUIDFromBytes("gametest-moderation-talker".getBytes());
    /** A made-up Opus packet: the log keeps whatever arrived, byte for byte. */
    private static final byte[] OPUS = { (byte)0xF8, 1, 2, 3, 4, 5, 6, 7 };

    @BeforeBatch(batch = BATCH)
    public static void recordOn(ServerLevel level) {
        RadiocraftServerConfig.MODERATION_RECORD_RADIO.set(true);
        RadiocraftServerConfig.MODERATION_METADATA_ONLY.set(false);
    }

    @AfterBatch(batch = BATCH)
    public static void recordOff(ServerLevel level) {
        RadiocraftServerConfig.MODERATION_RECORD_RADIO.set(false);
    }

    @BeforeBatch(batch = META_BATCH)
    public static void metadataOnlyOn(ServerLevel level) {
        RadiocraftServerConfig.MODERATION_RECORD_RADIO.set(true);
        RadiocraftServerConfig.MODERATION_METADATA_ONLY.set(true);
    }

    @AfterBatch(batch = META_BATCH)
    public static void metadataOnlyOff(ServerLevel level) {
        RadiocraftServerConfig.MODERATION_RECORD_RADIO.set(false);
        RadiocraftServerConfig.MODERATION_METADATA_ONLY.set(false);
    }

    /** When each test started (epoch ms): plots are reused between batches, so a record must be newer than its test. */
    private static final java.util.Map<GameTestHelper, Long> STARTED = new java.util.concurrent.ConcurrentHashMap<>();

    private static void start(GameTestHelper helper) {
        STARTED.put(helper, System.currentTimeMillis());
    }

    private static RadioBlockEntity radio(GameTestHelper helper, BlockPos pos) {
        return GameTestUtil.blockEntity(helper, pos, RadioBlockEntity.class);
    }

    private static ModerationLog log(GameTestHelper helper) {
        ModerationLog log = ModerationLog.get(helper.getLevel().getServer());
        if(log == null)
            helper.fail("No moderation log on the GameTest server");
        return log;
    }

    /** A VHF base station with a dummy load touching it (reaches about 10 blocks). */
    private static void station(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, RadiocraftBlocks.VHF_BASE_STATION.get());
        helper.setBlock(pos.north(), RadiocraftBlocks.DUMMY_LOAD.get());
    }

    private static boolean ready(GameTestHelper helper, BlockPos... radios) {
        for(BlockPos pos : radios)
            if(GameTestUtil.networkObject(helper, pos, RadioNetworkObject.class).getAntennas().size() != 1)
                return false;
        return true;
    }

    private static void powerOn(GameTestHelper helper, BlockPos pos) {
        if(!GameTestUtil.networkObject(helper, pos, RadioNetworkObject.class).isPowered)
            radio(helper, pos).toggle();
    }

    /** {@code speaker} sends a frame as if its talker's microphone packet had just arrived (the voice chat's own path). */
    private static void talk(GameTestHelper helper, BlockPos speaker) {
        RadioBlockEntity radio = radio(helper, speaker);
        radio.setPTTDown(true);
        ModerationHooks.micBegin(TALKER, OPUS);
        try {
            radio.acceptVoicePacket(() -> helper.getLevel(), new short[960], TALKER);
        }
        finally {
            ModerationHooks.micEnd();
        }
        radio.setPTTDown(false);
    }

    /** The closed transmission of {@code kind} sent from {@code radio} since its test started, or null while there is none yet. */
    private static TransmissionRecord sentFrom(GameTestHelper helper, BlockPos radio, TransmissionKind kind) {
        TransmissionQuery.Page page = log(helper).query(TransmissionQuery.all().withKinds(kind).withTime(STARTED.getOrDefault(helper, 0L), Long.MAX_VALUE)
                .withRadio(helper.getLevel().dimension().location().toString(), helper.absolutePos(radio)).withPage(0, 10));
        for(TransmissionRecord record : page.items())
            if(helper.absolutePos(radio).equals(record.radioPos()))
                return record;
        return null;
    }

    private static boolean heardAt(TransmissionRecord record, BlockPos absolute) {
        for(TransmissionRecord.Hearer hearer : record.hearers())
            if(absolute.equals(hearer.pos()))
                return true;
        return false;
    }

    /**
     * A player's voice over one base station, heard by another: one record at the source, the other station as its
     * hearer, the Opus packets kept as they arrived (playback gives them back), and flagging and export work on it.
     */
    @GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 2400, batch = BATCH)
    public static void voiceIsLoggedOnceWithItsHearer(GameTestHelper helper) {
        start(helper);
        BlockPos a = new BlockPos(3, 2, 6), b = new BlockPos(9, 2, 6);
        station(helper, a);
        station(helper, b);
        AtomicReference<CompletableFuture<ModerationLog.Playback>> playback = new AtomicReference<>();
        AtomicReference<CompletableFuture<List<Path>>> export = new AtomicReference<>();
        AtomicReference<CompletableFuture<TransmissionRecord>> flag = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ready(helper, a, b), "The stations do not see their dummy loads yet"))
                .thenExecute(() -> {
                    powerOn(helper, a);
                    powerOn(helper, b);
                })
                .thenIdle(2)
                .thenExecute(() -> helper.assertTrue(ModerationHooks.recording(), "Radio recording is not on"))
                .thenExecute(() -> talk(helper, a))
                .thenIdle(1)
                .thenExecute(() -> talk(helper, a))
                .thenIdle(1)
                .thenExecute(() -> talk(helper, a))
                .thenExecute(() -> helper.assertTrue(radio(helper, b).getLastSignalGameTime() >= 0, "B heard nothing: the test proves nothing"))
                .thenWaitUntil(() -> helper.assertTrue(sentFrom(helper, a, TransmissionKind.PLAYER_VOICE) != null, "No voice transmission from A logged yet"))
                .thenExecute(() -> {
                    TransmissionRecord record = sentFrom(helper, a, TransmissionKind.PLAYER_VOICE);
                    helper.assertTrue(record.audioFrames() == 3, "Expected the 3 frames kept, found " + record.audioFrames());
                    helper.assertTrue(heardAt(record, helper.absolutePos(b)), "B is not among the hearers: " + record.hearers());
                    helper.assertFalse(heardAt(record, helper.absolutePos(a)), "A heard itself");
                    helper.assertTrue(record.talkers().size() == 1 && TALKER.equals(record.talkers().getFirst().uuid()), "Talker: " + record.talkers());
                    playback.set(log(helper).playback(record.id(), null, "gametest"));
                })
                .thenWaitUntil(() -> helper.assertTrue(playback.get().isDone(), "Playback not read yet"))
                .thenExecute(() -> {
                    List<FrameFile.Frame> frames = playback.get().join().frames();
                    helper.assertTrue(frames.size() == 3 && Arrays.equals(frames.getFirst().data(), OPUS), "Playback frames: " + frames.size());
                    TransmissionRecord record = sentFrom(helper, a, TransmissionKind.PLAYER_VOICE);
                    export.set(log(helper).export(record.id(), null, "gametest"));
                    flag.set(log(helper).flag(record.id(), null, "gametest"));
                })
                .thenWaitUntil(() -> helper.assertTrue(export.get().isDone() && flag.get().isDone(), "Export and flag not done yet"))
                .thenExecute(() -> {
                    List<Path> files = export.get().join();
                    helper.assertTrue(files.size() == 1 && Files.isRegularFile(files.getFirst()), "Export wrote " + files);
                    TransmissionRecord flagged = flag.get().join();
                    helper.assertTrue(flagged != null && flagged.flagged(), "Not flagged");
                    Path kept = log(helper).folder().resolve("kept").resolve(flagged.id() + ".rcma");
                    helper.assertTrue(Files.isRegularFile(kept), "No kept copy at " + kept);
                    helper.assertTrue(log(helper).auditLog(0L, Long.MAX_VALUE, 50).stream().anyMatch(e -> e.target() == flagged.id() && e.action().equals("play")),
                            "Playback is not in the audit log");
                    log(helper).unflag(flagged.id(), null, "gametest");
                })
                .thenSucceed();
    }

    /** A packet from a base station is logged as data (its saved record), not as the audio of its tones. */
    @GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 2400, batch = BATCH)
    public static void dataIsLoggedAsData(GameTestHelper helper) {
        start(helper);
        BlockPos a = new BlockPos(3, 2, 9), b = new BlockPos(9, 2, 9);
        station(helper, a);
        station(helper, b);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ready(helper, a, b), "The stations do not see their dummy loads yet"))
                .thenExecute(() -> {
                    powerOn(helper, a);
                    powerOn(helper, b);
                })
                .thenIdle(2)
                .thenExecute(() -> helper.assertTrue(radio(helper, a).sendData(TncPacket.message(4242L, "N0CALL", "ALL", "moderation test")), "A did not take the packet"))
                .thenWaitUntil(() -> helper.assertTrue(sentFrom(helper, a, TransmissionKind.DATA) != null, "No data transmission from A logged yet"))
                .thenExecute(() -> {
                    TransmissionRecord record = sentFrom(helper, a, TransmissionKind.DATA);
                    helper.assertTrue(!record.packets().isEmpty() && record.packets().getFirst().kind().equals("MESSAGE"), "Packets: " + record.packets());
                    helper.assertTrue(record.packets().getFirst().snbt().contains("moderation test"), "The packet's record: " + record.packets().getFirst().snbt());
                    helper.assertTrue(record.audio().isEmpty(), "Data kept audio");
                    helper.assertTrue(heardAt(record, helper.absolutePos(b)), "B is not among the hearers: " + record.hearers());
                    helper.assertTrue(sentFrom(helper, a, TransmissionKind.BLOCK_AUDIO) == null, "The packet's tones were logged as audio");
                })
                .thenSucceed();
    }

    /** Retention deletes whole hour files and index days past the window. */
    @GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 1200, batch = BATCH)
    public static void retentionDeletesOldFiles(GameTestHelper helper) {
        Path root = log(helper).folder();
        Path oldAudio = root.resolve("audio").resolve("2020-01-01").resolve("05.rcma");
        Path oldIndex = root.resolve("index").resolve("2020-01-01.jsonl");
        AtomicReference<CompletableFuture<Integer>> purge = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ModerationHooks.recording(), "Radio recording is not on"))
                .thenExecute(() -> purge.set(log(helper).purge())) // Waits out the run the log makes as it starts, so the next one finds the old files.
                .thenWaitUntil(() -> helper.assertTrue(purge.get().isDone(), "The first retention run is not done yet"))
                .thenExecute(() -> {
                    try {
                        Files.createDirectories(oldAudio.getParent());
                        Files.write(oldAudio, FrameFile.header(1_577_854_800_000L));
                        Files.createDirectories(oldIndex.getParent());
                        Files.writeString(oldIndex, "");
                    }
                    catch(java.io.IOException e) {
                        helper.fail("Could not make the old files: " + e.getMessage());
                    }
                })
                .thenExecute(() -> purge.set(log(helper).purge()))
                .thenWaitUntil(() -> helper.assertTrue(purge.get().isDone(), "Retention has not run yet"))
                .thenExecute(() -> {
                    helper.assertFalse(Files.exists(oldAudio), "The 2020 hour of audio is still there");
                    helper.assertFalse(Files.exists(oldAudio.getParent()), "The emptied 2020 day folder is still there");
                    helper.assertFalse(Files.exists(oldIndex), "The 2020 index day is still there");
                    helper.assertTrue(purge.get().join() >= 2, "Deleted " + purge.get().join());
                })
                .thenSucceed();
    }

    /** With moderation_metadata_only, a voice transmission is logged with its hearers but no audio. */
    @GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 2400, batch = META_BATCH)
    public static void metadataOnlyKeepsNoAudio(GameTestHelper helper) {
        start(helper);
        BlockPos a = new BlockPos(3, 2, 6), b = new BlockPos(9, 2, 6);
        station(helper, a);
        station(helper, b);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ready(helper, a, b), "The stations do not see their dummy loads yet"))
                .thenExecute(() -> {
                    powerOn(helper, a);
                    powerOn(helper, b);
                })
                .thenIdle(2)
                .thenExecute(() -> talk(helper, a))
                .thenIdle(1)
                .thenExecute(() -> talk(helper, a))
                .thenWaitUntil(() -> helper.assertTrue(sentFrom(helper, a, TransmissionKind.PLAYER_VOICE) != null, "No voice transmission from A logged yet"))
                .thenExecute(() -> {
                    TransmissionRecord record = sentFrom(helper, a, TransmissionKind.PLAYER_VOICE);
                    helper.assertTrue(record.audioFrames() == 0 && record.audio().isEmpty(), "Audio was kept: " + record.audioFrames() + " frames");
                    helper.assertTrue(heardAt(record, helper.absolutePos(b)), "B is not among the hearers: " + record.hearers());
                })
                .thenSucceed();
    }
}
