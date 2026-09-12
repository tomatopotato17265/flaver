package tomatopotato.flaver.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import tomatopotato.flaver.client.gui.FriendsScreen;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin() {
		super(null);
	}

	@ModifyArg(
			method = "init",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/CommonButtons;friends(ILnet/minecraft/client/gui/components/Button$OnPress;Z)Lnet/minecraft/client/gui/components/FriendsButton;"
			),
			index = 1
	)
	private Button.OnPress flaver$redirectFriendsButton(Button.OnPress original) {
		TitleScreen self = (TitleScreen) (Object) this;
		return button -> this.minecraft.gui.setScreen(new FriendsScreen(self));
	}
}
