package tomatopotato.flaver.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class FriendsScreen extends Screen {
	private final Screen parent;

	public FriendsScreen(Screen parent) {
		super(Component.literal("Friends"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		FocusableTextWidget textWidget = this.addRenderableWidget(
				FocusableTextWidget.builder(this.title, this.font, 12)
						.textWidth(this.font.width(this.title))
						.build());
		textWidget.setPosition(this.width / 2 - textWidget.getWidth() / 2, this.height / 4);

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, this.height - 40, 200, 20)
				.build());
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
