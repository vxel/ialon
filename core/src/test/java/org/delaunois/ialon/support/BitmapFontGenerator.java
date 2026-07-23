/**
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Offline authoring tool : bakes a TrueType/OpenType font into the BMFont pair the game consumes
 * ({@code .fnt} text descriptor + a single {@code .png} glyph page), using only {@code java.awt} —
 * no external tool, no network. It replaces the manual libgdx-Hiero round-trip that produced the
 * first UI font (see {@code local/resources/fonts/apex-mk2/apex.hiero}).
 *
 * <p>The emitted {@code .fnt} sticks to the subset of BMFont fields that the forked
 * {@link org.delaunois.ialon.blocks.jme.BitmapFontLoader} parses, and to the same conventions as the
 * previous font : a single page, glyph {@code x}/{@code y} measured from the top-left of the page,
 * and a zero-width {@code id=10} entry for the newline.
 *
 * <p><b>Accented letters are taken from the face when it has them, and synthesized otherwise.</b>
 * A text face like Jura draws them properly ; display faces frequently ship printable ASCII only —
 * Jack of Gears has no Latin-1 letter and no usable combining mark — so a missing accented character
 * is decomposed with {@link Normalizer} and re-composed here from the font's own ASCII marks
 * ({@code ` ' ^ ~ "} above, {@code ,} as a cedilla below), centred over the base letter's ink.
 * Bases that carry a dot ({@code i}, {@code j}) lose it, as proper typography requires.
 *
 * <p>Kerning is recovered by differencing {@link TextLayout} advances (see
 * {@link #kerningPairs}) and only covers characters the face really has.
 *
 * <p><b>A drop shadow is baked into every glyph</b> ({@link Baker#dropShadow}), so UI text stays
 * legible over any 3D background at no runtime cost — see that method for why black keeps working
 * whatever colour a label is tinted.
 *
 * <p>Run it through the Gradle task (paths are relative to the repo root) :
 * <pre>
 * ./gradlew :core:generateFont
 * ./gradlew :core:generateFont --args='--size=48 --accents=àâçéèêëîïôöùûü'
 * ./gradlew :core:generateFont --args='--ttf="core/src/test/resources/fonts/jack-of-gears/Jack of Gears Regular.ttf" --out=core/src/main/resources/Textures/font-jackofgears'
 * </pre>
 *
 * @author Cedric de Launois
 */
public class BitmapFontGenerator {

    private static final Logger log = LoggerFactory.getLogger(BitmapFontGenerator.class);

    /** Page sizes tried in order : the smallest square that fits the glyphs wins. */
    private static final int[] PAGE_SIZES = {256, 512, 1024};

    /**
     * Free pixels kept around every glyph cell, and around the page border. One pixel is enough to
     * stop the bilinear filter (the page is sampled Bilinear / BilinearNoMipMaps, see
     * {@code BitmapFontLoader}) from bleeding a neighbour's ink — or, at the border, a neighbouring
     * tile of the shared texture atlas — into a glyph's edge.
     */
    private static final int PADDING = 1;

    /** Printable ASCII : the common denominator of every face baked so far. */
    private static final char FIRST_GLYPH = 0x20;
    private static final char LAST_GLYPH = 0x7E;

    /**
     * Accented letters used by French, upper and lower case. Uppercase is included because UI labels
     * are capitalised ("À propos", "Éditer") and a missing glyph renders as nothing at all. {@code ù}
     * is in the set for "où". Everything else in ISO-8859-1 is left out on purpose — pass
     * {@code --accents=...} to bake a different set (any character whose NFD decomposition is a base
     * plus one of the supported marks works).
     */
    private static final String DEFAULT_ACCENTS = "àâäçèéêëîïôöùûüÀÂÄÇÈÉÊËÎÏÔÖÙÛÜ";

    // The source TTF lives under test resources (never packaged into the jar/APK, but committed, so the
    // bake is reproducible — 'local/' is gitignored).
    // Jura's Bold (weight 700) : the UI text is small and sits over a busy 3D scene, where the lighter
    // statics read thin. Medium (500) and SemiBold (600) sit next to it — a --ttf away.
    private static final String DEFAULT_TTF = "core/src/test/resources/fonts/jura/Jura-Bold.ttf";
    private static final String DEFAULT_OUT = "core/src/main/resources/Textures/font-jura";

    /**
     * The UI draws text at {@code 4*vh} (≈43 px at 1080p), so the page is baked at 44 px : big enough
     * that nothing is upscaled, small enough that the glyphs still pack into a 512x256 page.
     */
    private static final int DEFAULT_SIZE = 44;

    /** Bases whose dot must be removed before an accent is stacked on top. */
    private static final String DOTTED_BASES = "ij";

    /**
     * Drop of the baked shadow as a fraction of the text size (2 px at 44), and its opacity. Straight
     * down, no horizontal offset : that keeps every glyph cell exactly as wide as before, so the page
     * only grows downwards. Pass {@code --shadow=0} to bake a flat page.
     */
    private static final float DEFAULT_SHADOW = 1f / 22f;
    private static final float DEFAULT_SHADOW_ALPHA = 1f;

    // Combining marks (Unicode block U+0300) an NFD decomposition can yield, and that this face can
    // stand in for with one of its ASCII glyphs.
    private static final char MARK_GRAVE = 0x0300;
    private static final char MARK_ACUTE = 0x0301;
    private static final char MARK_CIRCUMFLEX = 0x0302;
    private static final char MARK_TILDE = 0x0303;
    private static final char MARK_DIAERESIS = 0x0308;
    private static final char MARK_CEDILLA = 0x0327;

    public static void main(String[] args) throws Exception {
        // Metrics and glyph rasterization work fine without a display, and the Gradle task may well
        // run on a headless box.
        System.setProperty("java.awt.headless", "true");

        Options options = Options.parse(args);
        log.info("Baking {} at {} px into {}.fnt / {}.png",
                options.ttf, options.size, options.out, options.out);

        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(options.ttf))
                .deriveFont((float) options.size);

        int shadow = options.shadow(options.size);
        Baker baker = new Baker(font, options.size, options.markScale, shadow, options.shadowAlpha);
        List<Glyph> glyphs = new ArrayList<>();
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < options.glyphs.length(); i++) {
            char c = options.glyphs.charAt(i);
            if (font.canDisplay(c)) {
                glyphs.add(baker.simple(c));
            } else {
                missing.append(c);
            }
        }
        if (missing.length() > 0) {
            throw new IllegalStateException("The font has no glyph for : \"" + missing + "\"");
        }
        glyphs.addAll(baker.accented(options.accents));

        // Line box, derived from the rasterised ink and not from FontMetrics alone : the synthesized
        // accents reach above the reported ascent, and would otherwise get a negative yoffset and be
        // clipped by their container.
        FontMetrics metrics = baker.metrics();
        int base = metrics.getAscent();
        int lineHeight = metrics.getHeight();
        for (Glyph glyph : glyphs) {
            if (!glyph.empty()) {
                base = Math.max(base, -glyph.bounds.y);
            }
        }
        for (Glyph glyph : glyphs) {
            if (!glyph.empty()) {
                // Minus the shadow : it sits below the ink, in every cell, so counting it would inflate
                // lineHeight (and with it every Lemur label's height) purely as a side effect of a visual
                // effect. Every ~43 hard-coded setFontSize is calibrated against this ratio.
                lineHeight = Math.max(lineHeight, base + glyph.bounds.y + glyph.bounds.height - shadow);
            }
        }

        List<int[]> kernings = options.kerning ? kerningPairs(font, baker.frc(), glyphs) : new ArrayList<>();

        int pageWidth = choosePage(glyphs, options.page);
        // The shelves rarely reach the bottom of a square page : trimming the height to the next power
        // of two saves atlas room and texture memory (both scarce on the Android target).
        int pageHeight = Math.min(pageWidth, nextPowerOfTwo(usedHeight(glyphs)));
        BufferedImage image = compose(glyphs, pageWidth, pageHeight, options.gamma);

        Path pngPath = Paths.get(options.out + ".png");
        Path fntPath = Paths.get(options.out + ".fnt");
        if (pngPath.getParent() != null) {
            Files.createDirectories(pngPath.getParent());
        }
        try (OutputStream out = Files.newOutputStream(pngPath)) {
            ImageIO.write(image, "png", out);
        }
        writeFnt(fntPath, pngPath.getFileName().toString(), font, glyphs, kernings,
                base, lineHeight, pageWidth, pageHeight, options.size);
        if (options.debug != null) {
            writeDebug(Paths.get(options.debug), image);
        }

        int ink = 0;
        for (Glyph glyph : glyphs) {
            if (!glyph.empty()) {
                ink += (glyph.bounds.width + PADDING) * (glyph.bounds.height + PADDING);
            }
        }
        log.info("Wrote {} ({}x{}, {} glyphs, {}% of the page inked) and {} ({} kerning pairs)",
                pngPath, pageWidth, pageHeight, glyphs.size(),
                (100 * ink) / (pageWidth * pageHeight), fntPath, kernings.size());
        log.info("Font metrics : size={} base={} lineHeight={} (awt ascent={} descent={} height={})",
                options.size, base, lineHeight,
                metrics.getAscent(), metrics.getDescent(), metrics.getHeight());
    }

    /**
     * Rasterization/measurement hints. Text antialiasing is what puts the glyph coverage in the alpha
     * channel, which is what lets Lemur tint the text through the material {@code Color} param.
     * Grayscale AA only — LCD/subpixel AA would tint the glyphs per channel and break that.
     */
    private static Graphics2D newGraphics(BufferedImage image, Font font) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setFont(font);
        g.setColor(Color.WHITE);
        return g;
    }

    /**
     * Rasterises glyphs one at a time into a scratch image and measures their ink from the pixels
     * themselves — ground truth, and the only way to measure a composed glyph, which does not exist
     * in the source font.
     */
    private static final class Baker {

        private final Font font;
        private final int size;
        /** Global multiplier on every {@link Composition#markScale}, for tuning ({@code --markscale}). */
        private final float markScale;
        /** Pixels the baked shadow drops by, {@code 0} for none, and its opacity. */
        private final int shadow;
        private final float shadowAlpha;
        private final BufferedImage scratch;
        private final Graphics2D g;
        private final FontRenderContext frc;
        private final FontMetrics metrics;
        /** Pen position (on the baseline) used for every raster, with room for accents and descenders. */
        private final int penX;
        private final int penY;
        /** Free pixels between a base letter's ink and its accent. */
        private final int markGap;
        /** Ink top of 'x' relative to the baseline (negative) : where a removed dot is cut off. */
        private final int xHeight;

        Baker(Font font, int size, float markScale, int shadow, float shadowAlpha) {
            this.font = font;
            this.size = size;
            this.markScale = markScale;
            this.shadow = shadow;
            this.shadowAlpha = shadowAlpha;
            this.scratch = new BufferedImage(4 * size, 4 * size, BufferedImage.TYPE_INT_ARGB);
            this.g = newGraphics(scratch, font);
            this.frc = g.getFontRenderContext();
            this.metrics = g.getFontMetrics();
            this.penX = size;
            this.penY = 2 * size;
            this.markGap = Math.max(1, size / 24);
            Rectangle x = measure(Collections.singletonList(new Stroke('x', font, 0, 0)), false);
            this.xHeight = x == null ? -metrics.getAscent() : x.y;
        }

        FontMetrics metrics() {
            return metrics;
        }

        FontRenderContext frc() {
            return frc;
        }

        /** A glyph the source font really has. */
        Glyph simple(char c) {
            List<Stroke> strokes = Collections.singletonList(new Stroke(c, font, 0, 0));
            Rectangle bounds = raster(strokes, false);
            if (bounds == null || Character.isWhitespace(c)) {
                // Whitespace : an advance and nothing else. Note getPixelBounds-style measurement would
                // report a 1x1 box for the space glyph, hence the explicit whitespace test.
                return new Glyph(c, advance(c), null, null);
            }
            return new Glyph(c, advance(c), bounds, capture(bounds));
        }

        /**
         * Bakes each requested accented letter : the face's own glyph when it has one, otherwise a
         * composition of its base and the face's matching ASCII mark.
         */
        List<Glyph> accented(String targets) {
            List<Glyph> glyphs = new ArrayList<>(targets.length());
            StringBuilder skipped = new StringBuilder();
            int fromFace = 0;
            for (int i = 0; i < targets.length(); i++) {
                char target = targets.charAt(i);
                if (font.canDisplay(target)) {
                    glyphs.add(simple(target));
                    fromFace++;
                    continue;
                }
                Composition composition = decompose(target);
                if (composition == null || !font.canDisplay(composition.base)
                        || !canDisplayAll(composition.mark)) {
                    skipped.append(target);
                    continue;
                }
                glyphs.add(compose(composition));
            }
            if (skipped.length() > 0) {
                log.warn("Not composable from this face, skipped : \"{}\"", skipped);
            }
            log.info("Accented glyphs : {} drawn by the face, {} composed from its own ASCII marks",
                    fromFace, glyphs.size() - fromFace);
            return glyphs;
        }

        private boolean canDisplayAll(String chars) {
            for (int i = 0; i < chars.length(); i++) {
                if (!font.canDisplay(chars.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private Glyph compose(Composition composition) {
            boolean dotless = DOTTED_BASES.indexOf(composition.base) >= 0;
            Font markFont = markFont(composition);
            Stroke baseStroke = new Stroke(composition.base, font, 0, 0);
            Rectangle baseInk = measure(Collections.singletonList(baseStroke), dotless);
            List<Stroke> markStrokes = markStrokes(composition.mark, markFont);
            Rectangle markInk = measure(markStrokes, false);

            // Centre the mark on the base letter's ink, then sit it just clear of the ink, above or below.
            int dx = centre(baseInk) - centre(markInk);
            int dy = composition.above
                    ? baseInk.y - markGap - markInk.y - markInk.height
                    : baseInk.y + baseInk.height + markGap - markInk.y;

            List<Stroke> strokes = new ArrayList<>(markStrokes.size() + 1);
            strokes.add(baseStroke);
            for (Stroke mark : markStrokes) {
                strokes.add(new Stroke(mark.c, mark.font, mark.dx + dx, mark.dy + dy));
            }
            Rectangle bounds = raster(strokes, dotless);
            // The accent rides along : the composed letter advances exactly like its base, so text with
            // and without accents stays aligned.
            return new Glyph(composition.target, advance(composition.base), bounds, capture(bounds));
        }

        /** The face at the mark's size, squashed and sheared if the diacritic calls for it. */
        private Font markFont(Composition composition) {
            Font sized = font.deriveFont(size * composition.markScale * markScale);
            if (composition.markSquash == 1f && composition.markShear == 0f) {
                return sized;
            }
            AffineTransform transform = AffineTransform.getShearInstance(composition.markShear, 0);
            transform.scale(1, composition.markSquash);
            return sized.deriveFont(transform);
        }

        /** Lays the mark's glyph(s) out side by side at the origin, so a diaeresis can be two dots. */
        private List<Stroke> markStrokes(String mark, Font markFont) {
            List<Stroke> strokes = new ArrayList<>(mark.length());
            int pen = 0;
            for (int i = 0; i < mark.length(); i++) {
                char c = mark.charAt(i);
                strokes.add(new Stroke(c, markFont, pen, 0));
                pen += Math.round(new TextLayout(String.valueOf(c), markFont, frc).getAdvance());
            }
            return strokes;
        }

        private static int centre(Rectangle ink) {
            return ink.x + ink.width / 2;
        }

        private int advance(char c) {
            return Math.round(new TextLayout(String.valueOf(c), font, frc).getAdvance());
        }

        /**
         * Rasters the strokes and adds the drop shadow : what a finished glyph cell holds. Returns the ink
         * box relative to the pen, {@code null} when nothing was drawn.
         */
        private Rectangle raster(List<Stroke> strokes, boolean dotless) {
            Rectangle ink = measure(strokes, dotless);
            if (ink == null || shadow == 0) {
                return ink;
            }
            dropShadow();
            // The shadow is the ink shifted straight down, so it extends the box by exactly that, and
            // only downwards — no need to re-scan the scratch.
            return new Rectangle(ink.x, ink.y, ink.width, ink.height + shadow);
        }

        /**
         * Clears the scratch, draws the strokes at the pen, whitens them, and returns the ink box relative
         * to the pen ({@code null} when nothing was drawn) — no shadow, so the accent composition can
         * measure a base letter and a mark without one polluting the other's box. When {@code dotless},
         * everything above the x-height is wiped right after the FIRST stroke — that removes an
         * {@code i}/{@code j} dot before the accent is stacked, without touching the accent itself.
         */
        private Rectangle measure(List<Stroke> strokes, boolean dotless) {
            fill(AlphaComposite.Clear, 0, scratch.getHeight());
            boolean first = true;
            for (Stroke stroke : strokes) {
                g.setFont(stroke.font);
                g.drawString(String.valueOf(stroke.c), penX + stroke.dx, penY + stroke.dy);
                if (first && dotless) {
                    fill(AlphaComposite.Clear, 0, penY + xHeight);
                }
                first = false;
            }
            Rectangle ink = ink();
            if (ink != null) {
                whiten(ink);
            }
            return ink;
        }

        /**
         * Forces the RGB of the ink to pure white, leaving the antialiased alpha alone : Java2D leaves
         * slightly off values behind, and only the alpha may carry the shape — that is what lets Lemur
         * tint the text through the material {@code Color} param.
         */
        private void whiten(Rectangle ink) {
            for (int y = penY + ink.y; y < penY + ink.y + ink.height; y++) {
                for (int x = penX + ink.x; x < penX + ink.x + ink.width; x++) {
                    scratch.setRGB(x, y, (scratch.getRGB(x, y) & 0xFF000000) | 0x00FFFFFF);
                }
            }
        }

        /**
         * Stamps a black copy of the ink {@code shadow} pixels lower, behind it, so UI text stays legible
         * over any 3D background. Baked in rather than drawn twice at runtime : the game's text shares one
         * material, and a second pass (or an outline shader sampling the page 4-8 times) would cost draw
         * calls and fragment work on the Android target for a purely static effect. Black survives the
         * tint because the font material MULTIPLIES the page by the text colour — zero stays zero — so
         * this one page still serves white, parchment and grey labels alike.
         */
        private void dropShadow() {
            int w = scratch.getWidth();
            int h = scratch.getHeight();
            int[] pixels = scratch.getRGB(0, 0, w, h, null, 0, w);
            int[] composited = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                float ink = (pixels[i] >>> 24) / 255f;
                float below = i < shadow * w ? 0f : shadowAlpha * ((pixels[i - shadow * w] >>> 24) / 255f);
                float alpha = ink + below * (1 - ink);
                if (alpha <= 0f) {
                    continue;
                }
                // White ink over a black shadow : premultiplied in linear light that is just the ink's own
                // coverage, so the texel's colour is the ratio below — sRGB-encoded, because the atlas page
                // is tagged sRGB and the shaders decode it (see the srgb-color-pipeline notes).
                int lum = Math.round(255f * srgb(ink / alpha));
                composited[i] = (Math.round(alpha * 255f) << 24) | (lum << 16) | (lum << 8) | lum;
            }
            scratch.setRGB(0, 0, w, h, composited, 0, w);
        }

        private void fill(AlphaComposite composite, int fromY, int toY) {
            Graphics2D fill = scratch.createGraphics();
            fill.setComposite(composite);
            fill.fillRect(0, fromY, scratch.getWidth(), toY - fromY);
            fill.dispose();
        }

        /** Tight bounding box of every non-transparent pixel, relative to the pen. */
        private Rectangle ink() {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int y = 0; y < scratch.getHeight(); y++) {
                for (int x = 0; x < scratch.getWidth(); x++) {
                    if ((scratch.getRGB(x, y) >>> 24) != 0) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            if (minX > maxX) {
                return null;
            }
            return new Rectangle(minX - penX, minY - penY, maxX - minX + 1, maxY - minY + 1);
        }

        /**
         * Copies the finished box out of the scratch, verbatim : {@link #whiten} has already forced the ink
         * to white, and {@link #dropShadow} owns whatever greys the shadow needs.
         */
        private int[] capture(Rectangle bounds) {
            return scratch.getRGB(penX + bounds.x, penY + bounds.y,
                    bounds.width, bounds.height, null, 0, bounds.width);
        }
    }

    /** sRGB transfer function, for the one place that writes a colour rather than plain coverage. */
    private static float srgb(float linear) {
        return linear <= 0.0031308f
                ? 12.92f * linear
                : 1.055f * (float) Math.pow(linear, 1 / 2.4) - 0.055f;
    }

    /**
     * Splits an accented character into a base letter and one of the ASCII marks the face carries.
     * {@link Normalizer} does the work, so any precomposed character with a supported mark is handled;
     * anything else returns {@code null} and is reported rather than approximated.
     */
    private static Composition decompose(char target) {
        String nfd = Normalizer.normalize(String.valueOf(target), Normalizer.Form.NFD);
        if (nfd.length() != 2) {
            return null;
        }
        char base = nfd.charAt(0);
        switch (nfd.charAt(1)) {
            // Acute and grave are the solid vertical bar, squashed to accent length and sheared each way.
            // The face's own quote glyphs are useless here : ' is a 2px hairline and ` a square blob, so
            // neither reads as an accent nor tells è from é. A shrunk slash stays a hairline too — only
            // the bar keeps its weight, because squashing shortens the stroke without thinning it.
            case MARK_GRAVE:
                return Composition.slanted(target, base, 0.55f);
            case MARK_ACUTE:
                return Composition.slanted(target, base, -0.55f);
            case MARK_CIRCUMFLEX:
                return new Composition(target, base, "^", true, 1f);
            case MARK_TILDE:
                return new Composition(target, base, "~", true, 1.1f);
            // A scaled-up double quote reads as two tall bars, not as a diaeresis : two periods do.
            case MARK_DIAERESIS:
                return new Composition(target, base, "..", true, 0.75f);
            case MARK_CEDILLA:
                return new Composition(target, base, ",", false, 1.1f);
            default:
                return null;
        }
    }

    /**
     * Recovers the kerning pairs by differencing advances : {@code java.awt} exposes no kerning table,
     * but a {@link TextLayout} built on a font carrying {@code TextAttribute.KERNING_ON} applies the
     * face's legacy {@code kern} table, so {@code advance("AV") - advance("A") - advance("V")} is the
     * pair's kerning. Note {@code Font.createGlyphVector} does NOT apply it — it maps glyphs without
     * layout, which is why it reports no kerning at all.
     *
     * <p>Faces that carry only OpenType GPOS (which Java2D ignores) simply yield no pairs, and none are
     * written — better than faking them. Only characters the face really has take part : the composed
     * accented glyphs are unknown to the layout engine, and {@code BitmapFontLoader} looks the first
     * character of a pair up without a null check.
     */
    private static List<int[]> kerningPairs(Font font, FontRenderContext frc, List<Glyph> glyphs) {
        Map<TextAttribute, Object> kerned = new HashMap<>();
        kerned.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        Font kernFont = font.deriveFont(kerned);

        Map<Character, Double> advances = new HashMap<>();
        List<Character> chars = new ArrayList<>(glyphs.size());
        for (Glyph glyph : glyphs) {
            if (Character.isWhitespace(glyph.c) || !font.canDisplay(glyph.c)) {
                continue;
            }
            chars.add(glyph.c);
            advances.put(glyph.c, (double) new TextLayout(String.valueOf(glyph.c), font, frc).getAdvance());
        }

        List<int[]> pairs = new ArrayList<>();
        for (Character first : chars) {
            for (Character second : chars) {
                double pair = new TextLayout("" + first + second, kernFont, frc).getAdvance();
                int amount = (int) Math.round(pair - advances.get(first) - advances.get(second));
                if (amount != 0) {
                    pairs.add(new int[]{first, second, amount});
                }
            }
        }
        return pairs;
    }

    /**
     * Shelf packing : tallest glyphs first, then left to right until the row is full. The glyph set is
     * small and homogeneous enough that this wastes only a few percent of the page — no need for the
     * guillotine tree the texture atlas uses.
     */
    private static int choosePage(List<Glyph> glyphs, int forced) {
        List<Glyph> byHeight = new ArrayList<>(glyphs);
        byHeight.sort(Comparator.comparingInt(Glyph::height).reversed());
        for (int page : PAGE_SIZES) {
            if (forced > 0 && page != forced) {
                continue;
            }
            if (pack(byHeight, page)) {
                return page;
            }
        }
        throw new IllegalStateException("The glyphs do not fit in a "
                + (forced > 0 ? forced + "px" : PAGE_SIZES[PAGE_SIZES.length - 1] + "px")
                + " page : lower --size or shrink --glyphs / --accents.");
    }

    private static boolean pack(List<Glyph> byHeight, int page) {
        int x = PADDING;
        int y = PADDING;
        int rowHeight = 0;
        for (Glyph glyph : byHeight) {
            if (glyph.empty()) {
                glyph.x = 0;
                glyph.y = 0;
                continue;
            }
            if (x + glyph.bounds.width + PADDING > page) {
                x = PADDING;
                y += rowHeight + PADDING;
                rowHeight = 0;
            }
            if (y + glyph.bounds.height + PADDING > page) {
                return false;
            }
            glyph.x = x;
            glyph.y = y;
            x += glyph.bounds.width + PADDING;
            rowHeight = Math.max(rowHeight, glyph.bounds.height);
        }
        return true;
    }

    private static int usedHeight(List<Glyph> glyphs) {
        int bottom = 0;
        for (Glyph glyph : glyphs) {
            if (!glyph.empty()) {
                bottom = Math.max(bottom, glyph.y + glyph.bounds.height + PADDING);
            }
        }
        return bottom;
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    private static BufferedImage compose(List<Glyph> glyphs, int width, int height, float gamma) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (Glyph glyph : glyphs) {
            if (!glyph.empty()) {
                // Raw pixel copy, not drawImage : nothing must recomposite the antialiased alpha.
                image.setRGB(glyph.x, glyph.y, glyph.bounds.width, glyph.bounds.height,
                        glyph.pixels, 0, glyph.bounds.width);
            }
        }
        if (gamma != 1f) {
            applyGamma(image, gamma);
        }
        return image;
    }

    /**
     * Optional thickening / thinning of the antialiased edges, applied to the alpha channel only (the
     * RGB is a flat white). Hiero baked a 1.8 gamma into the previous font ; the game now runs an sRGB
     * pipeline, so the default here is a plain linear coverage and this stays an escape hatch.
     */
    private static void applyGamma(BufferedImage image, float gamma) {
        int[] lut = new int[256];
        for (int i = 0; i < lut.length; i++) {
            lut[i] = Math.round(255f * (float) Math.pow(i / 255f, 1f / gamma));
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = lut[(argb >>> 24) & 0xFF];
                image.setRGB(x, y, (alpha << 24) | (argb & 0x00FFFFFF));
            }
        }
    }

    /**
     * Flattens the (white on transparent) page over a dark background so it can be eyeballed in an
     * image viewer, where the real page just looks blank. Authoring aid only — never shipped.
     */
    private static void writeDebug(Path path, BufferedImage page) throws IOException {
        BufferedImage flat = new BufferedImage(page.getWidth(), page.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        g.setColor(new Color(0x20, 0x22, 0x28));
        g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
        g.drawImage(page, 0, 0, null);
        g.dispose();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (OutputStream out = Files.newOutputStream(path)) {
            ImageIO.write(flat, "png", out);
        }
        log.info("Wrote debug page {}", path);
    }

    private static void writeFnt(Path path, String pngName, Font font, List<Glyph> glyphs,
                                 List<int[]> kernings, int base, int lineHeight,
                                 int pageWidth, int pageHeight, int size) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("info face=\"").append(font.getFontName()).append("\" size=").append(size)
                .append(" bold=0 italic=0 charset=\"\" unicode=1 stretchH=100 smooth=1 aa=1")
                .append(" padding=0,0,0,0 spacing=").append(PADDING).append(',').append(PADDING)
                .append('\n');
        sb.append("common lineHeight=").append(lineHeight)
                .append(" base=").append(base)
                .append(" scaleW=").append(pageWidth).append(" scaleH=").append(pageHeight)
                .append(" pages=1 packed=0\n");
        sb.append("page id=0 file=\"").append(pngName).append("\"\n");
        // +1 : the newline entry below. BitmapText handles '\n' itself but the character must exist.
        sb.append("chars count=").append(glyphs.size() + 1).append('\n');
        appendChar(sb, 10, 0, 0, 0, 0, -1, 0, 0);
        for (Glyph glyph : glyphs) {
            if (glyph.empty()) {
                // Whitespace : an advance and nothing else, like the previous font's space entry.
                appendChar(sb, glyph.c, 0, 0, 0, 0, 0, 0, glyph.advance);
            } else {
                appendChar(sb, glyph.c, glyph.x, glyph.y, glyph.bounds.width, glyph.bounds.height,
                        glyph.bounds.x, base + glyph.bounds.y, glyph.advance);
            }
        }
        sb.append("kernings count=").append(kernings.size()).append('\n');
        for (int[] kerning : kernings) {
            sb.append("kerning first=").append(kerning[0])
                    .append(" second=").append(kerning[1])
                    .append(" amount=").append(kerning[2])
                    .append('\n');
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendChar(StringBuilder sb, int id, int x, int y, int width, int height,
                                   int xOffset, int yOffset, int xAdvance) {
        sb.append("char id=").append(id)
                .append(" x=").append(x)
                .append(" y=").append(y)
                .append(" width=").append(width)
                .append(" height=").append(height)
                .append(" xoffset=").append(xOffset)
                .append(" yoffset=").append(yOffset)
                .append(" xadvance=").append(xAdvance)
                .append(" page=0 chnl=0\n");
    }

    /** One draw operation inside a glyph cell : a character, at an offset from the glyph's pen. */
    private static final class Stroke {
        final char c;
        final Font font;
        final int dx;
        final int dy;

        Stroke(char c, Font font, int dx, int dy) {
            this.c = c;
            this.font = font;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** An accented character expressed as a base letter plus one or more of the face's ASCII glyphs. */
    private static final class Composition {
        final char target;
        final char base;
        /** The glyph(s) standing in for the diacritic, drawn side by side when there are several. */
        final String mark;
        /** Above the base letter's ink (accents) or below it (cedilla). */
        final boolean above;
        /**
         * Size of the mark relative to the text size. The stand-ins are punctuation drawn at text size,
         * so they need scaling in both directions : a full-height slash must shrink to accent size, a
         * period must grow to read as a dot.
         */
        final float markScale;
        /** Vertical squash applied to the mark, to shorten a stroke without thinning it. */
        final float markSquash;
        /** Horizontal shear applied to the mark : negative leans the top right (acute), positive left. */
        final float markShear;

        Composition(char target, char base, String mark, boolean above, float markScale) {
            this(target, base, mark, above, markScale, 1f, 0f);
        }

        Composition(char target, char base, String mark, boolean above, float markScale,
                    float markSquash, float markShear) {
            this.target = target;
            this.base = base;
            this.mark = mark;
            this.above = above;
            this.markScale = markScale;
            this.markSquash = markSquash;
            this.markShear = markShear;
        }

        /** An acute / grave built from the vertical bar : two thirds of the text size, squashed flat. */
        static Composition slanted(char target, char base, float shear) {
            return new Composition(target, base, "|", true, 0.68f, 0.42f, shear);
        }
    }

    private static final class Glyph {
        final char c;
        final int advance;
        /** Ink box relative to the pen on the baseline ; {@code null} for whitespace. */
        final Rectangle bounds;
        final int[] pixels;
        int x;
        int y;

        Glyph(char c, int advance, Rectangle bounds, int[] pixels) {
            this.c = c;
            this.advance = advance;
            this.bounds = bounds;
            this.pixels = pixels;
        }

        boolean empty() {
            return bounds == null;
        }

        int height() {
            return bounds == null ? 0 : bounds.height;
        }
    }

    private static class Options {
        String ttf = DEFAULT_TTF;
        String out = DEFAULT_OUT;
        int size = DEFAULT_SIZE;
        int page = 0;
        float gamma = 1f;
        float markScale = 1f;
        /** {@code null} until {@code --shadow} overrides it : the default scales with the text size. */
        Integer shadowDrop = null;
        float shadowAlpha = DEFAULT_SHADOW_ALPHA;
        boolean kerning = true;
        String glyphs = defaultGlyphs();
        String accents = DEFAULT_ACCENTS;
        String debug = null;

        /** Shadow drop in page pixels : proportional to the text size unless {@code --shadow} says otherwise. */
        int shadow(int size) {
            return shadowDrop != null ? shadowDrop : Math.max(1, Math.round(size * DEFAULT_SHADOW));
        }

        static Options parse(String[] args) {
            Options options = new Options();
            for (String arg : args) {
                int eq = arg.indexOf('=');
                if (!arg.startsWith("--") || eq < 0) {
                    throw new IllegalArgumentException("Expected --key=value arguments, got : " + arg);
                }
                String key = arg.substring(2, eq);
                String value = unquote(arg.substring(eq + 1));
                switch (key) {
                    case "ttf":
                        options.ttf = value;
                        break;
                    case "out":
                        options.out = value;
                        break;
                    case "size":
                        options.size = Integer.parseInt(value);
                        break;
                    case "page":
                        options.page = Integer.parseInt(value);
                        break;
                    case "gamma":
                        options.gamma = Float.parseFloat(value);
                        break;
                    case "markscale":
                        options.markScale = Float.parseFloat(value);
                        break;
                    case "shadow":
                        options.shadowDrop = Integer.parseInt(value);
                        break;
                    case "shadowalpha":
                        options.shadowAlpha = Float.parseFloat(value);
                        break;
                    case "glyphs":
                        options.glyphs = value;
                        break;
                    case "accents":
                        options.accents = value;
                        break;
                    case "debug":
                        options.debug = value;
                        break;
                    case "kerning":
                        options.kerning = Boolean.parseBoolean(value);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option --" + key);
                }
            }
            return options;
        }

        private static String unquote(String value) {
            if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private static String defaultGlyphs() {
            StringBuilder sb = new StringBuilder();
            for (char c = FIRST_GLYPH; c <= LAST_GLYPH; c++) {
                sb.append(c);
            }
            return sb.toString();
        }
    }
}
