package me.bill.fakePlayerPlugin.coldmc.pvp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small fully-connected network: inputs are standardised, every hidden layer is tanh, the output
 * layer is linear and read through {@link PvpSchema}'s heads (softmax for 3-way heads, sigmoid for
 * binary ones). Immutable once built, so a model can be swapped for another between two ticks
 * without a lock. Pure Java: the same class does inference on the server and training offline.
 *
 * <p>File format (plain text, so it is diffable and needs no library):
 * <pre>
 * CPPMLP 1
 * schema 1
 * features dist dy ...
 * mean ...
 * std ...
 * layer IN OUT        (then OUT lines: IN weights followed by the bias)
 * meta key value
 * </pre>
 */
public final class Mlp {
    public final String[] features;
    public final double[] mean, std;
    /** w[l][o][i], b[l][o]. */
    public final double[][][] w;
    public final double[][] b;
    public final Map<String, String> meta;

    public Mlp(String[] features, double[] mean, double[] std, double[][][] w, double[][] b, Map<String, String> meta) {
        this.features = features;
        this.mean = mean;
        this.std = std;
        this.w = w;
        this.b = b;
        this.meta = meta;
    }

    public int inputs() { return mean.length; }
    public int outputs() { return b[b.length - 1].length; }

    /** Raw output logits for one observation. Allocation is a few small arrays; cheap enough per bot per decision. */
    public double[] forward(float[] x) {
        double[] a = new double[mean.length];
        for (int i = 0; i < a.length; i++) a[i] = (x[i] - mean[i]) / std[i];
        for (int l = 0; l < w.length; l++) {
            double[] z = new double[b[l].length];
            for (int o = 0; o < z.length; o++) {
                double s = b[l][o];
                double[] row = w[l][o];
                for (int i = 0; i < row.length; i++) s += row[i] * a[i];
                z[o] = l == w.length - 1 ? s : Math.tanh(s);
            }
            a = z;
        }
        return a;
    }

    /** Turns logits into per-head probabilities: softmax heads give 3 numbers, binary heads 1 (P(yes)). */
    public static double[] probabilities(double[] logits) {
        double[] p = new double[logits.length];
        for (int h = 0; h < PvpSchema.HEADS.length; h++) {
            int off = PvpSchema.headOffset(h), n = PvpSchema.HEAD_SIZE[h];
            if (n == 1) { p[off] = sigmoid(logits[off]); continue; }
            double max = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < n; k++) max = Math.max(max, logits[off + k]);
            double sum = 0;
            for (int k = 0; k < n; k++) { p[off + k] = Math.exp(logits[off + k] - max); sum += p[off + k]; }
            for (int k = 0; k < n; k++) p[off + k] /= sum;
        }
        return p;
    }

    public static double sigmoid(double z) { return 1.0 / (1.0 + Math.exp(-z)); }

    // ------------------------------------------------------------------ file

    public static Mlp load(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null || !first.trim().equals("CPPMLP 1")) throw new IOException("not a CPPMLP 1 file");
            String[] feats = null;
            double[] mean = null, std = null;
            List<double[][]> ws = new ArrayList<>();
            List<double[]> bs = new ArrayList<>();
            Map<String, String> meta = new LinkedHashMap<>();
            int schema = -1;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                switch (t[0]) {
                    case "schema" -> schema = Integer.parseInt(t[1]);
                    case "features" -> { feats = new String[t.length - 1]; System.arraycopy(t, 1, feats, 0, feats.length); }
                    case "mean" -> mean = nums(t);
                    case "std" -> std = nums(t);
                    case "layer" -> {
                        int in = Integer.parseInt(t[1]), out = Integer.parseInt(t[2]);
                        double[][] wl = new double[out][in];
                        double[] bl = new double[out];
                        for (int o = 0; o < out; o++) {
                            String row = r.readLine();
                            if (row == null) throw new IOException("layer cut short");
                            double[] v = nums(("x " + row.trim()).split("\\s+"));
                            if (v.length != in + 1) throw new IOException("layer row has " + v.length + " numbers, wanted " + (in + 1));
                            System.arraycopy(v, 0, wl[o], 0, in);
                            bl[o] = v[in];
                        }
                        ws.add(wl);
                        bs.add(bl);
                    }
                    case "meta" -> meta.put(t[1], t.length > 2 ? line.substring(line.indexOf(t[1]) + t[1].length()).trim() : "");
                    default -> throw new IOException("unknown line: " + t[0]);
                }
            }
            if (schema != PvpSchema.VERSION) throw new IOException("model is for schema " + schema + ", this plugin reads " + PvpSchema.VERSION);
            if (feats == null || mean == null || std == null || ws.isEmpty()) throw new IOException("incomplete model");
            Mlp m = new Mlp(feats, mean, std, ws.toArray(new double[0][][]), bs.toArray(new double[0][]), meta);
            m.validate();
            return m;
        } catch (RuntimeException e) {
            throw new IOException("bad model file: " + e, e);
        }
    }

    /** Refuses a model that would not fit this plugin or holds NaN/Inf - a bad file must never reach a bot. */
    public void validate() throws IOException {
        if (features.length != PvpSchema.N) throw new IOException("model has " + features.length + " features, plugin has " + PvpSchema.N);
        for (int i = 0; i < features.length; i++)
            if (!features[i].equals(PvpSchema.FEATURES[i])) throw new IOException("feature " + i + " is " + features[i] + ", plugin expects " + PvpSchema.FEATURES[i]);
        if (mean.length != PvpSchema.N || std.length != PvpSchema.N) throw new IOException("mean/std size");
        int in = PvpSchema.N;
        for (int l = 0; l < w.length; l++) {
            if (w[l].length != b[l].length) throw new IOException("layer " + l + " size");
            for (double[] row : w[l]) {
                if (row.length != in) throw new IOException("layer " + l + " input size " + row.length + " != " + in);
                for (double v : row) if (!Double.isFinite(v)) throw new IOException("non-finite weight");
            }
            for (double v : b[l]) if (!Double.isFinite(v)) throw new IOException("non-finite bias");
            in = w[l].length;
        }
        if (in != PvpSchema.OUT) throw new IOException("model has " + in + " outputs, plugin expects " + PvpSchema.OUT);
        for (int i = 0; i < std.length; i++) if (!(std[i] > 0) || !Double.isFinite(mean[i])) throw new IOException("bad normalisation for " + features[i]);
    }

    public void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer o = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            o.write("CPPMLP 1\n");
            o.write("# ColdPlayersPlus PvP bot model. Written by the trainer; the plugin only reads it.\n");
            o.write("schema " + PvpSchema.VERSION + "\n");
            o.write("features " + String.join(" ", features) + "\n");
            o.write("mean " + join(mean) + "\n");
            o.write("std " + join(std) + "\n");
            for (int l = 0; l < w.length; l++) {
                o.write("layer " + w[l][0].length + " " + w[l].length + "\n");
                for (int k = 0; k < w[l].length; k++) o.write(join(w[l][k]) + " " + fmt(b[l][k]) + "\n");
            }
            for (Map.Entry<String, String> e : meta.entrySet()) o.write("meta " + e.getKey() + " " + e.getValue().replace('\n', ' ') + "\n");
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static double[] nums(String[] t) {
        double[] v = new double[t.length - 1];
        for (int i = 1; i < t.length; i++) v[i - 1] = Double.parseDouble(t[i]);
        return v;
    }

    private static String join(double[] v) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) s.append(' '); s.append(fmt(v[i])); }
        return s.toString();
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.6g", d); }
}
