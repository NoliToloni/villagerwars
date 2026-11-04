package com.example.villagewars.client;

import com.example.villagewars.Actions;
import com.example.villagewars.Equipment;
import com.example.villagewars.Net;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Das Dorf-Menü. */
public class VillageScreen extends Screen {
    private static final int W = 320, H = 238;
    private static final int[] COLORS = {0xFF5555, 0x5555FF, 0x55FF55, 0xFFFF55, 0x55FFFF, 0xFF55FF, 0xFFAA00, 0x00AA00};

    private Net.InfoMsg info;
    private int left, top;

    public VillageScreen(Net.InfoMsg info) {
        super(Component.literal("Dorf"));
        this.info = info;
    }

    public void update(Net.InfoMsg m) {
        this.info = m;
        buildButtons();
    }

    @Override
    protected void init() {
        buildButtons();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void buildButtons() {
        clearWidgets();
        left = (this.width - W) / 2;
        top = (this.height - H) / 2;
        if (!info.found) return;

        int x1 = left + 10, x2 = left + W / 2 + 4, w = W / 2 - 14;
        int y = top + 134;
        boolean canHire = info.rep >= 20;
        String claimCost = info.soldiers <= 0 ? "8" : "24";

        add(x1, y, w, "Hand-Item schenken", Actions.GIFT, true);
        add(x2, y, w, "Söldner stellen x3 (6 Gold)", Actions.SUPPORT, true);
        add(x1, y + 24, w, "Anheuern x1 (3 Smaragde)", Actions.HIRE1, canHire);
        add(x2, y + 24, w, "Anheuern x3 (9 Smaragde)", Actions.HIRE3, canHire);
        add(x1, y + 48, w, "Übernehmen (" + claimCost + " Gold)", Actions.CLAIM, info.owner.isEmpty());
        add(x2, y + 48, w, "Vorräte abholen", Actions.COLLECT, info.mine);
    }

    private void add(int x, int y, int w, String label, int action, boolean active) {
        Button b = Button.builder(Component.literal(label), btn -> Net.CHANNEL.sendToServer(new Net.ActionMsg(action)))
                .bounds(x, y, w, 20).build();
        b.active = active;
        addRenderableWidget(b);
    }

    private static String describe(int r) {
        if (r >= 60) return "Freund";
        if (r >= 20) return "freundlich";
        if (r > -20) return "neutral";
        return "feindlich";
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        // Panel mit Rahmen
        g.fill(left, top, left + W, top + H, 0xEE101018);
        g.fill(left, top, left + W, top + 1, 0xFF8A8A9A);
        g.fill(left, top + H - 1, left + W, top + H, 0xFF8A8A9A);
        g.fill(left, top, left + 1, top + H, 0xFF8A8A9A);
        g.fill(left + W - 1, top, left + W, top + H, 0xFF8A8A9A);

        int tx = left + 10;
        if (!info.found) {
            g.drawString(this.font, "Kein Dorf in der Nähe.", tx, top + 12, 0xFFAAAAAA);
            drawFeedback(g, tx);
            super.render(g, mx, my, pt);
            return;
        }

        int col = 0xFF000000 | COLORS[info.color % COLORS.length];
        g.drawString(this.font, info.name, tx, top + 10, col);
        if (!info.owner.isEmpty()) {
            g.drawString(this.font, "Besitzer: " + info.owner, left + W - 10 - this.font.width("Besitzer: " + info.owner), top + 10, 0xFFFFDD66);
        }
        g.drawString(this.font, "Einwohner " + info.population + "   Soldaten " + info.soldiers
                + "   Ausrüstung: " + Equipment.TIER_NAMES[Math.min(3, info.tier)], tx, top + 26, 0xFFDDDDDD);
        g.drawString(this.font, "Nahrung " + info.food + "   Holz " + info.wood + "   Eisen " + info.iron + "   Gold " + info.gold,
                tx, top + 38, 0xFFDDDDDD);
        g.drawString(this.font, "Dein Ruf: " + info.rep + " (" + describe(info.rep) + ")", tx, top + 50, 0xFF99DDFF);
        if (info.war) g.drawString(this.font, "IM KRIEG", left + W - 10 - this.font.width("IM KRIEG"), top + 50, 0xFFFF5555);

        g.drawString(this.font, "Nachbardörfer:", tx, top + 68, 0xFFAAAAAA);
        if (info.neighbours.isEmpty()) {
            g.drawString(this.font, "keine in Reichweite", tx + 4, top + 80, 0xFF777777);
        }
        int ny = top + 80;
        for (Net.Neighbour n : info.neighbours) {
            int c = n.rel <= -60 ? 0xFFFF5555 : n.rel < 0 ? 0xFFFFAA00 : 0xFF55FF55;
            String line = n.name + "   " + (n.rel > 0 ? "+" : "") + n.rel + "   " + n.dist + " m" + (n.war ? "   KRIEG" : "");
            g.drawString(this.font, line, tx + 4, ny, c);
            ny += 12;
        }

        drawFeedback(g, tx);
        super.render(g, mx, my, pt);
    }

    private void drawFeedback(GuiGraphics g, int tx) {
        if (info.feedback.isEmpty()) return;
        int c = info.ok ? 0xFF55FF55 : 0xFFFF7777;
        List<FormattedCharSequence> lines = this.font.split(Component.literal(info.feedback), W - 20);
        int y = top + H - 12 - Math.min(2, lines.size()) * 10 + 10;
        for (int i = 0; i < lines.size() && i < 2; i++) {
            g.drawString(this.font, lines.get(i), tx, y + i * 10, c);
        }
    }
}
