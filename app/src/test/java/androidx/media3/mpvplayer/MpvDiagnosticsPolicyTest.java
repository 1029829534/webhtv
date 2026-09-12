package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MpvDiagnosticsPolicyTest {
    @Test
    public void fatalFelRequiresActualErrorRecordNotQuotedMetadata() {
        assertTrue(MpvDiagnosticsPolicy.isFatalFelLog(20, "WebHTV FEL fatal: decoder stalled\n"));
        assertFalse(MpvDiagnosticsPolicy.isFatalFelLog(40, "WebHTV FEL fatal: a file title"));
        assertFalse(MpvDiagnosticsPolicy.isFatalFelLog(20, "title=WebHTV FEL fatal: movie"));
        assertFalse(MpvDiagnosticsPolicy.isFatalFelLog(20, null));
    }

    @Test
    public void warningSeverityPreservesStarvationAndUnknownWarnings() {
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately(30,
                "MediaCodec input and output ports remained unavailable; failing hardware decode"));
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately(20, "opaque driver diagnostic"));
        assertFalse(MpvDiagnosticsPolicy.shouldLogNativeImmediately(60, "ordinary per-frame detail"));
        assertFalse(MpvDiagnosticsPolicy.shouldLogNativeImmediately(30, null));
    }

    @Test
    public void repeatedWarningsAreBoundedButFatalDiagnosisSurvives() {
        MpvDiagnosticsPolicy.NativeLogWindow window = new MpvDiagnosticsPolicy.NativeLogWindow();
        for (int i = 0; i < 32; i++) assertTrue(window.allow(100, "warning"));
        assertFalse(window.allow(101, "warning"));
        assertFalse(window.allow(102, "warning"));
        assertTrue(window.allow(103, "vd: WebHTV FEL fatal: no progress"));
        assertEquals(2, window.takeSuppressed());
        assertEquals(0, window.takeSuppressed());
        assertTrue(window.allow(5100, "warning"));
    }

    @Test
    public void felReconstructionEvidenceIsPersistedBeforeMainQueue() {
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately(
                "enhancement_pair: WebHTV Android FEL: software enhancement-layer decoder enabled"));
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately(
                "enhancement_pair: WebHTV FEL pair: BL=1.0 EL=1.0 NLQ=1 software-EL=1"));
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately(
                "vo/gpu-next: WebHTV FEL GPU input: matched EL uploaded with active NLQ."));
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately(
                "ffmpeg/video: Native Dolby Vision output is unavailable, using the base-layer decoder for profile 7"));
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately(
                "ffmpeg/video: MediaCodec started successfully: codec = c2.mtk.hevc.decoder, ret = 0"));
    }

    @Test
    public void failureEvidenceDoesNotWaitForTheUi() {
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately("vd: Decoder init failed for hevc"));
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately("vo/gpu-next: Vulkan error"));
        assertTrue(MpvDiagnosticsPolicy.shouldLogNativeImmediately("lavf: Invalid data found when processing input"));
    }

    @Test
    public void ordinaryPerFrameLogsStayOutOfImmediateDiagnostics() {
        assertFalse(MpvDiagnosticsPolicy.shouldLogNativeImmediately("cplayer: playing frame pts=12.34"));
        assertFalse(MpvDiagnosticsPolicy.shouldLogNativeImmediately("vd: sending packet pts=12.34"));
        assertFalse(MpvDiagnosticsPolicy.shouldLogNativeImmediately(null));
        assertFalse(MpvDiagnosticsPolicy.shouldLogNativeImmediately(""));
    }

    @Test
    public void normalPlaybackAndMinimalErrorsNeverQueryDetailedProperties() {
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.PLAYBACK, false));
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.PLAYBACK, true));
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.ERROR_MINIMAL, false));
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.ERROR_MINIMAL, true));
    }

    @Test
    public void visiblePanelUsesObservedPropertiesOnly() {
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.PANEL, false));
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.PANEL, true));
    }

    @Test
    public void detailedLogsAndErrorsRequireDebugSwitch() {
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.DEBUG_LOG, false));
        assertTrue(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.DEBUG_LOG, true));
        assertFalse(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.ERROR_DETAILED, false));
        assertTrue(MpvDiagnosticsPolicy.allowsSynchronousProperties(MpvDiagnosticsPolicy.Request.ERROR_DETAILED, true));
    }

    @Test
    public void sourceSummaryDoesNotExposePathQueryOrToken() {
        String source = "https://cdn.example.com/private/movie.mkv?token=secret";
        String summary = MpvDiagnosticsPolicy.sourceSummary(source);

        assertTrue(summary.contains("scheme=https"));
        assertTrue(summary.contains("urlLen=" + source.length()));
        assertFalse(summary.contains("cdn.example.com"));
        assertFalse(summary.contains("private"));
        assertFalse(summary.contains("secret"));
    }

    @Test
    public void nativeLogRedactionRemovesUrlsAndSensitiveHeaders() {
        String raw = "opening https://cdn.example.com/a.m3u8?token=secret Authorization: Bearer abc Cookie=session=xyz codec=h264";
        String safe = MpvDiagnosticsPolicy.redactSensitive(raw);

        assertTrue(safe.contains("<url>"));
        assertTrue(safe.toLowerCase().contains("authorization=<redacted>"));
        assertTrue(safe.toLowerCase().contains("cookie=<redacted>"));
        assertTrue(safe.contains("codec=h264"));
        assertFalse(safe.contains("secret"));
        assertFalse(safe.contains("Bearer abc"));
        assertFalse(safe.contains("session=xyz"));
    }
}
