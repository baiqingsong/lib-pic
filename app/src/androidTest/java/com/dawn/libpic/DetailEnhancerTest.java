package com.dawn.libpic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.dawn.pic.DetailEnhancer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 清晰度增强：尺寸、alpha 保留、细节提升（拉普拉斯方差）与耗时。
 * 结果图输出到 app 外部私有目录 enhance/ 便于人工对比。
 */
@RunWith(AndroidJUnit4.class)
public class DetailEnhancerTest {

    private static final String TAG = "DetailEnhancerTest";

    @Test
    public void enhanceSharpensAndKeepsAlpha() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap source = loadSample();
        assertNotNull(source);

        // 顶部 40 行置为全透明，验证 alpha 不被破坏
        int width = source.getWidth();
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < width; x++) {
                source.setPixel(x, y, source.getPixel(x, y) & 0x00FFFFFF);
            }
        }

        double before = laplacianVariance(source);
        long start = SystemClock.elapsedRealtime();
        Bitmap enhanced = DetailEnhancer.enhance(source);
        long cost = SystemClock.elapsedRealtime() - start;
        assertNotNull(enhanced);

        assertEquals(source.getWidth(), enhanced.getWidth());
        assertEquals(source.getHeight(), enhanced.getHeight());
        assertEquals("透明区域应为完全透明", 0, Color.alpha(enhanced.getPixel(10, 10)));
        assertEquals("不透明区域应保持不变", 255, Color.alpha(enhanced.getPixel(width / 2, 400)));

        double after = laplacianVariance(enhanced);
        Log.i(TAG, String.format("%dx%d 增强耗时 %d ms，拉普拉斯方差 %.1f → %.1f",
                width, source.getHeight(), cost, before, after));
        assertTrue("细节应增强，实际 " + before + " → " + after, after > before * 1.05);

        File outDir = new File(target.getExternalFilesDir(null), "enhance");
        assertTrue(outDir.exists() || outDir.mkdirs());
        savePng(source, new File(outDir, "1_before.png"));
        savePng(enhanced, new File(outDir, "1_after.png"));

        long start2 = SystemClock.elapsedRealtime();
        Bitmap upscaled = DetailEnhancer.enhance(source, 1.0f, 0.6f, 2);
        long cost2 = SystemClock.elapsedRealtime() - start2;
        assertEquals(width * 2, upscaled.getWidth());
        assertEquals(source.getHeight() * 2, upscaled.getHeight());
        assertEquals("放大后透明区域仍透明", 0, Color.alpha(upscaled.getPixel(20, 20)));
        Log.i(TAG, String.format("2x 增强耗时 %d ms，输出 %dx%d",
                cost2, upscaled.getWidth(), upscaled.getHeight()));
        savePng(upscaled, new File(outDir, "1_after_2x.png"));

        enhanced.recycle();
        upscaled.recycle();
        source.recycle();
    }

    /** 模拟"相对模糊"的照片：5×5 均值模糊后增强，锐度应有明显回升 */
    @Test
    public void enhanceRecoversBlurredPhoto() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap source = loadSample();
        assertNotNull(source);
        Bitmap blurred = boxBlur(source, 2);
        source.recycle();

        double blurredVariance = laplacianVariance(blurred);
        long start = SystemClock.elapsedRealtime();
        Bitmap enhanced = DetailEnhancer.enhance(blurred);
        long cost = SystemClock.elapsedRealtime() - start;
        double enhancedVariance = laplacianVariance(enhanced);

        Log.i(TAG, String.format("模糊图增强耗时 %d ms，方差 %.1f → %.1f",
                cost, blurredVariance, enhancedVariance));
        assertTrue("模糊图锐度应明显回升，实际 " + blurredVariance + " → " + enhancedVariance,
                enhancedVariance > blurredVariance * 1.5);

        File outDir = new File(target.getExternalFilesDir(null), "enhance");
        assertTrue(outDir.exists() || outDir.mkdirs());
        savePng(blurred, new File(outDir, "2_blurred.png"));
        savePng(enhanced, new File(outDir, "2_blurred_enhanced.png"));

        enhanced.recycle();
        blurred.recycle();
    }

    private static Bitmap loadSample() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream in = testContext.getAssets().open("portrait_sample.jpg")) {
            return BitmapFactory.decodeStream(in).copy(Bitmap.Config.ARGB_8888, true);
        }
    }

    private static void savePng(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos));
        }
    }

    private static Bitmap boxBlur(Bitmap source, int radius) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0, g = 0, b = 0, count = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int yy = Math.min(height - 1, Math.max(0, y + dy));
                    for (int dx = -radius; dx <= radius; dx++) {
                        int xx = Math.min(width - 1, Math.max(0, x + dx));
                        int p = pixels[yy * width + xx];
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        count++;
                    }
                }
                out[y * width + x] = 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888);
    }

    /** 拉普拉斯方差：越大表示细节/边缘越强，常用来衡量锐度 */
    private static double laplacianVariance(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] luma = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            luma[i] = 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
        }

        double sum = 0;
        double sumSq = 0;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;
                double v = 4 * luma[idx] - luma[idx - width] - luma[idx + width]
                        - luma[idx - 1] - luma[idx + 1];
                sum += v;
                sumSq += v * v;
                count++;
            }
        }
        double mean = sum / count;
        return sumSq / count - mean * mean;
    }
}
