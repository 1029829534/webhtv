package com.github.catvod.crawler;

import android.os.SystemClock;

import com.github.catvod.Init;
import com.github.catvod.crawler.diagnostics.DiagnosticEvent;
import com.github.catvod.crawler.diagnostics.DiagnosticLogBuffer;
import com.github.catvod.crawler.diagnostics.RollingDiagnosticFile;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class DebugLogStore {
    private static final String PREF_ENABLED = "debug_log";
    private static volatile boolean enabled;
    private static volatile DiagnosticLogBuffer buffer;

    public static boolean isEnabled() { return enabled; }

    private static synchronized DiagnosticLogBuffer create() {
        if (buffer == null) {
            DiagnosticLogBuffer.Limits limits = DiagnosticLogBuffer.Limits.standard();
            buffer = new DiagnosticLogBuffer(limits, new RollingDiagnosticFile(Init.context().getCacheDir(), limits),
                    new DiagnosticLogBuffer.Clock() {
                        @Override public long wallMillis() { return System.currentTimeMillis(); }
                        @Override public long monotonicNanos() { return SystemClock.elapsedRealtimeNanos(); }
                        @Override public int processId() { return android.os.Process.myPid(); }
                    });
        }
        return buffer;
    }

    public static synchronized void setEnabled(boolean value) {
        if (value) create().start(false);
        enabled = value;
        Prefers.put(PREF_ENABLED, value);
        if (value) beginCollection("enable");
        else if (buffer != null) buffer.disable();
    }

    public static synchronized void restoreEnabled() {
        enabled = Prefers.getBoolean(PREF_ENABLED);
        if (!enabled) return;
        create().start(true);
        beginCollection("restore");
    }

    private static void beginCollection(String reason) {
        event(new DiagnosticEvent("diag.session.begin", "none", "process", 0, 0)
                .observed("mode", "standard").observed("reason", reason)
                .unknown("captureStartedLate", DiagnosticEvent.Status.UNKNOWN)
                .coverage("session", DiagnosticEvent.Status.KNOWN)
                .coverage("health", DiagnosticEvent.Status.KNOWN)
                .coverage("decoder", DiagnosticEvent.Status.NOT_COLLECTED)
                .coverage("surface", DiagnosticEvent.Status.NOT_COLLECTED)
                .coverage("audioOutput", DiagnosticEvent.Status.NOT_COLLECTED)
                .pin("process-session"));
    }

    public static void add(String tag, String message) { add(tag, message, false); }

    static void add(String tag, String message, boolean critical) {
        DiagnosticLogBuffer current = buffer;
        if (!enabled || current == null) return;
        current.add(tag, message, critical);
    }

    public static void event(DiagnosticEvent event) {
        DiagnosticLogBuffer current = buffer;
        if (enabled && current != null) current.event(event);
    }

    public static void collectorFailure() {
        DiagnosticLogBuffer current = buffer;
        if (enabled && current != null) current.collectorFailure();
    }

    /** Producer-published immutable cache; export never calls back into a player. */
    public static void collectorHealth(String id, DiagnosticEvent event, boolean partial, long generation) {
        DiagnosticLogBuffer current = buffer;
        if (enabled && current != null) current.collectorHealth(id, event, partial, generation);
    }

    public static String text() {
        if (!enabled) return "调试日志未开启";
        DiagnosticLogBuffer.Snapshot snapshot = incremental(-1, "", -1);
        if (snapshot == null) return "暂无调试日志";
        return DiagnosticLogBuffer.header(snapshot.health()) + snapshot.text();
    }

    public static List<String> snapshot() {
        DiagnosticLogBuffer.Snapshot snapshot = incremental(-1, "", -1);
        return snapshot == null ? List.of() : snapshot.lines();
    }

    public static List<String> observedOrigins() {
        DiagnosticLogBuffer current = buffer;
        return !enabled || current == null ? List.of() : current.origins();
    }

    public static DiagnosticLogBuffer.Snapshot incremental(long afterSeq, String runId, long generation) {
        DiagnosticLogBuffer current = buffer;
        return current == null ? null : current.snapshot(afterSeq, runId, generation);
    }

    public static DiagnosticLogBuffer.Export export() {
        DiagnosticLogBuffer current = buffer;
        if (enabled && current != null) return current.export(750);
        byte[] bytes = "调试日志未开启".getBytes(StandardCharsets.UTF_8);
        return new DiagnosticLogBuffer.Export(new ByteArrayInputStream(bytes), bytes.length, true);
    }

    public static int size() {
        DiagnosticLogBuffer.Snapshot snapshot = incremental(-1, "", -1);
        return snapshot == null ? 0 : snapshot.lines().size();
    }

    public static long bytes() {
        DiagnosticLogBuffer current = buffer;
        return current == null ? 0 : current.health().get("diskBytes").getAsLong();
    }

    public static long version() { return buffer == null ? 0 : buffer.version(); }
    public static long captureGeneration() { return buffer == null ? 0 : buffer.generation(); }

    public static synchronized void clear() {
        DiagnosticLogBuffer current = buffer;
        if (current == null) return;
        current.clear();
        if (enabled) beginCollection("clear");
    }
}
