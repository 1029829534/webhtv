package com.github.catvod.crawler.diagnostics;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.function.LongSupplier;

/** In-process pairing. Codes/tokens are never persisted or included in a URL or diagnostic event. */
public final class DiagnosticAccess {
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private String code, token;
    private long codeUntil, tokenUntil, attemptWindow, actionWindow;
    private int attempts, actions;
    public DiagnosticAccess() { this(() -> System.nanoTime() / 1_000_000); }
    public DiagnosticAccess(LongSupplier clock) { this.clock = clock; }

    public synchronized String localPairingCode() {
        long now = clock.getAsLong();
        if (code == null || now >= codeUntil) { code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000)); codeUntil = now + 600_000; }
        return code;
    }

    public synchronized String pair(String supplied) {
        long now = clock.getAsLong();
        if (now - attemptWindow >= 60_000) { attemptWindow = now; attempts = 0; }
        if (++attempts > 5 || code == null || now >= codeUntil || !same(code, supplied)) return null;
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); tokenUntil = now + 900_000;
        code = null; codeUntil = 0;
        return token;
    }

    public synchronized boolean authorize(String supplied) {
        long now = clock.getAsLong();
        if (token == null || now >= tokenUntil || !same(token, supplied)) return false;
        if (now - actionWindow >= 1000) { actionWindow = now; actions = 0; }
        return ++actions <= 5;
    }

    public synchronized void clear() { code = token = null; codeUntil = tokenUntil = 0; }

    public static boolean sameOrigin(String origin, String host, Set<String> allowedHosts) {
        if (host == null || !allowedHosts.contains(host.toLowerCase(java.util.Locale.ROOT))) return false;
        if (origin == null || origin.isEmpty()) return true; // Native clients still require an unguessable token.
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && host.equalsIgnoreCase(uri.getRawAuthority());
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static boolean same(String expected, String actual) {
        return actual != null && actual.length() <= 128 && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
