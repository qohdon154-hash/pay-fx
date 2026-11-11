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
    static final Pattern P = Pattern.compile("(\\w{1,16}) paid you \\$([\\d,.]+)\\s*([kKmMbB]?)");
    static final Pattern A = Pattern.compile("([\\d,.]+)([kKmMbB]?)");
    static final int[][] PAL = {{0xFFD700, 0xFFFFFF}, {0x55FFFF, 0x55FF55}, {0xFF55FF, 0xAA00FF}};
    static final SoundEvent[] SND = {SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
            SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundEvents.ITEM_TOTEM_USE};
    // effect bits: 1 confetti, 2 burst, 4 fountain, 8 rings, 16 spiral, 32 lightning, 64 face rain, 128 face orbit,
    // 256 fireworks, 512 meteors, 1024 gold coins, 2048 $ matrix rain, 4096 confetti cannons, 8192 aurora, 16384 laser sweep, 32768 implode->explode
    // each entry is one complete animation (scene); one is picked at random per payment, never the same twice in a row
    static final int[][] SC = {
            {1024, 4096, 2048, 8192},
            {1024 | 16, 4096 | 2, 2048 | 32, 8192 | 8, 256 | 1},
            {256 | 4096 | 8, 32 | 2048 | 8192, 1024 | 4 | 512, 16 | 8192 | 512 | 16384},
            {32768 | 2 | 8 | 8192 | 16384, 1024 | 2048 | 256 | 32, 16 | 512 | 4 | 2 | 8192 | 16384, 0x7FFF}};
    static final int[] LAST = new int[5];
    static final ArrayDeque<Fx> Q = new ArrayDeque<>();
    static final Map<String, Double> TOT = new HashMap<>();
    static final Map<String, SkinTextures> SK = new HashMap<>();
    static Fx cur;
    static boolean board;
    static Path FILE;

    static class Fx { int tier, mask; String label, name; SkinTextures tex; long start, seed; boolean s2; }

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
        if (rec) {
            TOT.merge(name, v, Double::sum);
            try {
                List<String> L = new ArrayList<>();
                TOT.forEach((k, x) -> L.add(k + "=" + x));
                Files.write(FILE, L);
            } catch (Exception e) { }
        }
        int T = v >= 1e8 ? 4 : v >= 5e7 ? 3 : v >= 1e7 ? 2 : v >= 1e6 ? 1 : 0;
        if (T == 0 || Q.size() > 5) return;
        Fx f = new Fx();
        Random r = new Random();
        f.tier = T; f.name = name; f.seed = r.nextLong(); f.label = "+$" + num + suf.toUpperCase();
        int[] sc = SC[T - 1];
        int idx;
        do idx = r.nextInt(sc.length); while (sc.length > 1 && idx == LAST[T]);
        LAST[T] = idx;
        f.mask = sc[idx];
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

    static void draw(DrawContext c) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (board) drawBoard(c, tr);
        long now = System.currentTimeMillis();
        if (cur != null && now - cur.start > 2500 + cur.tier * 1000L) cur = null;
        if (cur == null) {
            cur = Q.poll();
            if (cur == null) return;
            cur.start = now;
            var sm = MinecraftClient.getInstance().getSoundManager();
            sm.play(PositionedSoundInstance.master(SND[cur.tier - 1], 1f));
            if (cur.tier >= 3) sm.play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1f));
        }
        Fx f = cur;
        int T = f.tier, m = f.mask;
        long e = now - f.start;
        if (T == 4 && !f.s2 && e > 1200) {
            f.s2 = true;
            MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f));
        }
        float t = e / 1000f, p = e / (2500f + T * 1000);
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight(), cx = w / 2, cy = h / 4;
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
            for (int s = 0; s < 2; s++)
                for (int i = 0; i < 70; i++) {
                    Random r = new Random(f.seed + i * 3L + s);
                    double a = -.5 - r.nextFloat() * .8, v = 260 + r.nextFloat() * 220;
                    float x = (s == 0 ? 0 : w) + (s == 0 ? 1 : -1) * (float) (Math.cos(a) * v * t), y = h + (float) (Math.sin(a) * v * t) + 260 * t * t;
                    if (y < h + 10) c.fill((int) x, (int) y, (int) x + 3, (int) y + 2, col(T, i, t, al));
                }
        if ((m & 32768) != 0) // implode into the face, then explode
            for (int i = 0; i < 150; i++) {
                Random r = new Random(f.seed + i * 11L);
                double a = r.nextFloat() * 6.283, d0 = 100 + r.nextFloat() * 200;
                double d = t < .6f ? d0 * (1 - t / .6f) : (t - .6f) * (120 + d0);
                dot(c, cx + (float) (Math.cos(a) * d), cy + (float) (Math.sin(a) * d), 2, T, i, t, t < .6f ? 255 : al);
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
            int s = (int) (t * 3);
            if (t * 3 - s < .3f) {
                Random r = new Random(f.seed + s);
                int x = r.nextInt(w), y = 0;
                while (y < h) {
                    int nx = x + r.nextInt(31) - 15;
                    c.fill(Math.min(x, nx) - 3, y, Math.max(x, nx) + 5, y + 12, 0x555599FF);
                    c.fill(Math.min(x, nx), y, Math.max(x, nx) + 2, y + 12, 0xFFFFFFFF);
                    y += 12; x = nx;
                }
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
        float fl = e < 400 ? 1 - e / 400f : (T == 4 && e > 1200 && e < 1600 ? 1 - (e - 1200) / 400f : 0);
        if (T >= 3 && fl > 0) c.fill(0, 0, w, h, ((int) (fl * (T == 4 ? 200 : 100)) << 24) | 0xFFFFFF);
        int fs = 16 + T * 8;
        float pop = t < .25f ? t / .25f * 1.2f : 1 + (float) Math.sin(t * 8) * .06f;
        ms.push();
        ms.translate(cx, cy, 0);
        ms.scale(pop, pop, 1);
        if (T == 4) ms.multiply(new Quaternionf().rotateZ((float) Math.sin(t * 6) * .12f));
        int hf = fs / 2;
        c.fill(-hf - 8, -hf - 8, hf + 8, hf + 8, col(T, 0, t, 40));
        c.fill(-hf - 5, -hf - 5, hf + 5, hf + 5, col(T, 4, t, 90));
        c.fill(-hf - 2, -hf - 2, hf + 2, hf + 2, col(T, 0, t, 255));
        face(c, f.tex, -hf, -hf, fs);
        ms.pop();
        float s = 1.5f + T * .4f + .15f * (float) Math.sin(t * 10);
        int ty = cy + hf + 10;
        ms.push();
        ms.translate(cx, ty, 0);
        ms.scale(s, s, 1);
        int px = -tr.getWidth(f.label) / 2;
        for (int i = 0; i < f.label.length(); i++) {
            String ch = f.label.substring(i, i + 1);
            c.drawTextWithShadow(tr, ch, px, (int) (Math.sin(t * 8 + i * .7) * 2), col(T, i * 3 + (int) (t * 30), t, 255));
            px += tr.getWidth(ch);
        }
        ms.pop();
        c.drawCenteredTextWithShadow(tr, "from " + f.name, cx, (int) (ty + 8 * s + 5), 0xFFFFFFFF);
        ms.pop();
    }
}
