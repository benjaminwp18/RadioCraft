package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.blockentities.ModerationComputerBlockEntity;
import com.arrl.radiocraft.common.blocks.ModerationComputerBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.menus.ModerationComputerMenu;
import com.arrl.radiocraft.common.moderation.ComputerRequests;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Radio Moderation Computer's lock (2026-11-04): its owner and the admins they add may open it, nobody else; a
 * player who is not an operator cannot claim one by placing it; {@code /radio moderation unlock} clears the lock; and
 * the server side of its screen answers the owner, refuses owner-only changes to an admin, and closes on a stranger.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class ModerationComputerGameTests {

	private static final BlockPos COMPUTER = new BlockPos(4, 2, 4);

	private static ServerPlayer player(GameTestHelper helper, String name) {
		ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(("gametest-moderation-" + name).getBytes()), name));
		BlockPos at = helper.absolutePos(COMPUTER.south(2));
		player.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D); // Within reach of the computer.
		return player;
	}

	private static ModerationComputerBlockEntity computer(GameTestHelper helper) {
		helper.setBlock(COMPUTER, RadiocraftBlocks.MODERATION_COMPUTER.get());
		return GameTestUtil.blockEntity(helper, COMPUTER, ModerationComputerBlockEntity.class);
	}

	/** The owner opens it; a stranger is refused; an added admin can, and cannot once removed. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public static void ownerAndAdminsOnly(GameTestHelper helper) {
		ModerationComputerBlockEntity computer = computer(helper);
		ServerPlayer owner = player(helper, "owner"), stranger = player(helper, "stranger");
		computer.setOwner(owner.getUUID(), owner.getGameProfile().getName());
		helper.assertTrue(computer.canOpen(owner), "the owner cannot open it");
		helper.assertFalse(computer.canOpen(stranger), "a stranger can open it");
		helper.assertFalse(ModerationComputerBlock.open(stranger, computer), "it opened for a stranger");
		helper.assertTrue(computer.addAdmin(stranger.getUUID(), "stranger"), "the admin was not added");
		helper.assertTrue(computer.canOpen(stranger), "an added admin cannot open it");
		helper.assertFalse(computer.addAdmin(owner.getUUID(), "owner"), "the owner was added as an admin");
		helper.assertTrue(computer.removeAdmin(stranger.getUUID()) && !computer.canOpen(stranger), "a removed admin can still open it");
		helper.succeed();
	}

	/** Handing it over makes the new owner its owner and keeps the old one on as an admin; the lock survives a save. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public static void handOverAndSave(GameTestHelper helper) {
		ModerationComputerBlockEntity computer = computer(helper);
		ServerPlayer owner = player(helper, "first-owner"), next = player(helper, "next-owner");
		computer.setOwner(owner.getUUID(), "first-owner");
		computer.addAdmin(next.getUUID(), "next-owner");
		computer.transferTo(next.getUUID(), "next-owner");
		helper.assertTrue(computer.isOwner(next) && !computer.isOwner(owner), "ownership did not move");
		helper.assertTrue(computer.getAdmins().containsKey(owner.getUUID()) && !computer.getAdmins().containsKey(next.getUUID()), "admins after a hand over: " + computer.getAdmins());
		CompoundTag saved = computer.saveWithoutMetadata(helper.getLevel().registryAccess());
		ModerationComputerBlockEntity copy = new ModerationComputerBlockEntity(computer.getBlockPos(), computer.getBlockState());
		copy.loadWithComponents(saved, helper.getLevel().registryAccess());
		helper.assertTrue(copy.isOwner(next) && copy.canOpen(owner) && "next-owner".equals(copy.getOwnerName()), "the lock did not survive a save");
		helper.succeed();
	}

	/** A player who is not an operator places one and does not own it, and cannot claim it by using it. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public static void nonOperatorCannotClaim(GameTestHelper helper) {
		ModerationComputerBlockEntity computer = computer(helper);
		ServerPlayer placer = player(helper, "placer");
		RadiocraftBlocks.MODERATION_COMPUTER.get().setPlacedBy(helper.getLevel(), computer.getBlockPos(), computer.getBlockState(), placer, ItemStack.EMPTY);
		helper.assertTrue(computer.getOwner() == null, "a non-operator owns it: " + computer.getOwnerName());
		helper.assertFalse(ModerationComputerBlock.open(placer, computer), "it opened for a non-operator");
		helper.assertTrue(computer.getOwner() == null, "using it claimed it");
		helper.succeed();
	}

	/** {@code /radio moderation unlock <pos>} clears the owner and the admins. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public static void unlockCommandClearsTheLock(GameTestHelper helper) {
		ModerationComputerBlockEntity computer = computer(helper);
		ServerPlayer owner = player(helper, "gone-owner"), admin = player(helper, "gone-admin");
		computer.setOwner(owner.getUUID(), "gone-owner");
		computer.addAdmin(admin.getUUID(), "gone-admin");
		BlockPos at = helper.absolutePos(COMPUTER);
		var server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(helper.getLevel()).withSuppressedOutput(),
				"radio moderation unlock " + at.getX() + " " + at.getY() + " " + at.getZ());
		helper.assertTrue(computer.getOwner() == null && computer.getAdmins().isEmpty(), "still locked to " + computer.getOwnerName() + " and " + computer.getAdmins());
		helper.assertFalse(computer.canOpen(owner) || computer.canOpen(admin), "the old owner or admin can still open it");
		helper.succeed();
	}

	/**
	 * The screen's requests: the owner gets the access list and the status; an admin is refused an owner-only change;
	 * the owner removes the admin; and a stranger's menu is not valid, so their requests are refused.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public static void requestsCheckTheLock(GameTestHelper helper) {
		ModerationComputerBlockEntity computer = computer(helper);
		ServerPlayer owner = player(helper, "request-owner"), admin = player(helper, "request-admin"), stranger = player(helper, "request-stranger");
		computer.setOwner(owner.getUUID(), "request-owner");
		computer.addAdmin(admin.getUUID(), "request-admin");
		List<ComputerRequests.Reply> replies = new ArrayList<>();

		ComputerRequests.handle(computer, owner, "access", new CompoundTag(), replies::add);
		helper.assertTrue(replies.size() == 1 && replies.getFirst().kind().equals("access") && replies.getFirst().data().getBoolean("isOwner")
				&& replies.getFirst().data().getList("admins", Tag.TAG_COMPOUND).size() == 1, "access answer: " + replies);
		replies.clear();
		ComputerRequests.handle(computer, owner, "status", new CompoundTag(), replies::add);
		helper.assertTrue(replies.size() == 1 && replies.getFirst().kind().equals("status") && replies.getFirst().data().contains("dims"), "status answer: " + replies);

		replies.clear();
		CompoundTag remove = new CompoundTag();
		remove.putString("uuid", admin.getUUID().toString());
		ComputerRequests.handle(computer, admin, "remove_admin", remove, replies::add);
		helper.assertTrue(!replies.isEmpty() && replies.getFirst().kind().equals("error") && computer.canOpen(admin), "an admin changed the admins: " + replies);
		ComputerRequests.handle(computer, owner, "remove_admin", remove, replies::add);
		helper.assertFalse(computer.canOpen(admin), "the owner could not remove the admin");

		ModerationComputerMenu ownerMenu = new ModerationComputerMenu(0, computer), strangerMenu = new ModerationComputerMenu(1, computer);
		helper.assertTrue(ownerMenu.stillValid(owner), "the owner's menu is not valid");
		helper.assertFalse(strangerMenu.stillValid(stranger), "a stranger's menu is valid");
		strangerMenu.handleAction(stranger, "remove_admin", remove); // Refused, and must not throw.
		helper.succeed();
	}
}
