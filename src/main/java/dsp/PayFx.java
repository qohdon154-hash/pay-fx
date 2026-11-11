package dsp;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import org.joml.Quaternionf;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class PayFx implements ClientModInitializer {
    // "Steve paid you $1.5M" -> edit if DonutSMP's wording differs
    static final Pattern P = Pattern.compile("(\\w{1,16}) paid you \\$\\s*([\\d,.]+)\\s*([kKmMbB]?)");
    static final Pattern A = Pattern.compile("([\\d,.]+)([kKmMbB]?)");
    static final int[][] PAL = {{0xFFD700, 0xFFFFFF}, {0x55FFFF, 0x55FF55}, {0xFF55FF, 0xAA00FF}};
    static final SoundEvent[] SND = {SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
            SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundEvents.ITEM_TOTEM_USE};
    // effect bits: 1 confetti, 2 burst, 4 fountain, 8 rings, 16 spiral, 32 lightning, 64 face rain, 128 face orbit,
    // 256 fireworks, 512 meteors, 1024 coins, 2048 $ rain, 4096 cannons, 8192 aurora, 16384 lasers, 32768 implode,
    // 65536 FAKE BLUE SCREEN, 1<<17 hacker terminal, 1<<18 slot machine, 1<<19 rocket to moon, 1<<20 tsunami, 1<<21 vault
    record Sc(int m, String t) {}
    static Sc s(int m, String t) { return new Sc(m, t); }
    static final int B = 65536, TERM = 1 << 17, SLOT = 1 << 18, ROCK = 1 << 19, WAVE = 1 << 20, VAULT = 1 << 21;
    // each entry is one complete animation; one is picked at random per payment, never the same twice in a row
    static final Sc[][] SC = {
            {s(1024, "MONEY RAIN"), s(4096, "PARTY TIME"), s(2048, "DONATION DETECTED"), s(8192, "NORTHERN GAINS"), s(TERM, "TRANSFER COMPLETE")},
            {s(1024 | 16, "GOLD RUSH"), s(4096 | 2, "CELEBRATION"), s(2048 | 32, "SYSTEM OVERLOAD"), s(8192 | 8, "SHOCKWAVE"),
                    s(256 | 1, "FIREWORKS"), s(SLOT | 1024, "JACKPOT"), s(ROCK | 512, "TO THE MOON"), s(TERM | 32, "HACKED THE BANK")},
            {s(256 | 4096 | 8, "FIREWORK FESTIVAL"), s(32 | 2048 | 8192, "STORM"), s(1024 | 4 | 512, "GOLD RUSH"), s(16 | 8192 | 512 | 16384, "GALAXY"),
                    s(B | 2 | 8 | 256, "ERROR: TOO MUCH MONEY"), s(ROCK | 256 | 512, "YEET TO THE MOON"), s(WAVE | 1024, "WHALE ALERT"),
                    s(VAULT | 1024 | 4, "VAULT BREACH"), s(SLOT | 256 | 4096, "MEGA JACKPOT")},
            {s(32768 | 2 | 8 | 8192 | 16384, "SUPERNOVA"), s(1024 | 2048 | 256 | 32, "MONEY APOCALYPSE"), s(16 | 512 | 4 | 2 | 8192 | 16384, "COSMIC EVENT"),
                    s(0xFFFF, "THE ULTIMATE"), s(B | 0xFFFF, "KERNEL PANIC: BANK EMPTY"), s(ROCK | WAVE | 256 | 512 | 32, "OFF THE CHARTS"),
                    s(VAULT | SLOT | 1024 | 256 | 4096, "LEGENDARY JACKPOT")}};
    static final int[] LAST = new int[5];
    static final String[] RE = {"$", "7", "#", "@"};
    static final ArrayDeque<Fx> Q = new ArrayDeque<>();
    static final Map<String, Double> TOT = new HashMap<>();
    static final Map<String, SkinTextures> SK = new HashMap<>();
    static Fx cur;
    static boolean board;
    static Path FILE;

    static class Fx { int tier, mask; String label, name, title, line; SkinTextures tex; long start, seed; boolean s2, s3; }

    public void onInitializeClient() {
        FILE = FabricLoader.getInstance().getConfigDir().resolve("payfx-leaderboard.txt");
        try {
            for (String l : Files.readAllLines(FILE)) {
                int i = l.lastIndexOf('=');
                if (i > 0) TOT.put(l.substring(0, i), Double.parseDouble(l.substring(i + 1)));
            }
        } catch (Exception e) { }
        ClientReceiveMessageEvents.GAME.register((m, o) -> {
            Matcher x = P.matcher(m.getString());
            if (x.find()) add(x.group(1), x.group(2), x.group(3), true);
        });
        // /payfx top = chat leaderboard, /payfx board = toggle on-screen leaderboard, /payfx 50m = test animation
        ClientCommandRegistrationCallback.EVENT.register((d, r) -> {
            var root = ClientCommandManager.literal("payfx");
            root.then(ClientCommandManager.literal("top").executes(x -> top()));
            root.then(ClientCommandManager.literal("board").executes(x -> { board = !board; return 1; }));
            root.then(ClientCommandManager.argument("amount", StringArgumentType.word()).executes(x -> {
                Matcher a = A.matcher(StringArgumentType.getString(x, "amount"));
                if (a.matches()) add(MinecraftClient.getInstance().getSession().getUsername(), a.group(1), a.group(2), false);
                return 1;
            }));
            d.register(root);
        });
        HudRenderCallback.EVENT.register((c, tc) -> draw(c));
    }

    static String fmt(double v) {
        return v >= 1e9 ? String.format("%.2fB", v / 1e9) : v >= 1e6 ? String.format("%.2fM", v / 1e6) : v >= 1e3 ? String.format("%.1fK", v / 1e3) : String.format("%.0f", v);
    }

    static List<Map.Entry<String, Double>> top(int n) {
        List<Map.Entry<String, Double>> l = new ArrayList<>(TOT.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l.subList(0, Math.min(n, l.size()));
    }

    static int top() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        p.sendMessage(Text.literal("§6§lTop Donors"), false);
        int i = 1;
        for (var e : top(10)) p.sendMessage(Text.literal("§e#" + (i++) + " §f" + e.getKey() + " §a$" + fmt(e.getValue())), false);
        if (TOT.isEmpty()) p.sendMessage(Text.literal("§7No payments yet"), false);
        return 1;
    }

    static SkinTextures skin(String name) {
        var h = MinecraftClient.getInstance().getNetworkHandler();
        PlayerListEntry pe = h == null ? null : h.getPlayerListEntry(name);
        if (pe != null) { SkinTextures s = pe.getSkinTextures(); SK.put(name, s); return s; }
        SkinTextures s = SK.get(name);
        return s != null ? s : DefaultSkinHelper.getSkinTextures(Uuids.getOfflinePlayerUuid(name));
    }

    static void add(String name, String num, String suf, boolean rec) {
        double v;
        try { v = Double.parseDouble(num.replace(",", "")); } catch (Exception e) { return; }
        switch (suf.toLowerCase()) {
            case "k": v *= 1e3; break;
            case "m": v *= 1e6; break;
            case "b": v *= 1e9; break;
        }
        String line = null;
        if (rec) {
            var tp = top(1);
            String old = tp.isEmpty() ? null : tp.get(0).getKey();
            TOT.merge(name, v, Double::sum);
            try {
                List<String> L = new ArrayList<>();
                TOT.forEach((k, x) -> L.add(k + "=" + x));
                Files.write(FILE, L);
            } catch (Exception e) { }
            var all = top(TOT.size());
            int idx = 0;
            for (int i = 0; i < all.size(); i++) if (all.get(i).getKey().equals(name)) idx = i;
            double total = TOT.get(name);
            line = idx == 0 ? (old != null && !old.equals(name) ? "★ NEW #1 DONOR! ★" : "★ #1 DONOR • $" + fmt(total) + " total")
                    : "#" + (idx + 1) + " donor • $" + fmt(all.get(idx - 1).getValue() - total) + " more to reach #" + idx;
        }
        int T = v >= 1e8 ? 4 : v >= 5e7 ? 3 : v >= 1e7 ? 2 : v >= 1e6 ? 1 : 0;
        if (T == 0 || Q.size() > 5) return;
        Fx f = new Fx();
        Random r = new Random();
        f.tier = T; f.name = name; f.seed = r.nextLong(); f.label = "+$" + num + suf.toUpperCase(); f.line = line;
        Sc[] sc = SC[T - 1];
        int idx;
        do idx = r.nextInt(sc.length); while (sc.length > 1 && idx == LAST[T]);
        LAST[T] = idx;
        f.mask = sc[idx].m(); f.title = sc[idx].t();
        if (T >= 3) f.mask |= 128;
        if (T == 4) f.mask |= 64;
        f.tex = skin(name);
        Q.add(f);
    }

    static int col(int T, int i, float t, int a) {
        int rgb = T == 4 ? Color.HSBtoRGB((i / 40f + t) % 1, .9f, 1) & 0xFFFFFF : PAL[T - 1][i % 2];
        return Math.max(0, Math.min(255, a)) << 24 | rgb;
    }

    // glowing particle: soft halo + bright core
    static void dot(DrawContext c, float x, float y, int s, int T, int i, float t, int a) {
        int X = (int) x, Y = (int) y;
        c.fill(X - s, Y - s, X + s * 2, Y + s * 2, col(T, i, t, a / 5));
        c.fill(X, Y, X + s, Y + s, col(T, i, t, a));
    }

    static void face(DrawContext c, SkinTextures tex, int x, int y, int s) { PlayerSkinDrawer.draw(c, tex, x, y, s); }

    static void drawBoard(DrawContext c, TextRenderer tr) {
        int w = c.getScaledWindowWidth(), x = w - 190, y = 30;
        var l = top(5);
        c.fill(x, y, w - 4, y + 20 + Math.max(1, l.size()) * 16, 0xAA000000);
        c.fill(x, y, w - 4, y + 2, 0xFFFFD700);
        c.drawTextWithShadow(tr, "Top Donors", x + 6, y + 6, 0xFFFFD700);
        if (l.isEmpty()) c.drawTextWithShadow(tr, "No payments yet", x + 6, y + 22, 0xFFAAAAAA);
        int i = 0;
        for (var e : l) {
            int ry = y + 20 + i * 16, cl = i == 0 ? 0xFFFFD700 : i == 1 ? 0xFFC0C0C0 : i == 2 ? 0xFFCD7F32 : 0xFFFFFFFF;
            c.drawTextWithShadow(tr, "#" + (i + 1), x + 4, ry + 2, cl);
            face(c, skin(e.getKey()), x + 22, ry, 12);
            c.drawTextWithShadow(tr, e.getKey(), x + 38, ry + 2, cl);
            String a = "$" + fmt(e.getValue());
            c.drawTextWithShadow(tr, a, w - 8 - tr.getWidth(a), ry + 2, 0xFF55FF55);
            i++;
        }
    }

    // fake Windows blue screen: "TOO MUCH MONEY"
    static void bsod(DrawContext c, Fx f, float tb, int w, int h, TextRenderer tr) {
        var ms = c.getMatrices();
        if (tb < .2f) {
            Random r = new Random((long) (tb * 60) + f.seed);
            for (int i = 0; i < 12; i++) { int y = r.nextInt(h); c.fill(0, y, w, y + r.nextInt(20) + 2, r.nextBoolean() ? 0xFF00FFFF : 0xFFFF00FF); }
            return;
        }
        if (tb > 2.0f) {
            c.fill(0, 0, w, h, 0xFF000000);
            if (tb > 2.1f) c.drawCenteredTextWithShadow(tr, "Restarting...", w / 2, h / 2, 0xFFFFFFFF);
            return;
        }
        c.fill(0, 0, w, h, 0xFF0078D7);
        int x = w / 12, y = h / 9;
        ms.push(); ms.translate(x, y, 0); ms.scale(7, 7, 1); c.drawText(tr, ":(", 0, 0, 0xFFFFFFFF, false); ms.pop();
        ms.push(); ms.translate(x, y + 80, 0); ms.scale(1.6f, 1.6f, 1);
        c.drawText(tr, "Your PC ran into a problem: too much money.", 0, 0, 0xFFFFFFFF, false); ms.pop();
        c.drawText(tr, f.label + " exceeded the maximum amount of money this system supports.", x, y + 110, 0xFFFFFFFF, false);
        c.drawText(tr, Math.min(100, (int) (tb / 1.9f * 100)) + "% complete", x, y + 130, 0xFFFFFFFF, false);
        int qy = y + 155;
        for (int i = 0; i < 15; i++)
            for (int j = 0; j < 15; j++)
                if (new Random(f.seed + i * 15 + j).nextBoolean()) c.fill(x + i * 3, qy + j * 3, x + i * 3 + 3, qy + j * 3 + 3, 0xFFFFFFFF);
        c.drawText(tr, "Stop code: TOO_MUCH_MONEY", x + 56, qy + 8, 0xFFFFFFFF, false);
        c.drawText(tr, "What failed: donation.sys (" + f.name + ")", x + 56, qy + 22, 0xFFFFFFFF, false);
    }

    static void draw(DrawContext c) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (board) drawBoard(c, tr);
        long now = System.currentTimeMillis();
        if (cur != null && now - cur.start > 2500 + cur.tier * 1000L + ((cur.mask & B) != 0 ? 2300 : 0)) cur = null;
        if (cur == null) {
            cur = Q.poll();
            if (cur == null) return;
            cur.start = now;
            if ((cur.mask & B) != 0) MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_NO, .6f));
            else playWin(cur.tier);
        }
        Fx f = cur;
        int T = f.tier, m = f.mask;
        boolean bs = (m & B) != 0;
        long e = now - f.start;
        float t = e / 1000f - (bs ? 2.3f : 0);
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight(), cx = w / 2, cy = h / 4;
        if (t < 0) { bsod(c, f, e / 1000f, w, h, tr); return; }
        if (bs && !f.s3) { f.s3 = true; playWin(T); }
        long ee = (long) (t * 1000);
        if (T == 4 && !f.s2 && ee > 1200) {
            f.s2 = true;
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f));
        }
        float p = e / (2500f + T * 1000 + (bs ? 2300 : 0));
        int al = (int) (255 * Math.min(1, (1 - p) * 3)), n = 20 * T * T;
        var ms = c.getMatrices();
        ms.push();
        if (T == 4 && t < 1.5f) ms.translate((float) Math.sin(t * 90) * (1.5f - t) * 2, (float) Math.cos(t * 80) * (1.5f - t) * 2, 0);
        if (T >= 3) c.fill(0, 0, w, h, (int) (70 * Math.min(1, Math.min(t * 4, (1 - p) * 4))) << 24);
        if (T >= 2) {
            int ea = (int) ((70 + 40 * Math.sin(t * 8)) * Math.min(1, (1 - p) * 3));
            c.fillGradient(0, 0, w, 45, col(T, 0, t, ea), col(T, 0, t, 0));
            c.fillGradient(0, h - 45, w, h, col(T, 20, t, 0), col(T, 20, t, ea));
        }
        if ((m & 8192) != 0) // aurora curtains
            for (int k = 0; k < 4; k++)
                for (int x = 0; x < w; x += 8) {
                    int y = (int) (h / 8f + Math.sin(x * .02 + t * 2 + k) * 22 + k * 14), rgb = Color.HSBtoRGB((.3f + k * .12f + x * .0005f) % 1, .8f, 1) & 0xFFFFFF;
                    c.fillGradient(x, y, x + 8, y + 55, (Math.min(110, al) << 24) | rgb, rgb);
                }
        if ((m & 16384) != 0) // laser sweeps
            for (int k = 0; k < 4; k++) {
                int y = (int) ((t * 260 + k * h / 4f) % h), x = (int) ((t * 220 + k * w / 4f) % w);
                c.fillGradient(0, y, w, y + 8, col(T, k * 7, t, Math.min(130, al)), col(T, k * 7, t, 0));
                c.fillGradient(x, 0, x + 6, h, col(T, k * 7 + 3, t, Math.min(100, al)), col(T, k * 7 + 3, t, 0));
            }
        if ((m & WAVE) != 0) { // tsunami rises then recedes
            float lvl = (float) Math.sin(Math.min(1, t / 2.4f) * Math.PI);
            for (int x = 0; x < w; x += 4) {
                int y = (int) (h - h * .75f * lvl + Math.sin(x * .03 + t * 4) * 14);
                if (lvl > .02f) c.fillGradient(x, y, x + 4, h, 0xBB5CC8FF, 0xDD0B2E8A);
            }
            if (lvl > .1f)
                for (int i = 0; i < 30; i++) {
                    Random r = new Random(f.seed + i);
                    float by = h - ((t * (50 + r.nextInt(80)) + r.nextInt(h)) % (h * .7f));
                    int bx = r.nextInt(w);
                    c.fill(bx, (int) by, bx + 3, (int) by + 3, 0xCCFFFFFF);
                }
        }
        if ((m & 2048) != 0) // $ matrix rain
            for (int k = 0; k < w / 16; k++) {
                Random r = new Random(f.seed + k);
                float head = (t * (120 + r.nextInt(160)) + r.nextInt(h)) % h;
                for (int j = 0; j < 5; j++) {
                    int y = (int) head - j * 10;
                    if (y < 0) continue;
                    c.drawText(tr, "$", k * 16, y, ((al * (5 - j) / 5) << 24) | (j == 0 ? 0xFFFFFF : 0x33FF66), false);
                }
            }
        if ((m & 1024) != 0) // spinning gold coins
            for (int i = 0; i < 15 + 15 * T; i++) {
                Random r = new Random(f.seed + i * 5L);
                int x = r.nextInt(w), y = (int) (-10 + t * (80 + r.nextInt(120))), wd = 1 + (int) (Math.abs(Math.sin(t * 6 + i)) * 5);
                c.fill(x, y, x + wd, y + 7, (al << 24) | 0xFFD700);
                c.fill(x, y, x + wd, y + 2, (al << 24) | 0xFFF2A0);
            }
        if ((m & 4096) != 0) // confetti cannons from both corners
            for (int sd = 0; sd < 2; sd++)
                for (int i = 0; i < 70; i++) {
                    Random r = new Random(f.seed + i * 3L + sd);
                    double a = -.5 - r.nextFloat() * .8, v = 260 + r.nextFloat() * 220;
                    float x = (sd == 0 ? 0 : w) + (sd == 0 ? 1 : -1) * (float) (Math.cos(a) * v * t), y = h + (float) (Math.sin(a) * v * t) + 260 * t * t;
                    if (y < h + 10) c.fill((int) x, (int) y, (int) x + 3, (int) y + 2, col(T, i, t, al));
                }
        if ((m & 32768) != 0) // implode into the face, then explode
            for (int i = 0; i < 150; i++) {
                Random r = new Random(f.seed + i * 11L);
                double a = r.nextFloat() * 6.283, d0 = 100 + r.nextFloat() * 200;
                double d = t < .6f ? d0 * (1 - t / .6f) : (t - .6f) * (120 + d0);
                dot(c, cx + (float) (Math.cos(a) * d), cy + (float) (Math.sin(a) * d), 2, T, i, t, t < .6f ? 255 : al);
            }
        if ((m & VAULT) != 0) { // vault doors swing open, golden light pours out
            float o = Math.min(1, t / .9f);
            int half = (int) (w / 2 * (1 - o));
            if (o > .6f)
                for (int k = 0; k < 9; k++) { int x = cx + (k - 4) * 30; c.fillGradient(x - 6, 0, x + 6, h, 0x66FFD700, 0x00FFD700); }
            if (o < 1) {
                c.fill(0, 0, half, h, 0xEE4A4A55); c.fill(w - half, 0, w, h, 0xEE4A4A55);
                c.fill(half - 4, 0, half, h, 0xFF9999A5); c.fill(w - half, 0, w - half + 4, h, 0xFF9999A5);
                for (int k = 1; k < 6; k++) { c.fill(half - 14, k * h / 6 - 5, half - 4, k * h / 6 + 5, 0xFFC0C0C8); c.fill(w - half + 4, k * h / 6 - 5, w - half + 14, k * h / 6 + 5, 0xFFC0C0C8); }
            }
        }
        if ((m & ROCK) != 0) { // rocket launches to the moon
            int mx = (int) (w * .82f), my = (int) (h * .14f);
            for (int yy = -22; yy <= 22; yy++) { int hw = (int) Math.sqrt(484 - yy * yy); c.fill(mx - hw, my + yy, mx + hw, my + yy + 1, 0xFFE8E8F0); }
            c.fill(mx - 8, my - 6, mx - 3, my - 1, 0xFFBBBBC8); c.fill(mx + 4, my + 5, mx + 10, my + 10, 0xFFBBBBC8);
            if (t < 1.7f) {
                int ry = (int) (h - (h + 60) * Math.min(1, t / 1.6f)), rx = cx + (int) (Math.sin(t * 25) * 1.5);
                c.fill(rx - 4, ry, rx + 4, ry + 22, 0xFFEEEEEE); c.fill(rx - 3, ry - 5, rx + 3, ry, 0xFFFF4444);
                c.fill(rx - 8, ry + 14, rx - 4, ry + 24, 0xFFFF4444); c.fill(rx + 4, ry + 14, rx + 8, ry + 24, 0xFFFF4444);
                c.fill(rx - 2, ry + 6, rx + 2, ry + 10, 0xFF55CCFF);
                for (int j = 0; j < 14; j++) {
                    Random r = new Random((long) (t * 40) * 31 + j);
                    c.fill(rx - 3 + r.nextInt(6), ry + 24 + j * 4, rx + 1 + r.nextInt(5), ry + 27 + j * 4, ((255 - j * 16) << 24) | (j < 6 ? 0xFFDD33 : 0xFF6600));
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Random r = new Random(f.seed + i * 31L);
            if ((m & 1) != 0) {
                float x = r.nextFloat() * w + (float) Math.sin(t * 3 + i) * 15, y = -10 + t * (100 + r.nextInt(150));
                c.fill((int) x, (int) y, (int) x + 2, (int) y + 3, col(T, i, t, al));
            }
            r = new Random(f.seed + i * 17L);
            if ((m & 2) != 0) {
                double a = r.nextFloat() * 6.283, v = 60 + r.nextFloat() * 200;
                for (int k = 0; k < 3; k++) {
                    float tt = t - k * .05f;
                    if (tt < 0) break;
                    dot(c, cx + (float) (Math.cos(a) * v * tt), cy + (float) (Math.sin(a) * v * tt), k == 0 ? 2 : 1, T, i, t, al / (k + 1));
                }
            }
            if ((m & 4) != 0) {
                double a = -1.57 + (r.nextFloat() - .5) * 1.2, v = 150 + r.nextFloat() * 150;
                for (int k = 0; k < 3; k++) {
                    float tt = t - k * .05f;
                    if (tt < 0) break;
                    dot(c, cx + (float) (Math.cos(a) * v * tt), h + (float) (Math.sin(a) * v * tt) + 150 * tt * tt, k == 0 ? 2 : 1, T, i, t, al / (k + 1));
                }
            }
        }
        if ((m & 8) != 0)
            for (int k = 0; k < 3; k++) {
                float rad = t * 150 - k * 40;
                if (rad <= 0) continue;
                for (int j = 0; j < 72; j++) {
                    double a = j * 6.283 / 72;
                    dot(c, cx + (float) (Math.cos(a) * rad), cy + (float) (Math.sin(a) * rad), 2, T, j + k * 9, t, al);
                }
            }
        if ((m & 16) != 0)
            for (int i = 0; i < 160; i++) {
                double rad = i * 1.6 * Math.min(1, t * 1.5), a = i * .22 + t * 5;
                dot(c, cx + (float) (Math.cos(a) * rad), cy + (float) (Math.sin(a) * rad), 1, T, i, t, al);
            }
        if ((m & 256) != 0)
            for (int k = 0; k < 4; k++) {
                Random r = new Random(f.seed + k * 7L);
                float tt = t - (.1f + k * .5f);
                if (tt < 0) continue;
                float rx = w * (k + 1) / 5f + r.nextInt(40) - 20, ry = cy + r.nextInt(30);
                if (tt < .5f) {
                    float y = h - (h - ry) * tt / .5f;
                    for (int j = 0; j < 5; j++) dot(c, rx, y + j * 5, 1, T, k, t, al / (j + 1));
                } else {
                    float u = tt - .5f;
                    for (int i = 0; i < 36 && u < 1.2f; i++) {
                        double a = i * 6.283 / 36 + r.nextFloat() * .2, d = (60 + r.nextInt(50)) * u;
                        dot(c, rx + (float) (Math.cos(a) * d), ry + (float) (Math.sin(a) * d) + 90 * u * u, 1, T, i + k * 9, t, (int) (al * (1 - u / 1.2f)));
                    }
                }
            }
        if ((m & 512) != 0)
            for (int i = 0; i < 10; i++) {
                Random r = new Random(f.seed + i * 13L);
                float sp = 250 + r.nextInt(200), x0 = r.nextInt(w + 250), y0 = -r.nextInt(150) - 20, d = t * sp - r.nextInt(100);
                if (d < 0) continue;
                for (int j = 0; j < 8; j++) dot(c, x0 - d + j * 7, y0 + d - j * 7, j == 0 ? 2 : 1, T, i * 3 + j, t, al / (1 + j / 2));
            }
        if ((m & 32) != 0) {
            int q = (int) (t * 3);
            if (t * 3 - q < .3f) {
                Random r = new Random(f.seed + q);
                int x = r.nextInt(w), y = 0;
                while (y < h) {
                    int nx = x + r.nextInt(31) - 15;
                    c.fill(Math.min(x, nx) - 3, y, Math.max(x, nx) + 5, y + 12, 0x555599FF);
                    c.fill(Math.min(x, nx), y, Math.max(x, nx) + 2, y + 12, 0xFFFFFFFF);
                    y += 12; x = nx;
                }
            }
        }
        if ((m & SLOT) != 0) { // slot machine: reels stop one by one on 777
            int bw = 34, x0 = cx - (3 * bw + 12) / 2, y0 = (int) (h * .58f);
            c.fill(x0 - 8, y0 - 8, x0 + 3 * bw + 20, y0 + bw + 12, 0xEE222222);
            c.fill(x0 - 8, y0 - 8, x0 + 3 * bw + 20, y0 - 5, 0xFFFFD700);
            c.fill(x0 - 8, y0 + bw + 9, x0 + 3 * bw + 20, y0 + bw + 12, 0xFFFFD700);
            for (int k = 0; k < 3; k++) {
                int bx = x0 + k * (bw + 6);
                String ch = t < .8f + k * .4f ? RE[(int) (t * 22 + k * 3) % 4] : "7";
                c.fill(bx, y0, bx + bw, y0 + bw + 4, 0xFFEEEEEE);
                ms.push(); ms.translate(bx + bw / 2f, y0 + 6, 0); ms.scale(3, 3, 1);
                c.drawText(tr, ch, -tr.getWidth(ch) / 2, 0, 0xFFCC0000, false); ms.pop();
            }
            if (t > 1.6f && ((int) (t * 6)) % 2 == 0) c.drawCenteredTextWithShadow(tr, "★ JACKPOT ★", cx, y0 - 22, 0xFFFFD700);
        }
        if ((m & TERM) != 0) { // hacker terminal types out
            int pw = Math.min(w - 20, 300), px0 = cx - pw / 2, py = (int) (h * .55f);
            c.fill(px0, py, px0 + pw, py + 66, 0xDD000000);
            c.fill(px0, py, px0 + pw, py + 10, 0xFF333333);
            c.drawText(tr, "payfx@donut:~$", px0 + 4, py + 1, 0xFFAAAAAA, false);
            String[] L = {"> connecting to bank...", "> verifying " + f.name + "...", "> transfer " + f.label + " ... OK", "> ACCESS GRANTED"};
            int shown = (int) (t * 45);
            for (int i = 0; i < 4 && shown > 0; i++) {
                int k = Math.min(L[i].length(), shown);
                c.drawText(tr, L[i].substring(0, k), px0 + 6, py + 14 + i * 12, 0xFF33FF66, false);
                shown -= L[i].length();
            }
        }
        if ((m & 64) != 0)
            for (int i = 0; i < 14; i++) {
                Random r = new Random(f.seed + i);
                int sz = 8 + r.nextInt(10);
                face(c, f.tex, r.nextInt(w), (int) (-sz + t * (90 + r.nextInt(120))), sz);
            }
        if ((m & 128) != 0)
            for (int k = 0; k < 8; k++)
                for (int g = 2; g >= 0; g--) {
                    double a = t * 3 + k * .785 - g * .22;
                    int sz = 14 - g * 3;
                    face(c, f.tex, (int) (cx + Math.cos(a) * 80) - sz / 2, (int) (cy + Math.sin(a) * 38) - sz / 2, sz);
                }
        if (T >= 3)
            for (int k = 0; k < 8; k++)
                if (Math.sin(t * 6 + k) > 0) { int x = w * (2 * k + 1) / 16; c.fillGradient(x - 2, 0, x + 2, h, col(T, k * 5, t, 140), col(T, k * 5, t, 0)); }
        float fl = ee < 400 ? 1 - ee / 400f : (T == 4 && ee > 1200 && ee < 1600 ? 1 - (ee - 1200) / 400f : 0);
        if (T >= 3 && fl > 0) c.fill(0, 0, w, h, ((int) (fl * (T == 4 ? 200 : 100)) << 24) | 0xFFFFFF);
        // donation card: dark panel, face, title, amount, name, rank
        int fs = 16 + T * 8, hf = fs / 2;
        float ts = 1.5f + T * .4f + .15f * (float) Math.sin(t * 10);
        int ty = cy + hf + 22, ly = (int) (ty + 8 * ts + 16);
        int pa = Math.min(150, al);
        c.fill(cx - 130, cy - hf - 14, cx + 130, ly + (f.line != null ? 22 : 8), pa << 24);
        c.fill(cx - 130, cy - hf - 14, cx + 130, cy - hf - 12, col(T, 0, t, pa + 60));
        float pop = t < .25f ? t / .25f * 1.2f : 1 + (float) Math.sin(t * 8) * .06f;
        ms.push();
        ms.translate(cx, cy, 0);
        ms.scale(pop, pop, 1);
        if (T == 4) ms.multiply(new Quaternionf().rotateZ((float) Math.sin(t * 6) * .12f));
        c.fill(-hf - 8, -hf - 8, hf + 8, hf + 8, col(T, 0, t, 40));
        c.fill(-hf - 5, -hf - 5, hf + 5, hf + 5, col(T, 4, t, 90));
        c.fill(-hf - 2, -hf - 2, hf + 2, hf + 2, col(T, 0, t, 255));
        face(c, f.tex, -hf, -hf, fs);
        ms.pop();
        c.drawCenteredTextWithShadow(tr, "★ " + f.title + " ★", cx, cy + hf + 8, col(T, (int) (t * 20), t, 255));
        ms.push();
        ms.translate(cx, ty, 0);
        ms.scale(ts, ts, 1);
        int px = -tr.getWidth(f.label) / 2;
        for (int i = 0; i < f.label.length(); i++) {
            String ch = f.label.substring(i, i + 1);
            c.drawTextWithShadow(tr, ch, px, (int) (Math.sin(t * 8 + i * .7) * 2), col(T, i * 3 + (int) (t * 30), t, 255));
            px += tr.getWidth(ch);
        }
        ms.pop();
        c.drawCenteredTextWithShadow(tr, "from " + f.name, cx, (int) (ty + 8 * ts + 4), 0xFFFFFFFF);
        if (f.line != null) c.drawCenteredTextWithShadow(tr, f.line, cx, ly, col(T, (int) (t * 10), t, 255));
        ms.pop();
    }

    static void playWin(int T) {
        var sm = MinecraftClient.getInstance().getSoundManager();
        sm.play(PositionedSoundInstance.master(SND[T - 1], 1f));
        if (T >= 3) sm.play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1f));
    }
}
