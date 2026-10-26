package com.waypoint.service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Local OCR in a separate process, independent of the JVM's native architecture. */
class TimesheetOcrService {
    boolean isAvailable() {
        try {
            Process process = new ProcessBuilder(executable(), "--version")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    String read(BufferedImage image) throws IOException {
        BufferedImage target = image;
        boolean preprocessed = false;
        if ((long) image.getWidth() * image.getHeight() < 1_500_000) {
            target = preprocessImage(image);
            preprocessed = true;
        }
        try {
            String original = recognize(target, "3");
            BufferedImage cleaned = removeGridLines(target);
            String text;
            try { text = recognize(cleaned, "6"); }
            finally { cleaned.flush(); }
            // Multi-line column headings are often clearer before the grid is removed.
            var heading = java.util.regex.Pattern.compile("(?is)day of week.{0,300}?regular.{0,100}?overtime.{0,100}?hours")
                    .matcher(original);
            if (heading.find()) {
                return "Day of Week Regular Hours Overtime Hours\n" + text;
            }
            return original.isBlank() ? text : original;
        } finally {
            if (preprocessed) {
                target.flush();
            }
        }
    }

    private BufferedImage preprocessImage(BufferedImage source) {
        int width = source.getWidth(), height = source.getHeight();
        double scale = 1.0;
        if (width < 2200 && height < 2200) {
            scale = Math.min(2.5, 2600.0 / Math.max(width, height));
        }
        int newW = (int) Math.round(width * scale);
        int newH = (int) Math.round(height * scale);

        BufferedImage processed = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        var graphics = processed.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, newW, newH, null);
        } finally {
            graphics.dispose();
        }

        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int rgb = processed.getRGB(x, y);
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;
                int gray = (r * 299 + g * 587 + b * 114) / 1000;
                int stretched;
                if (gray > 230) {
                    stretched = 255;
                } else if (gray < 160) {
                    stretched = 0;
                } else {
                    stretched = (gray - 160) * 255 / 70;
                }
                processed.setRGB(x, y, (stretched << 16) | (stretched << 8) | stretched);
            }
        }
        return processed;
    }

    private BufferedImage removeGridLines(BufferedImage source) {
        int width = source.getWidth(), height = source.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
            graphics.setColor(java.awt.Color.WHITE);
            for (int y = 0; y < height; y++) {
                int start = -1;
                for (int x = 0; x <= width; x++) {
                    boolean dark = x < width && dark(source.getRGB(x, y));
                    if (dark && start < 0) start = x;
                    if (!dark && start >= 0) {
                        if (x - start > width / 8) graphics.fillRect(start, y - 2, x - start, 5);
                        start = -1;
                    }
                }
            }
            for (int x = 0; x < width; x++) {
                int start = -1;
                for (int y = 0; y <= height; y++) {
                    boolean dark = y < height && dark(source.getRGB(x, y));
                    if (dark && start < 0) start = y;
                    if (!dark && start >= 0) {
                        if (y - start > height / 8) graphics.fillRect(x - 2, start, 5, y - start);
                        start = -1;
                    }
                }
            }
        } finally { graphics.dispose(); }
        return result;
    }

    private boolean dark(int rgb) {
        return ((rgb >> 16) & 255) < 160 && ((rgb >> 8) & 255) < 160 && (rgb & 255) < 160;
    }

    private String recognize(BufferedImage image, String segmentation) throws IOException {
        Path directory = Files.createTempDirectory("waypoint-timesheet-ocr-");
        Path input = directory.resolve("page.png");
        Path output = directory.resolve("result.txt");
        Path errors = directory.resolve("errors.txt");
        Process process = null;
        try {
            ImageIO.write(image, "png", input.toFile());
            process = new ProcessBuilder(executable(), input.toString(), directory.resolve("result").toString(),
                    "-l", "eng", "--psm", segmentation)
                    .redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("OCR timed out");
            }
            if (process.exitValue() != 0 || !Files.exists(output)) throw new IOException("OCR could not read this page");
            if (Files.size(output) > 1_000_000) throw new IOException("OCR text exceeds import limit");
            return Files.readString(output, StandardCharsets.UTF_8);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("OCR interrupted", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(errors);
            Files.deleteIfExists(directory);
        }
    }

    private String executable() {
        String configured = System.getenv("TIMESHEET_OCR_EXECUTABLE");
        if (configured != null && !configured.isBlank()) return configured;
        if (System.getProperty("os.name").startsWith("Windows")) {
            for (String base : new String[]{System.getenv("LOCALAPPDATA"), System.getenv("ProgramFiles")}) {
                if (base == null) continue;
                for (String relative : new String[]{"Programs/Tesseract-OCR/tesseract.exe", "Tesseract-OCR/tesseract.exe"}) {
                    Path candidate = Path.of(base, relative);
                    if (Files.isRegularFile(candidate)) return candidate.toString();
                }
            }
        }
        return "tesseract";
    }
}
