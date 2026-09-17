package androidx.media3.mpvplayer;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class MpvHlsAdblockTest {

    private static final HlsPlaylistRewriter.Variant LOW =
            new HlsPlaylistRewriter.Variant(1_000_000, 900_000, 640, 360,
                    HlsPlaylistRewriter.VariantKind.STREAM);
    private static final HlsPlaylistRewriter.Variant HIGH =
            new HlsPlaylistRewriter.Variant(4_000_000, 3_500_000, 1920, 1080,
                    HlsPlaylistRewriter.VariantKind.STREAM);

    @Test
    public void directMediaPlaylistDoesNotNeedNativeVariantMetadata() {
        HlsAdTimeline direct = HlsAdTimelineTest.middleAd("2");
        assertSame(direct, MpvHlsProxy.resolveAdTimeline(direct, Map.of(), 0, 0));
    }

    @Test
    public void selectedPeakOrAverageBitrateChoosesItsOwnPlan() {
        HlsAdTimeline low = HlsAdTimelineTest.middleAd("2");
        HlsAdTimeline high = HlsAdTimelineTest.middleAd("4");
        Map<HlsPlaylistRewriter.Variant, HlsAdTimeline> plans = Map.of(LOW, low, HIGH, high);
        assertSame(low, MpvHlsProxy.resolveAdTimeline(null, plans, 1_000_000, 2));
        assertSame(high, MpvHlsProxy.resolveAdTimeline(null, plans, 3_500_000, 2));
        assertTrue(MpvHlsProxy.resolveAdTimeline(null, plans, 2_000_000, 2).ranges().isEmpty());
    }

    @Test
    public void unknownSelectionRequiresEveryDeclaredVariantToAgree() {
        HlsAdTimeline plan = HlsAdTimelineTest.middleAd("2");
        assertTrue(MpvHlsProxy.resolveAdTimeline(null, Map.of(LOW, plan), 0, 2)
                .ranges().isEmpty());
        assertSame(plan, MpvHlsProxy.resolveAdTimeline(null,
                Map.of(LOW, plan, HIGH, plan), 0, 2));
        assertTrue(MpvHlsProxy.resolveAdTimeline(null,
                Map.of(LOW, plan, HIGH, HlsAdTimeline.NONE), 0, 2).ranges().isEmpty());
    }

    @Test
    public void matchingBitratesWithConflictingPlansNeverGuess() {
        HlsPlaylistRewriter.Variant alternate =
                new HlsPlaylistRewriter.Variant(1_000_000, 900_000, 960, 540,
                        HlsPlaylistRewriter.VariantKind.STREAM);
        assertTrue(MpvHlsProxy.resolveAdTimeline(null,
                Map.of(LOW, HlsAdTimelineTest.middleAd("2"),
                        alternate, HlsAdTimelineTest.middleAd("4")),
                1_000_000, 2).ranges().isEmpty());
    }

    @Test
    public void imageOrIframePlaylistCannotSupplyTheVideoAdPlan() {
        HlsPlaylistRewriter.Variant iframe =
                new HlsPlaylistRewriter.Variant(1_000_000, 900_000, 640, 360,
                        HlsPlaylistRewriter.VariantKind.I_FRAME);
        assertTrue(MpvHlsProxy.resolveAdTimeline(null,
                Map.of(iframe, HlsAdTimelineTest.middleAd("2")), 1_000_000, 1)
                .ranges().isEmpty());
    }
}
