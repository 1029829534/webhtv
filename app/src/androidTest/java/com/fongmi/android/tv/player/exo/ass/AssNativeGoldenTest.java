package com.fongmi.android.tv.player.exo.ass;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Fixed upstream PNGs + fixed fonts, with system font lookup explicitly disabled. */
public class AssNativeGoldenTest extends TestCase {
    private byte[] asset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("exo-ass/official/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private long create() throws Exception {
        AssNative.ensureLoaded();
        long handle = AssNative.createTestFonts(new String[]{"Aileron-Regular.otf", "Arimo-Bold.ttf", "Arimo-Regular.ttf"},
                new byte[][]{asset("Aileron-Regular.otf"), asset("Arimo-Bold.ttf"), asset("Arimo-Regular.ttf")});
        assertTrue("Native initialization with fixed fonts", handle != 0);
        return handle;
    }

    private void compare(long handle, String base, int time, int width, int height) throws Exception {
        long[] stats = new long[6];
        assertTrue("A changed frame must be submitted", AssNative.render(handle, time, width, height, width, height,
                1, 2, 2, true, stats) > 0);
        byte[] pixels = AssNative.readPixels(handle);
        assertNotNull(pixels);
        String name = base + "-" + time;
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "exo-ass-golden");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".rgba"))) { output.write(pixels); }
        byte[] png = asset(name + ".png");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPremultiplied = false;
        Bitmap reference = BitmapFactory.decodeByteArray(png, 0, png.length, options);
        assertEquals(width, reference.getWidth()); assertEquals(height, reference.getHeight());
        int[] expected = new int[width * height];
        reference.getPixels(expected, 0, width, 0, 0, width, height);
        reference.recycle();
        long error = 0, union = 0, bad = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = ((height - 1 - y) * width + x) * 4;
            int argb = expected[y * width + x];
            int a = argb >>> 24;
            int actualA = pixels[offset + 3] & 255;
            if (a == 0 && actualA == 0) continue;
            union++;
            int[] referencePixel = { ((argb >>> 16 & 255) * a + 127) / 255,
                    ((argb >>> 8 & 255) * a + 127) / 255, ((argb & 255) * a + 127) / 255, a };
            int maximum = 0;
            for (int channel = 0; channel < 4; channel++) {
                int difference = Math.abs(referencePixel[channel] - (pixels[offset + channel] & 255));
                error += difference; maximum = Math.max(maximum, difference);
            }
            if (maximum > 16) bad++;
        }
        assertTrue("Reference must contain a visible subtitle", union > 1000);
        double mean = error / (union * 4.0);
        double badRatio = bad / (double) union;
        String report = name + " foregroundMae=" + mean + " badPixelRatio=" + badRatio
                + " renderUs=" + stats[0] + " uploadUs=" + stats[1];
        android.util.Log.i("ExoAssGolden", report);
        // Frozen before inspecting candidate pixels. Transparent background does not dilute error.
        assertTrue(report, mean <= 1.5 && badRatio <= .02);
    }

    public void testOfficialBlurTransformFrames() throws Exception {
        long handle = create();
        try {
            assertTrue(AssNative.load(handle, AssInput.normalize(asset("blur+t.ass"))));
            assertTrue(AssNative.testSurface(handle, 800, 600));
            for (int time : new int[]{1000, 1500, 1900}) compare(handle, "blur+t", time, 800, 600);
        } finally { AssNative.destroy(handle); }
    }

    public void testOfficialKaraokeFrames() throws Exception {
        long handle = create();
        try {
            assertTrue(AssNative.load(handle, AssInput.normalize(asset("357-k-and-kf-desynced.ass"))));
            assertTrue(AssNative.testSurface(handle, 1920, 1080));
            for (int time : new int[]{6798, 7170, 8170}) compare(handle, "357-k-and-kf-desynced", time, 1920, 1080);
        } finally { AssNative.destroy(handle); }
    }

    public void testUnchangedContextRecreationAndEmptyFrameAreDistinct() throws Exception {
        long handle = create();
        long[] stats = new long[6];
        try {
            assertTrue(AssNative.load(handle, AssInput.normalize(asset("blur+t.ass"))));
            assertFalse(AssNative.testSurface(handle, Integer.MAX_VALUE, 100));
            assertTrue(AssNative.testSurface(handle, 800, 600));
            assertTrue(AssNative.render(handle, 1000, 800, 600, 800, 600, 1, 2, 2, false, stats) > 0);
            assertEquals(0, AssNative.render(handle, 1000, 800, 600, 800, 600, 1, 2, 2, false, stats));
            assertTrue(AssNative.setSurface(handle, null));
            assertTrue(AssNative.testSurface(handle, 800, 600));
            assertTrue(AssNative.render(handle, 1000, 800, 600, 800, 600, 1, 2, 2, false, stats) > 0);
            assertEquals(2, AssNative.render(handle, 3000, 800, 600, 800, 600, 1, 2, 2, false, stats));
            byte[] pixels = AssNative.readPixels(handle);
            for (byte pixel : pixels) assertEquals(0, pixel);
        } finally { AssNative.destroy(handle); }
    }
}
