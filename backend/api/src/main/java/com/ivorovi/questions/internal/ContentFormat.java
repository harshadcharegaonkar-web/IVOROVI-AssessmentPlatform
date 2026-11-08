package com.ivorovi.questions.internal;

import com.ivorovi.platform.web.ApiException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Question content format {@code ivorovi.content.v1} (ADR-0010): a structured document of paragraphs (text and inline
 * math runs), display math, images and lists — never HTML. Validation rebuilds the document from allowed keys only, so
 * nothing unknown is ever stored; rendering builds DOM nodes from this structure.
 */
public final class ContentFormat {

    public static final String FORMAT = "ivorovi.content.v1";
    private static final Set<String> MARKS = Set.of("bold", "italic", "sub", "sup");

    /** An image used by the content: asset id with its accessible description. */
    public record ImageUse(UUID assetId, String alt, boolean decorative) {}

    public record Parsed(Map<String, Object> document, String plainText, List<ImageUse> images, int chars) {}

    private ContentFormat() {}

    @SuppressWarnings("unchecked")
    public static Parsed parse(Object raw, String path, int maxChars, int maxImages) {
        if (!(raw instanceof Map<?, ?> m)) throw invalid(path, "content must be an object");
        allowKeys(m, path, "format", "blocks");
        if (!FORMAT.equals(m.get("format"))) throw invalid(path, "unknown content format");
        if (!(m.get("blocks") instanceof List<?> blocks) || blocks.isEmpty()) throw invalid(path, "content needs at least one block");
        if (blocks.size() > 200) throw invalid(path, "too many blocks");
        List<Object> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ImageUse> images = new ArrayList<>();
        int[] chars = {0};
        for (int i = 0; i < blocks.size(); i++) {
            String bp = path + ".blocks[" + i + "]";
            if (!(blocks.get(i) instanceof Map<?, ?> b)) throw invalid(bp, "block must be an object");
            String type = String.valueOf(b.get("type"));
            switch (type) {
                case "paragraph" -> {
                    allowKeys(b, bp, "type", "runs");
                    out.add(Map.of("type", "paragraph", "runs", runs(b.get("runs"), bp, text, chars)));
                    text.append('\n');
                }
                case "math" -> {
                    allowKeys(b, bp, "type", "tex");
                    String tex = str(b.get("tex"), bp + ".tex");
                    TexGuard.check(tex, bp + ".tex");
                    chars[0] += tex.length();
                    text.append(tex).append('\n');
                    out.add(Map.of("type", "math", "tex", tex));
                }
                case "image" -> {
                    allowKeys(b, bp, "type", "asset", "alt", "decorative");
                    UUID asset;
                    try {
                        asset = UUID.fromString(str(b.get("asset"), bp + ".asset"));
                    } catch (IllegalArgumentException e) {
                        throw invalid(bp + ".asset", "not an asset id");
                    }
                    boolean decorative = Boolean.TRUE.equals(b.get("decorative"));
                    String alt = b.get("alt") == null ? null : String.valueOf(b.get("alt")).strip();
                    if (!decorative && (alt == null || alt.isEmpty())) throw invalid(bp + ".alt", "describe the image (alternative text) or mark it decorative");
                    if (alt != null && alt.length() > 250) throw invalid(bp + ".alt", "alternative text longer than 250 characters");
                    if (alt != null) checkText(alt, bp + ".alt");
                    images.add(new ImageUse(asset, decorative ? null : alt, decorative));
                    Map<String, Object> img = new LinkedHashMap<>();
                    img.put("type", "image");
                    img.put("asset", asset.toString());
                    img.put("decorative", decorative);
                    if (!decorative) img.put("alt", alt);
                    out.add(img);
                }
                case "list" -> {
                    allowKeys(b, bp, "type", "ordered", "items");
                    if (!(b.get("items") instanceof List<?> items) || items.isEmpty() || items.size() > 50) throw invalid(bp + ".items", "1–50 list items");
                    List<Object> its = new ArrayList<>();
                    for (int j = 0; j < items.size(); j++) {
                        String ip = bp + ".items[" + j + "]";
                        if (!(items.get(j) instanceof Map<?, ?> it)) throw invalid(ip, "item must be an object");
                        allowKeys(it, ip, "runs");
                        its.add(Map.of("runs", runs(it.get("runs"), ip, text, chars)));
                        text.append('\n');
                    }
                    out.add(Map.of("type", "list", "ordered", Boolean.TRUE.equals(b.get("ordered")), "items", its));
                }
                default -> throw invalid(bp + ".type", "unknown block type");
            }
        }
        if (chars[0] > maxChars) throw invalid(path, "content longer than " + maxChars + " characters");
        if (images.size() > maxImages) throw invalid(path, "at most " + maxImages + " images");
        return new Parsed(Map.of("format", FORMAT, "blocks", out), text.toString().strip(), images, chars[0]);
    }

    private static List<Object> runs(Object raw, String path, StringBuilder text, int[] chars) {
        if (!(raw instanceof List<?> runs) || runs.isEmpty() || runs.size() > 500) throw invalid(path + ".runs", "1–500 runs");
        List<Object> out = new ArrayList<>();
        for (int k = 0; k < runs.size(); k++) {
            String rp = path + ".runs[" + k + "]";
            if (!(runs.get(k) instanceof Map<?, ?> r)) throw invalid(rp, "run must be an object");
            if (r.containsKey("math")) {
                allowKeys(r, rp, "math");
                String tex = str(r.get("math"), rp + ".math");
                TexGuard.check(tex, rp + ".math");
                chars[0] += tex.length();
                text.append(' ').append(tex).append(' ');
                out.add(Map.of("math", tex));
            } else {
                allowKeys(r, rp, "text", "marks");
                String t = str(r.get("text"), rp + ".text");
                checkText(t, rp + ".text");
                chars[0] += t.length();
                text.append(t);
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("text", t);
                if (r.get("marks") != null) {
                    if (!(r.get("marks") instanceof List<?> ms)) throw invalid(rp + ".marks", "marks must be a list");
                    List<String> clean = new ArrayList<>();
                    for (Object mk : ms) {
                        if (!MARKS.contains(String.valueOf(mk))) throw invalid(rp + ".marks", "unknown mark");
                        if (!clean.contains(String.valueOf(mk))) clean.add(String.valueOf(mk));
                    }
                    if (!clean.isEmpty()) run.put("marks", clean);
                }
                out.add(run);
            }
        }
        return out;
    }

    /**
     * Source syntax used by imports and the editor's source mode: paragraphs separated by blank lines, {@code $…$} inline
     * math, {@code $$…$$} display math, {@code \$} for a literal dollar sign. Images are added in the editor only.
     */
    public static Map<String, Object> fromSource(String source) {
        List<Object> blocks = new ArrayList<>();
        String src = source == null ? "" : source.replace("\r\n", "\n").strip();
        for (String para : src.split("\n\\s*\n")) {
            if (para.isBlank()) continue;
            String p = para.strip();
            if (p.startsWith("$$") && p.endsWith("$$") && p.length() > 4 && p.indexOf("$$", 2) == p.length() - 2) {
                blocks.add(Map.of("type", "math", "tex", p.substring(2, p.length() - 2).strip()));
                continue;
            }
            List<Object> runs = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean math = false;
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                if (ch == '\\' && i + 1 < p.length() && p.charAt(i + 1) == '$') { cur.append('$'); i++; continue; }
                if (ch == '$') {
                    if (math) { if (!cur.isEmpty()) runs.add(Map.of("math", cur.toString())); }
                    else if (!cur.isEmpty()) runs.add(Map.of("text", cur.toString()));
                    cur.setLength(0);
                    math = !math;
                    continue;
                }
                cur.append(ch == '\n' ? ' ' : ch);
            }
            if (math) throw ApiException.badRequest("invalid_math", "unclosed $ in text");
            if (!cur.isEmpty()) runs.add(Map.of("text", cur.toString()));
            if (!runs.isEmpty()) blocks.add(Map.of("type", "paragraph", "runs", runs));
        }
        return Map.of("format", FORMAT, "blocks", blocks);
    }

    /**
     * Lower-case, Unicode-normalized, single-spaced text for duplicate detection. Only sentence punctuation is dropped:
     * mathematical symbols ({@code / + - = ^ _ { } \\ < > ( ) |} …) are kept, so formulas that differ only by an operator
     * are never treated as duplicates.
     */
    public static String normalize(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        n = n.replaceAll("[.,;:!?¡¿\"'“”‘’«»…]", " ").replaceAll("\\s+", " ").strip();
        return n;
    }

    private static void checkText(String t, String path) {
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if ((ch < 0x20 && ch != '\n' && ch != '\t') || (ch >= 0x7f && ch < 0xa0) || ch == ' ' || ch == ' ' || (ch >= '‪' && ch <= '‮')
                    || (ch >= '⁦' && ch <= '⁩')) {
                throw invalid(path, "control or direction-override character");
            }
        }
    }

    private static String str(Object v, String path) {
        if (!(v instanceof String s)) throw invalid(path, "text expected");
        return s;
    }

    private static void allowKeys(Map<?, ?> m, String path, String... allowed) {
        Set<String> ok = Set.of(allowed);
        for (Object k : m.keySet()) if (!ok.contains(String.valueOf(k))) throw invalid(path, "unknown property '" + k + "'");
    }

    static ApiException invalid(String path, String what) {
        return ApiException.badRequest("invalid_content", path + ": " + what + ".");
    }
}
