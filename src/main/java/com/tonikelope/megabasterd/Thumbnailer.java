/*
 * mega-downloader
 * Originally MegaBasterd, © tonikelope since 2016. GPLv3.
 */
package com.tonikelope.megabasterd;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * Video thumbnails are grabbed by shelling out to {@code ffprobe}/{@code
 * ffmpeg} instead of the Xuggler Java bindings this class used to wrap.
 * Xuggler's `xuggle-xuggler-server-all` dependency was an unmaintained
 * third-party SNAPSHOT with no confirmed Linux native support -- a poor fit
 * for a Docker image. `ffmpeg` is a standard, well-maintained `apt` package
 * available on essentially every Linux distro (and via winget/brew for local
 * dev), so one subprocess call replaces the whole native-binding dependency
 * tree. Image thumbnails (createImageThumbnail) never depended on Xuggler
 * and are unchanged.
 */
public class Thumbnailer {

    private static final Logger LOG = Logger.getLogger(Thumbnailer.class.getName());

    public Thumbnailer() {
    }

    public static final int IMAGE_THUMB_SIZE = 250;

    public static final float SECONDS_BETWEEN_FRAMES_PERC = 0.03f; //Take frame video at 3% position

    private static final int PROBE_TIMEOUT_SECS = 15;
    private static final int FFMPEG_TIMEOUT_SECS = 30;

    public String createThumbnail(String filename) {
        try {
            if (MiscTools.isVideoFile(filename)) {

                return createVideoThumbnail(filename);

            } else if (MiscTools.isImageFile(filename)) {

                return createImageThumbnail(filename);

            }
        } catch (Exception ex) {
        }

        return null;
    }

    private String createImageThumbnail(String filename) {

        try {

            BufferedImage imagen_original = ImageIO.read(new File(filename));

            if (imagen_original.getHeight() <= IMAGE_THUMB_SIZE) {
                return filename;
            }

            int h = IMAGE_THUMB_SIZE;

            int w = Math.round((((float) imagen_original.getWidth()) * h) / imagen_original.getHeight());

            BufferedImage newImage = new BufferedImage(w, h, imagen_original.getType());

            Graphics2D g = newImage.createGraphics();

            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            g.drawImage(imagen_original, 0, 0, w, h, null);

            g.dispose();

            File file = File.createTempFile("megabasterd_thumbnail_" + MiscTools.genID(20), ".png");

            file.deleteOnExit();

            ImageIO.write(newImage, "png", file);

            return file.getAbsolutePath();

        } catch (Exception ex) {
            Logger.getLogger(Thumbnailer.class.getName()).log(Level.SEVERE, null, ex);
        }

        return null;
    }

    /**
     * Grabs a single frame at ~3% into the video (SECONDS_BETWEEN_FRAMES_PERC,
     * matching the old Xuggler-based behaviour) via ffprobe (duration) +
     * ffmpeg (frame extraction), and writes it out as a JPG in the system
     * temp directory. Returns null if ffprobe/ffmpeg aren't on PATH, the
     * file has no readable duration, or extraction otherwise fails --
     * callers already treat a null thumbnail as "skip it", same as before.
     */
    private String createVideoThumbnail(String filename) {

        Double duration_secs = probeDurationSeconds(filename);

        if (duration_secs == null || duration_secs <= 0) {
            return null;
        }

        double seek_secs = duration_secs * SECONDS_BETWEEN_FRAMES_PERC;

        File out;
        try {
            out = File.createTempFile("megabasterd_thumbnail_" + MiscTools.genID(20), ".jpg");
            out.deleteOnExit();
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Could not create temp file for video thumbnail: {0}", ex.getMessage());
            return null;
        }

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-ss", String.format(java.util.Locale.ROOT, "%.3f", seek_secs),
                "-i", filename,
                "-frames:v", "1",
                "-q:v", "2",
                out.getAbsolutePath());
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();

            // Drain stdout/stderr so ffmpeg's pipe never fills and blocks it
            // mid-extraction -- we don't care about the output, just that
            // the process isn't stalled waiting for a reader.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // discard
                }
            }

            boolean finished = p.waitFor(FFMPEG_TIMEOUT_SECS, TimeUnit.SECONDS);

            if (!finished) {
                p.destroyForcibly();
                LOG.log(Level.WARNING, "ffmpeg thumbnail extraction timed out for {0}", filename);
                return null;
            }

            if (p.exitValue() != 0 || !out.exists() || out.length() == 0) {
                LOG.log(Level.WARNING, "ffmpeg thumbnail extraction failed for {0} (exit {1})",
                        new Object[]{filename, p.exitValue()});
                return null;
            }

            return out.getAbsolutePath();

        } catch (Exception ex) {
            LOG.log(Level.WARNING, "ffmpeg not available or failed for {0}: {1}", new Object[]{filename, ex.getMessage()});
            return null;
        }
    }

    private Double probeDurationSeconds(String filename) {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                filename);
        pb.redirectErrorStream(false);

        try {
            Process p = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }

            boolean finished = p.waitFor(PROBE_TIMEOUT_SECS, TimeUnit.SECONDS);

            if (!finished) {
                p.destroyForcibly();
                return null;
            }

            if (p.exitValue() != 0 || output == null || output.trim().isEmpty()) {
                return null;
            }

            return Double.parseDouble(output.trim());

        } catch (Exception ex) {
            LOG.log(Level.WARNING, "ffprobe not available or failed for {0}: {1}", new Object[]{filename, ex.getMessage()});
            return null;
        }
    }
}
