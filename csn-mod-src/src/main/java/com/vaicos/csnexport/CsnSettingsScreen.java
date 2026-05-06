package com.vaicos.csnexport;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CsnSettingsScreen extends Screen {

    private final Screen parent;

    private TextFieldWidget webhookField;
    private TextFieldWidget codeField;
    private TextFieldWidget nameField;

    private final List<String[]> aliasList = new ArrayList<>();
    private int selectedAlias = -1;
    private int scrollOffset = 0;

    private static final int LIST_X      = 20;
    private static final int LIST_Y      = 90;
    private static final int LIST_WIDTH  = 340;
    private static final int ROW_HEIGHT  = 14;
    private static final int VISIBLE_ROWS = 7;

    public CsnSettingsScreen(Screen parent) {
        super(Text.literal("CSN Export Settings"));
        this.parent = parent;
        for (Map.Entry<String, String> e : CsnExportClient.brewAliases.entrySet())
            aliasList.add(new String[]{e.getKey(), e.getValue()});
    }

    @Override
    protected void init() {
        int cx = width / 2;

        webhookField = new TextFieldWidget(textRenderer, cx - 150, 40, 300, 18,
                Text.literal("Webhook URL"));
        webhookField.setMaxLength(512);
        webhookField.setText(CsnExportClient.discordWebhook);
        webhookField.setPlaceholder(Text.literal("https://discord.com/api/webhooks/..."));
        addDrawableChild(webhookField);

        int fieldY = LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 12;

        codeField = new TextFieldWidget(textRenderer, LIST_X, fieldY, 155, 16,
                Text.literal("Code"));
        codeField.setMaxLength(64);
        codeField.setPlaceholder(Text.literal("Potion#32L"));
        addDrawableChild(codeField);

        nameField = new TextFieldWidget(textRenderer, LIST_X + 163, fieldY, 177, 16,
                Text.literal("Name"));
        nameField.setMaxLength(64);
        nameField.setPlaceholder(Text.literal("Speed II"));
        addDrawableChild(nameField);

        int btnY = fieldY + 22;

        addDrawableChild(ButtonWidget.builder(Text.literal("Add / Update"), btn -> addOrUpdate())
                .dimensions(LIST_X, btnY, 100, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), btn -> removeSelected())
                .dimensions(LIST_X + 108, btnY, 80, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear All"), btn -> {
            aliasList.clear();
            selectedAlias = -1;
            scrollOffset  = 0;
        }).dimensions(LIST_X + 196, btnY, 80, 18).build());

        int bottomY = height - 28;

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), btn -> save())
                .dimensions(cx - 82, bottomY, 78, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> cancel())
                .dimensions(cx + 4, bottomY, 78, 20).build());
    }

    private void addOrUpdate() {
        String code = codeField.getText().strip();
        String name = nameField.getText().strip();
        if (code.isEmpty() || name.isEmpty()) return;

        for (String[] row : aliasList) {
            if (row[0].equals(code)) {
                row[1] = name;
                return;
            }
        }
        aliasList.add(new String[]{code, name});
    }

    private void removeSelected() {
        if (selectedAlias < 0 || selectedAlias >= aliasList.size()) return;
        aliasList.remove(selectedAlias);
        selectedAlias = Math.min(selectedAlias, aliasList.size() - 1);
        codeField.setText("");
        nameField.setText("");
    }

    private void save() {
        CsnExportClient.discordWebhook = webhookField.getText().strip();
        CsnExportClient.brewAliases.clear();
        for (String[] row : aliasList)
            CsnExportClient.brewAliases.put(row[0], row[1]);
        CsnExportClient.saveConfig();
        MinecraftClient.getInstance().setScreen(parent);
    }

    private void cancel() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int relY = (int) mouseY - LIST_Y;
        if (mouseX >= LIST_X && mouseX <= LIST_X + LIST_WIDTH && relY >= 0) {
            int row = relY / ROW_HEIGHT;
            int idx = row + scrollOffset;
            if (idx < aliasList.size()) {
                selectedAlias = idx;
                codeField.setText(aliasList.get(idx)[0]);
                nameField.setText(aliasList.get(idx)[1]);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) verticalAmount,
                Math.max(0, aliasList.size() - VISIBLE_ROWS)));
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);

        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Discord Webhook URL:"), width / 2 - 150, 30, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Tip: Discord channel → Edit Channel → Integrations → Webhooks → New Webhook → Copy URL"),
                width / 2 - 150, 63, 0x888888);

        ctx.drawTextWithShadow(textRenderer, Text.literal("Brew Aliases  (" + aliasList.size() + "):"),
                LIST_X, LIST_Y - 12, 0xAAAAAA);

        ctx.fill(LIST_X - 1, LIST_Y - 1,
                LIST_X + LIST_WIDTH + 1, LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 1, 0xFF444444);

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = i + scrollOffset;
            if (idx >= aliasList.size()) break;

            int rowY = LIST_Y + i * ROW_HEIGHT;
            boolean sel = idx == selectedAlias;

            if (sel) ctx.fill(LIST_X, rowY, LIST_X + LIST_WIDTH, rowY + ROW_HEIGHT - 1, 0xFF335588);

            String label = aliasList.get(idx)[0] + "  →  " + aliasList.get(idx)[1];
            if (label.length() > 52) label = label.substring(0, 51) + "…";
            ctx.drawTextWithShadow(textRenderer, Text.literal(label), LIST_X + 3, rowY + 2,
                    sel ? 0xFFFFDD : 0xDDDDDD);
        }

        if (aliasList.size() > VISIBLE_ROWS) {
            int bar = LIST_Y + (scrollOffset * VISIBLE_ROWS * ROW_HEIGHT) / aliasList.size();
            ctx.fill(LIST_X + LIST_WIDTH + 2, bar, LIST_X + LIST_WIDTH + 5,
                    bar + 12, 0xFF888888);
        }

        int fieldY = LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 12;
        ctx.drawTextWithShadow(textRenderer, Text.literal("Code:"), LIST_X, fieldY - 9, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Name:"), LIST_X + 163, fieldY - 9, 0xAAAAAA);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
