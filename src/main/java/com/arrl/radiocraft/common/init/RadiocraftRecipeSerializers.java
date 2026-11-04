package com.arrl.radiocraft.common.init;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.crafting.AprsModuleInstallRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class RadiocraftRecipeSerializers {

	public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Radiocraft.MOD_ID);

	/** A VHF handheld plus an APRS module: the handheld with the module installed. */
	public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<AprsModuleInstallRecipe>> APRS_MODULE_INSTALL = RECIPE_SERIALIZERS.register(
			"aprs_module_install", () -> new SimpleCraftingRecipeSerializer<>(AprsModuleInstallRecipe::new));
}
