package com.ivorovi.questions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ivorovi.platform.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

/** Image intake: magic bytes (not extension or declared type), dimension limits, and metadata removal by re-encoding. */
class AssetInspectionTest {

    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** PNG with a tEXt chunk (stands in for camera/location metadata). */
    static byte[] pngWithText(String keyword, String value) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ImageWriter w = ImageIO.getImageWritersByFormatName("png").next();
        IIOMetadata meta = w.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(img), null);
        IIOMetadataNode text = new IIOMetadataNode("tEXt");
        IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
        entry.setAttribute("keyword", keyword);
        entry.setAttribute("value", value);
        text.appendChild(entry);
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
        root.appendChild(text);
        meta.mergeTree("javax_imageio_png_1.0", root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(new IIOImage(img, null, meta));
        }
        return out.toByteArray();
    }

    @Test
    void pngAndJpegAreRecognisedFromTheirBytes() throws Exception {
        assertThat(AssetService.inspect(png(30, 20))).isEqualTo(new AssetService.Inspected("image/png", 30, 20));
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 9, BufferedImage.TYPE_INT_RGB), "jpeg", jpg);
        assertThat(AssetService.inspect(jpg.toByteArray()).mediaType()).isEqualTo("image/jpeg");
    }

    @Test
    void svgGifHtmlAndPolyglotsAreRefused() throws Exception {
        assertThatThrownBy(() -> AssetService.inspect("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "unsupported_image");
        assertThatThrownBy(() -> AssetService.inspect("GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AssetService.inspect("<html><script>x</script></html>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
        // PNG signature followed by garbage: refused as unreadable
        byte[] fake = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}, 0, fake, 0, 8);
        assertThatThrownBy(() -> AssetService.inspect(fake)).isInstanceOf(ApiException.class);
    }

    @Test
    void dimensionsAboveTheLimitAreRefusedBeforeDecoding() throws Exception {
        assertThatThrownBy(() -> AssetService.inspect(png(4097, 10))).isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "image_too_large");
        assertThat(AssetService.inspect(png(4096, 1)).width()).isEqualTo(4096);
    }

    @Test
    void reencodingDropsMetadataAndTrailingData() throws Exception {
        byte[] withMeta = pngWithText("GPSLatitude", "51.5072N-secret-location");
        assertThat(new String(withMeta, StandardCharsets.ISO_8859_1)).contains("secret-location");
        byte[] trailing = new byte[withMeta.length + 20];
        System.arraycopy(withMeta, 0, trailing, 0, withMeta.length);
        System.arraycopy("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), 0, trailing, withMeta.length, 20);
        byte[] clean = AssetService.reencode(trailing, "image/png");
        String s = new String(clean, StandardCharsets.ISO_8859_1);
        assertThat(s).doesNotContain("secret-location").doesNotContain("GPSLatitude").doesNotContain("<script");
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(clean))) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            ImageReader r = it.next();
            r.setInput(in);
            assertThat(r.getWidth(0)).isEqualTo(8);
        }
    }

    @Test
    void uploadsFailClosedWithoutAScannerOutsideDevelopment() {
        assertThat(AssetService.uploadsAllowed(com.ivorovi.platform.config.DeploymentProperties.Environment.PRODUCTION, false)).isFalse();
        assertThat(AssetService.uploadsAllowed(com.ivorovi.platform.config.DeploymentProperties.Environment.STAGING, false)).isFalse();
        assertThat(AssetService.uploadsAllowed(com.ivorovi.platform.config.DeploymentProperties.Environment.PRODUCTION, true)).isTrue();
        assertThat(AssetService.uploadsAllowed(com.ivorovi.platform.config.DeploymentProperties.Environment.DEVELOPMENT, false)).isTrue();
    }
}
