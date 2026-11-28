package net.aspir3.betterbattle.fabric.ui;

import net.aspir3.betterbattle.BetterBattle;
import net.aspir3.betterbattle.fabric.compat.Compat;
import net.aspir3.betterbattle.sync.ApiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Voice: type your teammate's or party's names and press Start. Everyone named has to press Voice too,
 * naming the same group, before the Discord channel is made.
 */
public final class VoiceScreen extends Screen {
    private final Screen parent;
    private EditBox names;
    private Button start, leave, open;
    private String typedError;

    public VoiceScreen(Screen parent) {
        super(Component.literal("Voice Chat"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        BetterBattle.get().refreshVoice();
        int cx = width / 2;
        int y = height / 2 - 10;
        names = new EditBox(font, cx - 150, y, 300, 20, Component.literal("Players"));
        names.setHint(Component.literal("Teammate or party names, like Koidu Aspir3").withStyle(ChatFormatting.DARK_GRAY));
        names.setMaxLength(7 * 17);
        names.setResponder(v -> typedError = null);
        addRenderableWidget(names);
        setInitialFocus(names);

        y += 26;
        start = addRenderableWidget(Button.builder(Component.literal("Start Voice"), b -> start()).bounds(cx - 154, y, 100, 20).build());
        open = addRenderableWidget(Button.builder(Component.literal("Open in Discord"), b -> openChannel()).bounds(cx - 50, y, 100, 20).build());
        leave = addRenderableWidget(Button.builder(Component.literal("Leave Voice"), b -> BetterBattle.get().leaveVoice()).bounds(cx + 54, y, 100, 20).build());
        addRenderableWidget(Button.builder(parent == null ? CommonComponents.GUI_DONE : CommonComponents.GUI_BACK, b -> onClose())
            .bounds(cx - 50, height - 28, 100, 20).build());
    }

    private void start() {
        List<String> list = BetterBattle.voiceNames(names.getValue());
        if (list == null || list.isEmpty() || list.size() > 7) {
            typedError = "Type 1 to 7 Minecraft names, separated by spaces.";
            return;
        }
        BetterBattle.get().voice(list);
    }

    private void openChannel() {
        ApiClient.Voice v = BetterBattle.get().voiceState();
        if (v != null && v.ready() && v.url != null) ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(v.url));
    }

    @Override
    public void tick() {
        ApiClient.Voice v = BetterBattle.get().voiceState();
        boolean in = v != null && (v.ready() || v.waiting());
        leave.active = in;
        open.active = v != null && v.ready() && v.url != null;
        start.active = BetterBattle.get().db() != null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2;
        graphics.centeredText(font, title, cx, 15, 0xFFFFFFFF);
        int y = Math.max(34, height / 2 - 76);
        for (String line : new String[] {
            "Talk on the Better Battle Discord with your teammate or party.",
            "Everyone you name has to press Voice too, so nobody ends up in a call with strangers.",
            "Turn on voice chat and link Discord on your account page on the website first.",
        }) {
            graphics.centeredText(font, Component.literal(line).withStyle(ChatFormatting.GRAY), cx, y, 0xFFFFFFFF);
            y += 12;
        }

        graphics.centeredText(font, status(), cx, height / 2 + 46, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("Better Battle isn't made by or affiliated with Hypixel. Follow Hypixel's rules when you use it.")
            .withStyle(ChatFormatting.DARK_GRAY), cx, height - 42, 0xFFFFFFFF);
    }

    private Component status() {
        BetterBattle core = BetterBattle.get();
        if (core.db() == null) return Component.literal("Join Hypixel to use voice.").withStyle(ChatFormatting.GRAY);
        if (typedError != null) return Component.literal(typedError).withStyle(ChatFormatting.RED);
        if (core.voiceProblem() != null) return Component.literal(core.voiceProblem()).withStyle(ChatFormatting.RED);
        ApiClient.Voice v = core.voiceState();
        if (v == null) return Component.literal("Checking...").withStyle(ChatFormatting.DARK_GRAY);
        if (v.ready()) return Component.literal("Ready: ").withStyle(ChatFormatting.GREEN).append(Component.literal(v.label).withStyle(ChatFormatting.WHITE));
        if (v.waiting()) {
            String who = v.waitingFor == null || v.waitingFor.isEmpty() ? "the others" : String.join(", ", v.waitingFor);
            return Component.literal("Waiting for ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(who).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" to press Voice").withStyle(ChatFormatting.YELLOW));
        }
        return Component.literal("You're not in a voice channel.").withStyle(ChatFormatting.GRAY);
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
