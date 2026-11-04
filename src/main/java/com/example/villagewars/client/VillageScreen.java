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

/** Das Dorf-Menü. Anführer sehen Befehle für ihr eigenes Dorf. */
public class VillageScreen extends Screen {
    private static final int W = 320, H = 252;
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
        int y = top + 148;

        if (info.mine) {
            add(x1, y, w, 20, "Hand-Item einlagern", Actions.GIFT, 0, true);
            add(x2, y, w, 20, "Vorräte abholen", Actions.COLLECT, 0, true);

            int ny = top + 80;
            for (int i = 0; i < info.neighbours.size(); i++) {
                Net.Neighbour n = info.neighbours.get(i);
                add(left + W - 112, ny - 2, 50, 11, "Krieg", Actions.DECLARE, i, !n.war);
                add(left + W - 60, ny - 2, 50, 11, "Frieden", Actions.PEACE, i, n.war);
                ny += 12;
            }
        } else {
            boolean canHire = info.rep >= 20;
            String claim = !info.hasOwn ? "Anführer werden (gratis)"
                    : "Übernehmen (" + Actions.claimCost(info.population) + " Gold)";
            boolean canClaim = info.owner.isEmpty() && (!info.hasOwn || info.soldiers <= 0 || info.fallen);
            add(x1, y, w, 20, "Hand-Item schenken", Actions.GIFT, 0, true);
            add(x2, y, w, 20, "Söldner stellen x3 (6 Gold)", Actions.SUPPORT, 0, true);
            add(x1, y + 24, w, 20, "Anheuern x1 (3 Smaragde)", Actions.HIRE1, 0, canHire);
            add(x2, y + 24, w, 20, "Anheuern x3 (9 Smaragde)", Actions.HIRE3, 0, canHire);
            add(x1, y + 48, w, 20, claim, Actions.CLAIM, 0, canClaim);
        }
    }

    private void add(int x, int y, int w, int h, String label, int action, int arg, boolean active) {
        Button b = Button.builder(Component.literal(label), btn -> Net.CHANNEL.sendToServer(new Net.ActionMsg(action, arg)))
                .bounds(x, y, w, h).build();
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
        String tag = info.mine ? "DEIN DORF" : (info.owner.isEmpty() ? "" : "Besitzer: " + info.owner);
        if (!tag.isEmpty()) {
            g.drawString(this.font, tag, left + W - 10 - this.font.width(tag), top + 10, info.mine ? 0xFF55FF55 : 0xFFFFDD66);
        }
        g.drawString(this.font, "Einwohner " + info.population + "   Soldaten " + info.soldiers
                + "   Ausrüstung: " + Equipment.TIER_NAMES[Math.min(3, info.tier)], tx, top + 26, 0xFFDDDDDD);
        g.drawString(this.font, "Nahrung " + info.food + "   Holz " + info.wood + "   Eisen " + info.iron + "   Gold " + info.gold,
                tx, top + 38, 0xFFDDDDDD);
        if (!info.mine) {
            g.drawString(this.font, "Dein Ruf: " + info.rep + " (" + describe(info.rep) + ")", tx, top + 50, 0xFF99DDFF);
        }
        if (info.fallen) g.drawString(this.font, "BESIEGT", left + W - 10 - this.font.width("BESIEGT"), top + 50, 0xFFFFAA00);
        else if (info.war) g.drawString(this.font, "IM KRIEG", left + W - 10 - this.font.width("IM KRIEG"), top + 50, 0xFFFF5555);

        g.drawString(this.font, "Nachbardörfer:", tx, top + 68, 0xFFAAAAAA);
        if (info.neighbours.isEmpty()) {
            g.drawString(this.font, "keine in Reichweite", tx + 4, top + 80, 0xFF777777);
        }
        int ny = top + 80;
        for (Net.Neighbour n : info.neighbours) {
            int c = n.rel <= -60 ? 0xFFFF5555 : n.rel < 0 ? 0xFFFFAA00 : 0xFF55FF55;
            String line = n.name + "  " + (n.rel > 0 ? "+" : "") + n.rel + "  " + n.dist + "m" + (n.war ? " !" : "");
            g.drawString(this.font, line, tx + 4, ny, c);
            ny += 12;
        }

         String hint = info.mine ? "Krieg erklären, dann selbst mit deinen Soldaten hinziehen."
                : (!info.hasOwn ? "Du führst noch kein Dorf: übernimm dieses gratis."
                : (info.owner.isEmpty() && info.soldiers > 0 && !info.fallen ? "Nur Dörfer ohne Krieger sind käuflich: erst besiegen." : ""));
        if (!hint.isEmpty()) g.drawString(this.font, hint, tx, top + 134, 0xFF999999);

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
