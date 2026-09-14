package com.github.catvod.crawler.diagnostics;

import com.google.gson.JsonObject;

import java.util.Set;

/** Versioned, allowlisted facts. No arbitrary object serialization or media content. */
public final class DiagnosticEvent {
    public enum Status {
        KNOWN("known"), UNKNOWN("unknown"), NOT_COLLECTED("not-collected"), NOT_SUPPORTED("not-supported"),
        NOT_APPLICABLE("not-applicable"), PERMISSION_DENIED("permission-denied"), STALE("stale"),
        READ_ERROR("read-error"), TIMED_OUT("timed-out"), PENDING_CALLBACK("pending-callback"),
        UNAVAILABLE("unavailable"), NOT_OBSERVABLE("not-observable");
        public final String value;
        Status(String value) { this.value = value; }
    }

    private static final Set<String> EVENTS = Set.of("diag.session.begin", "env.device", "config.snapshot", "play.request",
            "resolve.result", "media.tracks", "play.lifecycle", "play.attempt.end");
    private static final Set<String> FIELDS = Set.of("mode", "captureStartedLate", "reason", "controllerStage", "elapsedMs",
            "playerType", "decode", "headersCount", "tracksSummary", "signalSource", "video", "audio", "physicalVideo", "audibility",
            "appVersion", "versionCode", "buildTime", "buildTag", "gitRevision", "media3Version", "flavor", "abi", "process64Bit",
            "android", "api", "targetSdk", "manufacturer", "model", "device", "hardwareAccelerated", "source", "role",
            "session", "deviceInfo", "nativeLibraries", "display", "config", "configChanges", "request", "input", "inputRoute",
            "container", "tracks", "drm", "lifecycle", "clock", "resources", "attemptEnd", "health", "decoder", "surface", "audioOutput",
            "closed", "outcome", "lastNormalLayer", "missingEvidence", "nextStep", "association", "engineInstance");

    private final JsonObject root = new JsonObject();
    private final JsonObject observed = new JsonObject();
    private final JsonObject requested = new JsonObject();
    private final JsonObject coverage = new JsonObject();
    private String pinKey;

    public DiagnosticEvent(String event, String trace, String instance, long generation, long attempt) {
        if (!EVENTS.contains(event)) throw new IllegalArgumentException("Unknown diagnostic event");
        root.addProperty("schemaVersion", 1);
        root.addProperty("event", event);
        root.addProperty("level", "info");
        root.addProperty("trace", label(trace));
        root.addProperty("playerInstanceId", label(instance));
        root.addProperty("association", "controller-context-only");
        root.addProperty("eventMediaIdStatus", Status.NOT_COLLECTED.value);
        root.addProperty("engineStatus", Status.NOT_COLLECTED.value);
        root.addProperty("mediaGeneration", Math.max(0, generation));
        root.addProperty("attemptId", Math.max(0, attempt));
        root.addProperty("source", "app-controller");
        root.addProperty("evidenceClass", "observed");
        root.addProperty("status", Status.KNOWN.value);
        root.addProperty("ageMs", 0);
    }

    public DiagnosticEvent observed(String name, Object value) { put(observed, name, value, Status.KNOWN); return this; }
    public DiagnosticEvent requested(String name, Object value) { put(requested, name, value, Status.KNOWN); return this; }
    public DiagnosticEvent unknown(String name, Status status) { put(observed, name, null, status); return this; }
    public DiagnosticEvent coverage(String name, Status status) { put(coverage, name, status == Status.KNOWN ? true : null, status); return this; }
    public DiagnosticEvent pin(String key) { pinKey = label(key); return this; }
    public String pinKey() { return pinKey; }

    public String json() {
        JsonObject result = root.deepCopy();
        if (observed.size() > 0) result.add("observed", observed.deepCopy());
        if (requested.size() > 0) result.add("requested", requested.deepCopy());
        if (coverage.size() > 0) result.add("collectors", coverage.deepCopy());
        String json = result.toString();
        if (json.length() > 4_000) throw new IllegalArgumentException("Diagnostic event exceeds schema budget");
        return json;
    }

    private static void put(JsonObject target, String name, Object value, Status status) {
        if (!FIELDS.contains(name) || status == null) throw new IllegalArgumentException("Unknown diagnostic field/status");
        JsonObject fact = new JsonObject();
        if (status == Status.KNOWN && value == null) status = Status.UNKNOWN;
        if (value instanceof Number number && !Double.isFinite(number.doubleValue())) status = Status.UNKNOWN;
        fact.addProperty("status", status.value);
        if (status == Status.KNOWN) {
            if (value instanceof Boolean bool) fact.addProperty("value", bool);
            else if (value instanceof Number number) fact.addProperty("value", number);
            else if (value instanceof String string) fact.addProperty("value", label(string));
            else throw new IllegalArgumentException("Only scalar diagnostic fields are allowed");
        }
        target.add(name, fact);
    }

    private static String label(String value) {
        if (value == null) return "none";
        String safe = DiagnosticText.clean(value).text();
        return safe.length() > 256 ? safe.substring(0, 240) + " [truncated]" : safe;
    }
}
