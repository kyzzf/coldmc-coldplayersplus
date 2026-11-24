package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Offline trainer for the PvP bot model. Runs on any machine with Java 21+, no server, no library:
 *
 * <pre>
 * java -cp ColdPlayersPlus.jar me.bill.fakePlayerPlugin.coldmc.pvp.Trainer &lt;data dir&gt;... --out combat-model.txt
 * </pre>
 *
 * It learns, from real players' fights, what a player tends to do in a situation: walk in or back
 * off, strafe which way, jump, sprint, swing, drink soup. That is behaviour cloning, weighted toward
 * fights that went well, with every player's share capped so the model is the crowd and not one
 * person. A model that does not beat the "always guess the most common action" baseline on held-out
 * data is not written (unless --force), so a bad training run can never reach the bots.
 */
public final class Trainer {

    static final class Sample {
        final float[] x;
        final int[] y; // fwd strafe as class index 0..2, then 4 binaries
        final String who;
        final String eng;
        double weight = 1;
        Sample(float[] x, int[] y, String who, String eng) { this.x = x; this.y = y; this.who = who; this.eng = eng; }
    }

    public static void main(String[] argv) throws Exception {
        List<Path> inputs = new ArrayList<>();
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("--force")) opt.put("force", "1");
            else if (a.equals("--keep-unfought")) opt.put("keep-unfought", "1");
            else if (a.startsWith("--")) { if (i + 1 >= argv.length) usage("missing value for " + a); opt.put(a.substring(2), argv[++i]); }
            else inputs.add(Paths.get(a));
        }
        if (inputs.isEmpty() || !opt.containsKey("out")) usage(null);
        int[] hidden = parseHidden(opt.getOrDefault("hidden", "32,16"));
        int epochs = Integer.parseInt(opt.getOrDefault("epochs", "30"));
        double lr = Double.parseDouble(opt.getOrDefault("lr", "0.003"));
        int batch = Integer.parseInt(opt.getOrDefault("batch", "128"));
        int maxSamples = Integer.parseInt(opt.getOrDefault("max-samples", "400000"));
        double maxShare = Double.parseDouble(opt.getOrDefault("max-share", "0.25"));
        double valFrac = Double.parseDouble(opt.getOrDefault("val", "0.15"));
        int minSamples = Integer.parseInt(opt.getOrDefault("min-samples", "2000"));
        long seed = Long.parseLong(opt.getOrDefault("seed", "7"));
        double l2 = Double.parseDouble(opt.getOrDefault("l2", "0.0001"));
        boolean force = opt.containsKey("force");
        Random rnd = new Random(seed);

        // ---- read
        List<Sample> all = new ArrayList<>();
        Map<String, double[]> outcome = new HashMap<>(); // who|eng -> dealt, taken, result(+1 win, -1 loss, 0)
        int[] files = {0}, skipped = {0};
        for (Path in : inputs) {
            try (Stream<Path> s = Files.isDirectory(in) ? Files.walk(in) : Stream.of(in)) {
                for (Path f : (Iterable<Path>) s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".csv") || n.endsWith(".csv.gz");
                }).sorted()::iterator) {
                    files[0]++;
                    read(f, all, outcome, skipped);
                }
            }
        }
        System.out.printf(Locale.ROOT, "read %d samples from %d files (%d rows skipped), %d engagements with an outcome%n",
                all.size(), files[0], skipped[0], outcome.size());

        // ---- only real fights: the player both dealt and took damage. Samples with no outcome row (never
        // fought, AFK, standing near someone) or from a one-sided fight are dropped. The recorder already does
        // this for new files; this also cleans files recorded before it did (the owner, 2026-11-24).
        if (!opt.containsKey("keep-unfought")) {
            double minDealt = Double.parseDouble(opt.getOrDefault("min-dealt", "1")), minTaken = Double.parseDouble(opt.getOrDefault("min-taken", "1"));
            int before = all.size();
            all.removeIf(sm -> { double[] o = outcome.get(sm.who + "|" + sm.eng); return o == null || o[0] < minDealt || o[1] < minTaken; });
            System.out.printf(Locale.ROOT, "kept %d samples from real two-sided fights, dropped %d (no fight, AFK or one-sided)%n", all.size(), before - all.size());
        }
        if (all.size() < minSamples) {
            System.out.println("not enough samples (" + all.size() + " < " + minSamples + "): no model written. Let players fight longer, or lower --min-samples.");
            System.exit(2);
        }

        // ---- weight by how the fight went: a sample from a won, clean fight counts up to 2x, a lost one down to 0.3x
        for (Sample sm : all) {
            double[] o = outcome.get(sm.who + "|" + sm.eng);
            if (o == null) continue;
            double w = 1 + 0.5 * o[2] + Math.max(-0.5, Math.min(0.5, (o[0] - o[1]) / 20.0));
            sm.weight = Math.max(0.3, Math.min(2.0, w));
        }

        // ---- no single player may be more than maxShare of the set
        Map<String, List<Sample>> byWho = new LinkedHashMap<>();
        for (Sample sm : all) byWho.computeIfAbsent(sm.who, k -> new ArrayList<>()).add(sm);
        int players = byWho.size();
        int cap = players <= 1 ? Integer.MAX_VALUE : (int) Math.max(minSamples / 4.0, maxShare * all.size());
        List<Sample> set = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> e : byWho.entrySet()) {
            List<Sample> l = e.getValue();
            if (l.size() > cap) { Collections.shuffle(l, rnd); l = l.subList(0, cap); }
            set.addAll(l);
        }
        Collections.shuffle(set, rnd);
        if (set.size() > maxSamples) set = new ArrayList<>(set.subList(0, maxSamples));
        if (players <= 1) System.out.println("warning: all data is from ONE player - the model will copy that person. Collect from more players.");
        int nVal = Math.max(1, (int) (set.size() * valFrac));
        List<Sample> val = set.subList(0, nVal), train = set.subList(nVal, set.size());
        System.out.printf(Locale.ROOT, "%d players, training on %d, validating on %d%n", players, train.size(), val.size());

        // ---- normalisation from the training split
        int n = PvpSchema.N;
        double[] mean = new double[n], std = new double[n];
        for (Sample sm : train) for (int i = 0; i < n; i++) mean[i] += sm.x[i];
        for (int i = 0; i < n; i++) mean[i] /= train.size();
        for (Sample sm : train) for (int i = 0; i < n; i++) { double d = sm.x[i] - mean[i]; std[i] += d * d; }
        for (int i = 0; i < n; i++) std[i] = Math.max(1e-3, Math.sqrt(std[i] / train.size()));

        // ---- network
        int[] sizes = new int[hidden.length + 2];
        sizes[0] = n;
        System.arraycopy(hidden, 0, sizes, 1, hidden.length);
        sizes[sizes.length - 1] = PvpSchema.OUT;
        int L = sizes.length - 1;
        double[][][] w = new double[L][][];
        double[][] b = new double[L][];
        Mlp init = opt.containsKey("init") ? tryInit(Paths.get(opt.get("init")), sizes) : null;
        for (int l = 0; l < L; l++) {
            w[l] = new double[sizes[l + 1]][sizes[l]];
            b[l] = new double[sizes[l + 1]];
            double scale = Math.sqrt(1.0 / sizes[l]);
            for (double[] row : w[l]) for (int i = 0; i < row.length; i++) row[i] = rnd.nextGaussian() * scale;
            if (init != null) { for (int o = 0; o < w[l].length; o++) { w[l][o] = init.w[l][o].clone(); } b[l] = init.b[l].clone(); }
        }
        if (init != null) System.out.println("warm start from " + opt.get("init"));
        // output biases start at the label priors, so the first epochs are not spent learning frequencies
        double[] prior = priors(train);
        if (init == null) for (int k = 0; k < PvpSchema.OUT; k++) b[L - 1][k] = priorLogit(prior, k);

        Adam adam = new Adam(w, b);
        double baseLoss = baselineLoss(val, prior);
        Mlp best = null;
        double bestLoss = Double.MAX_VALUE;
        List<Sample> order = new ArrayList<>(train);
        for (int ep = 1; ep <= epochs; ep++) {
            Collections.shuffle(order, rnd);
            double trainLoss = 0, tw = 0;
            for (int s0 = 0; s0 < order.size(); s0 += batch) {
                int s1 = Math.min(order.size(), s0 + batch);
                adam.zero();
                for (int s = s0; s < s1; s++) {
                    Sample sm = order.get(s);
                    trainLoss += backprop(w, b, sizes, mean, std, sm, adam) * sm.weight;
                    tw += sm.weight;
                }
                adam.step(lr, s1 - s0, l2);
            }
            Mlp m = new Mlp(PvpSchema.FEATURES.clone(), mean, std, deep(w), deep2(b), new LinkedHashMap<>());
            double vl = loss(m, val);
            System.out.printf(Locale.ROOT, "epoch %2d  train %.4f  val %.4f  (baseline %.4f)%n", ep, trainLoss / tw, vl, baseLoss);
            if (vl < bestLoss) { bestLoss = vl; best = m; }
        }

        // ---- report and decide
        System.out.println("held-out accuracy per head (model / always-most-common):");
        double[] acc = accuracy(best, val), baseAcc = baselineAccuracy(val, prior);
        for (int h = 0; h < PvpSchema.HEADS.length; h++)
            System.out.printf(Locale.ROOT, "  %-7s %.3f / %.3f%n", PvpSchema.HEADS[h], acc[h], baseAcc[h]);
        boolean better = bestLoss < baseLoss - 1e-4;
        if (!better && !force) {
            System.out.printf(Locale.ROOT, "the model (%.4f) does not beat the baseline (%.4f): NOT written. Collect more varied fights, or --force.%n", bestLoss, baseLoss);
            System.exit(3);
        }
        best.meta.put("trained", Instant.now().toString());
        best.meta.put("samples", String.valueOf(set.size()));
        best.meta.put("players", String.valueOf(players));
        best.meta.put("val_loss", String.format(Locale.ROOT, "%.5f", bestLoss));
        best.meta.put("baseline_loss", String.format(Locale.ROOT, "%.5f", baseLoss));
        best.meta.put("hidden", opt.getOrDefault("hidden", "32,16"));
        best.validate();
        Path out = Paths.get(opt.get("out"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        best.save(out);
        System.out.println("wrote " + out.toAbsolutePath());
    }

    private static void usage(String why) {
        if (why != null) System.err.println(why);
        System.err.println("usage: Trainer <data dir or file>... --out <model.txt> [--hidden 32,16] [--epochs 30] [--lr 0.003]\n"
                + "        [--batch 128] [--max-samples 400000] [--max-share 0.25] [--val 0.15] [--min-samples 2000]\n"
                + "        [--l2 0.0001] [--seed 7] [--init <old model.txt>] [--force]\n"
                + "        [--min-dealt 1] [--min-taken 1] [--keep-unfought]");
        System.exit(1);
    }

    private static int[] parseHidden(String s) {
        String[] t = s.split(",");
        int[] h = new int[t.length];
        for (int i = 0; i < t.length; i++) h[i] = Math.max(2, Math.min(256, Integer.parseInt(t[i].trim())));
        return h;
    }

    private static Mlp tryInit(Path p, int[] sizes) {
        try {
            Mlp m = Mlp.load(p);
            if (m.w.length != sizes.length - 1) return null;
            for (int l = 0; l < m.w.length; l++) if (m.w[l].length != sizes[l + 1]) return null;
            return m;
        } catch (IOException e) {
            System.out.println("not warm-starting: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ reading

    private static void read(Path f, List<Sample> out, Map<String, double[]> outcome, int[] skipped) throws IOException {
        InputStream raw = Files.newInputStream(f);
        if (f.getFileName().toString().endsWith(".gz")) raw = new GZIPInputStream(raw, 1 << 16);
        boolean ok = false; // a file with a header for another schema is skipped
        try (BufferedReader r = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("S,v")) { ok = line.equals(PvpSchema.header()); continue; }
                String[] t = line.split(",");
                try {
                    if (t[0].equals("E") && t.length >= 6) {
                        double res = t[5].equals("win") ? 1 : t[5].equals("loss") ? -1 : 0;
                        outcome.put(t[1] + "|" + t[2], new double[]{Double.parseDouble(t[3]), Double.parseDouble(t[4]), res});
                    } else if (t[0].equals("S") && ok && t.length == 3 + PvpSchema.N + PvpSchema.LABELS) {
                        float[] x = new float[PvpSchema.N];
                        for (int i = 0; i < PvpSchema.N; i++) {
                            float v = Float.parseFloat(t[3 + i]);
                            if (!Float.isFinite(v)) throw new NumberFormatException();
                            x[i] = v;
                        }
                        int[] y = new int[PvpSchema.LABELS];
                        int o = 3 + PvpSchema.N;
                        y[0] = clamp3(Integer.parseInt(t[o]) + 1);
                        y[1] = clamp3(Integer.parseInt(t[o + 1]) + 1);
                        for (int k = 2; k < PvpSchema.LABELS; k++) y[k] = Integer.parseInt(t[o + k]) != 0 ? 1 : 0;
                        out.add(new Sample(x, y, t[1], t[2]));
                    } else if (!line.isBlank()) skipped[0]++;
                } catch (RuntimeException e) {
                    skipped[0]++;
                }
            }
        } catch (java.io.EOFException e) {
            System.out.println("note: " + f.getFileName() + " ends early (file still being written?) - read what was there");
        }
    }

    private static int clamp3(int v) { return Math.max(0, Math.min(2, v)); }

    // ------------------------------------------------------------------ maths

    /** Forward + backward for one sample; adds the gradient into adam's buffers. Returns its loss. */
    private static double backprop(double[][][] w, double[][] b, int[] sizes, double[] mean, double[] std, Sample sm, Adam g) {
        int L = w.length;
        double[][] act = new double[L + 1][];
        act[0] = new double[sizes[0]];
        for (int i = 0; i < sizes[0]; i++) act[0][i] = (sm.x[i] - mean[i]) / std[i];
        for (int l = 0; l < L; l++) {
            double[] z = new double[sizes[l + 1]];
            for (int o = 0; o < z.length; o++) {
                double s = b[l][o];
                for (int i = 0; i < sizes[l]; i++) s += w[l][o][i] * act[l][i];
                z[o] = l == L - 1 ? s : Math.tanh(s);
            }
            act[l + 1] = z;
        }
        double[] logits = act[L];
        double[] p = Mlp.probabilities(logits);
        double[] delta = new double[PvpSchema.OUT];
        double loss = 0;
        for (int h = 0; h < PvpSchema.HEADS.length; h++) {
            int off = PvpSchema.headOffset(h);
            if (PvpSchema.HEAD_SIZE[h] == 3) {
                int c = sm.y[h];
                loss -= Math.log(Math.max(1e-9, p[off + c]));
                for (int k = 0; k < 3; k++) delta[off + k] = p[off + k] - (k == c ? 1 : 0);
            } else {
                int yv = sm.y[h];
                loss -= yv == 1 ? Math.log(Math.max(1e-9, p[off])) : Math.log(Math.max(1e-9, 1 - p[off]));
                delta[off] = p[off] - yv;
            }
        }
        double wt = sm.weight;
        for (int l = L - 1; l >= 0; l--) {
            double[] prev = act[l];
            double[] nextDelta = l > 0 ? new double[sizes[l]] : null;
            for (int o = 0; o < delta.length; o++) {
                double d = delta[o] * wt;
                g.gb[l][o] += d;
                double[] gw = g.gw[l][o], wr = w[l][o];
                for (int i = 0; i < prev.length; i++) {
                    gw[i] += d * prev[i];
                    if (nextDelta != null) nextDelta[i] += delta[o] * wr[i];
                }
            }
            if (nextDelta != null) {
                for (int i = 0; i < nextDelta.length; i++) nextDelta[i] *= 1 - prev[i] * prev[i]; // tanh'
                delta = nextDelta;
            }
        }
        return loss;
    }

    private static double sampleLoss(double[] p, Sample sm) {
        double loss = 0;
        for (int h = 0; h < PvpSchema.HEADS.length; h++) {
            int off = PvpSchema.headOffset(h);
            if (PvpSchema.HEAD_SIZE[h] == 3) loss -= Math.log(Math.max(1e-9, p[off + sm.y[h]]));
            else loss -= sm.y[h] == 1 ? Math.log(Math.max(1e-9, p[off])) : Math.log(Math.max(1e-9, 1 - p[off]));
        }
        return loss;
    }

    private static double loss(Mlp m, List<Sample> set) {
        double s = 0, tw = 0;
        for (Sample sm : set) { s += sampleLoss(Mlp.probabilities(m.forward(sm.x)), sm) * sm.weight; tw += sm.weight; }
        return s / tw;
    }

    /** Label frequencies laid out like the output vector (3-way heads: class shares; binary: P(1)). */
    private static double[] priors(List<Sample> set) {
        double[] p = new double[PvpSchema.OUT];
        for (Sample sm : set)
            for (int h = 0; h < PvpSchema.HEADS.length; h++) {
                int off = PvpSchema.headOffset(h);
                if (PvpSchema.HEAD_SIZE[h] == 3) p[off + sm.y[h]]++; else p[off] += sm.y[h];
            }
        for (int k = 0; k < p.length; k++) p[k] = Math.max(1e-4, Math.min(1 - 1e-4, p[k] / set.size()));
        return p;
    }

    private static double priorLogit(double[] prior, int k) {
        for (int h = 0; h < PvpSchema.HEADS.length; h++) {
            int off = PvpSchema.headOffset(h);
            if (k >= off && k < off + PvpSchema.HEAD_SIZE[h])
                return PvpSchema.HEAD_SIZE[h] == 3 ? Math.log(prior[k]) : Math.log(prior[k] / (1 - prior[k]));
        }
        return 0;
    }

    private static double baselineLoss(List<Sample> set, double[] prior) {
        double s = 0, tw = 0;
        for (Sample sm : set) { s += sampleLoss(prior, sm) * sm.weight; tw += sm.weight; }
        return s / tw;
    }

    private static double[] accuracy(Mlp m, List<Sample> set) {
        double[] acc = new double[PvpSchema.HEADS.length];
        for (Sample sm : set) {
            double[] p = Mlp.probabilities(m.forward(sm.x));
            for (int h = 0; h < acc.length; h++) if (predict(p, h) == sm.y[h]) acc[h]++;
        }
        for (int h = 0; h < acc.length; h++) acc[h] /= set.size();
        return acc;
    }

    private static double[] baselineAccuracy(List<Sample> set, double[] prior) {
        double[] acc = new double[PvpSchema.HEADS.length];
        for (Sample sm : set) for (int h = 0; h < acc.length; h++) if (predict(prior, h) == sm.y[h]) acc[h]++;
        for (int h = 0; h < acc.length; h++) acc[h] /= set.size();
        return acc;
    }

    private static int predict(double[] p, int h) {
        int off = PvpSchema.headOffset(h);
        if (PvpSchema.HEAD_SIZE[h] == 1) return p[off] >= 0.5 ? 1 : 0;
        int best = 0;
        for (int k = 1; k < 3; k++) if (p[off + k] > p[off + best]) best = k;
        return best;
    }

    private static double[][][] deep(double[][][] a) {
        double[][][] c = new double[a.length][][];
        for (int l = 0; l < a.length; l++) { c[l] = new double[a[l].length][]; for (int o = 0; o < a[l].length; o++) c[l][o] = a[l][o].clone(); }
        return c;
    }

    private static double[][] deep2(double[][] a) {
        double[][] c = new double[a.length][];
        for (int l = 0; l < a.length; l++) c[l] = a[l].clone();
        return c;
    }

    /** Adam over all weights and biases, with L2 on weights only. */
    static final class Adam {
        final double[][][] w, gw, mw, vw;
        final double[][] b, gb, mb, vb;
        int t;
        Adam(double[][][] w, double[][] b) {
            this.w = w; this.b = b;
            gw = zeros(w); mw = zeros(w); vw = zeros(w);
            gb = zeros(b); mb = zeros(b); vb = zeros(b);
        }
        void zero() {
            for (double[][] l : gw) for (double[] r : l) java.util.Arrays.fill(r, 0);
            for (double[] r : gb) java.util.Arrays.fill(r, 0);
        }
        void step(double lr, int n, double l2) {
            t++;
            double b1 = 0.9, b2 = 0.999, eps = 1e-8;
            double c1 = 1 - Math.pow(b1, t), c2 = 1 - Math.pow(b2, t);
            for (int l = 0; l < w.length; l++) {
                for (int o = 0; o < w[l].length; o++) {
                    for (int i = 0; i < w[l][o].length; i++) {
                        double g = gw[l][o][i] / n + l2 * w[l][o][i];
                        mw[l][o][i] = b1 * mw[l][o][i] + (1 - b1) * g;
                        vw[l][o][i] = b2 * vw[l][o][i] + (1 - b2) * g * g;
                        w[l][o][i] -= lr * (mw[l][o][i] / c1) / (Math.sqrt(vw[l][o][i] / c2) + eps);
                    }
                    double g = gb[l][o] / n;
                    mb[l][o] = b1 * mb[l][o] + (1 - b1) * g;
                    vb[l][o] = b2 * vb[l][o] + (1 - b2) * g * g;
                    b[l][o] -= lr * (mb[l][o] / c1) / (Math.sqrt(vb[l][o] / c2) + eps);
                }
            }
        }
        private static double[][][] zeros(double[][][] a) {
            double[][][] z = new double[a.length][][];
            for (int l = 0; l < a.length; l++) { z[l] = new double[a[l].length][]; for (int o = 0; o < a[l].length; o++) z[l][o] = new double[a[l][o].length]; }
            return z;
        }
        private static double[][] zeros(double[][] a) {
            double[][] z = new double[a.length][];
            for (int l = 0; l < a.length; l++) z[l] = new double[a[l].length];
            return z;
        }
    }
}
