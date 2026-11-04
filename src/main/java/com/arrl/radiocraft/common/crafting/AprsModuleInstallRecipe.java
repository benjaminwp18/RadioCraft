package com.arrl.radiocraft.common.crafting;

import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.arrl.radiocraft.common.init.RadiocraftRecipeSerializers;
import com.arrl.radiocraft.common.items.VHFHandheldItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

/**
 * A VHF handheld (any crystal) and an APRS module, anywhere in the grid: the same handheld, settings, charge and memory
 * channels kept, with the module installed. A handheld that already has one is not a match.
 */
public class AprsModuleInstallRecipe extends CustomRecipe {

	public AprsModuleInstallRecipe(CraftingBookCategory category) {
		super(category);
	}

	@Override
	public boolean matches(@NotNull CraftingInput input, @NotNull Level level) {
		return !find(input).isEmpty();
	}

	@Override
	public @NotNull ItemStack assemble(@NotNull CraftingInput input, HolderLookup.@NotNull Provider registries) {
		ItemStack handheld = find(input);
		if(handheld.isEmpty())
			return ItemStack.EMPTY;
		ItemStack out = handheld.copyWithCount(1);
		out.set(RadiocraftDataComponent.HANDHELD_APRS.value(), HandheldAprs.INSTALLED);
		return out;
	}

	/** The handheld, if the grid holds exactly a handheld without a module and one APRS module; empty otherwise. */
	private static ItemStack find(CraftingInput input) {
		ItemStack handheld = ItemStack.EMPTY;
		int modules = 0;
		for(int i = 0; i < input.size(); i++) {
			ItemStack stack = input.getItem(i);
			if(stack.isEmpty())
				continue;
			if(stack.getItem() instanceof VHFHandheldItem && handheld.isEmpty() && !stack.has(RadiocraftDataComponent.HANDHELD_APRS.value()))
				handheld = stack;
			else if(stack.is(RadiocraftItems.APRS_MODULE.get()))
				modules++;
			else
				return ItemStack.EMPTY;
		}
		return modules == 1 ? handheld : ItemStack.EMPTY;
	}

	@Override
	public boolean canCraftInDimensions(int width, int height) {
		return width * height >= 2;
	}

	@Override
	public @NotNull RecipeSerializer<?> getSerializer() {
		return RadiocraftRecipeSerializers.APRS_MODULE_INSTALL.get();
	}
}
