package androidx.media3.mpvplayer;

import java.util.Locale;
import java.util.regex.Pattern;

final class MpvDiagnosticsPolicy {

    enum Request {
        PLAYBACK,
        PANEL,
        DEBUG_LOG,
        ERROR_MINIMAL,
        ERROR_DETAILED
    }

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://[^\\s\\]\\[\\\"'<>]+");
    private static final Pattern SENSITIVE_HEADER = Pattern.compile("(?i)\\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+");

    private MpvDiagnosticsPolicy() {
    }

    static boolean allowsSynchronousProperties(Request request, boolean debugLogEnabled) {
        if (request == null) return false;
        return switch (request) {
            case PANEL, PLAYBACK, ERROR_MINIMAL -> false;
            case DEBUG_LOG, ERROR_DETAILED -> debugLogEnabled;
        };
    }

    static String sourceSummary(String source) {
        String value = source == null ? "" : source.trim();
        String scheme = scheme(value);
        return "scheme=" + (scheme.isEmpty() ? "-" : scheme) + " urlLen=" + value.length();
    }

    static String redactSensitive(String text) {
        if (text == null || text.isEmpty()) return "";
        String safe = URL.matcher(text).replaceAll("<url>");
        return SENSITIVE_HEADER.matcher(safe).replaceAll("$1=<redacted>");
    }

    /** Persist startup/failure evidence without waiting for the Android main queue. */
    static boolean isFatalFelLog(int level, String text) {
        return level > 0 && level <= 20 && text != null
                && text.trim().startsWith("WebHTV FEL fatal:");
    }

    static boolean shouldLogNativeImmediately(int level, String line) {
        // mpv: fatal=10, error=20, warn=30. In particular "failing hardware
        // decode" must not disappear just because it doesn't contain "failed".
        return line != null && !line.isEmpty()
                && (level > 0 && level <= 30 || shouldLogNativeImmediately(line));
    }

    static final class NativeLogWindow {
        private long startMs = -1;
        private int count;
        private int suppressed;

        boolean allow(long nowMs, String line) {
            if (startMs < 0 || nowMs - startMs >= 5000 || nowMs < startMs) {
                startMs = nowMs;
                count = 0;
            }
            if (++count <= 32 || line.contains("WebHTV FEL fatal:")) return true;
            suppressed++;
            return false;
        }

        int takeSuppressed() {
            int result = suppressed;
            suppressed = 0;
            return result;
        }
    }

    static boolean shouldLogNativeImmediately(String line) {
        if (line == null || line.isEmpty()) return false;
        String lower = line.toLowerCase(Locale.US);
        return lower.contains("webhtv android fel:")
                || lower.contains("webhtv fel ")
                || lower.contains("dolby vision")
                || lower.contains("dovi")
                || lower.contains("nlq")
                || lower.contains("mediacodec started successfully")
                || lower.contains("using hardware decoding")
                || lower.contains("using software decoding")
                || lower.contains("decoder format:")
                || lower.contains("device name:")
                || lower.contains("vo: [gpu-next]")
                || lower.contains("error")
                || lower.contains("failed")
                || lower.contains("invalid");
    }

    private static String scheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) return "";
        for (int i = 0; i < colon; i++) {
            char c = value.charAt(i);
            if (i == 0 && !Character.isLetter(c)) return "";
            if (i > 0 && !Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') return "";
        }
        return value.substring(0, colon).toLowerCase(Locale.US);
    }
}
