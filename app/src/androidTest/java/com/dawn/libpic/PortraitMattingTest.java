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

import com.dawn.pic.PortraitMatting;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 在真机上验证离线人像抠图：模型加载、推理耗时、透明背景正确性。
 * 结果图片输出到 app 外部私有目录 matting/result.png，便于人工检查发丝边缘。
 */
@RunWith(AndroidJUnit4.class)
public class PortraitMattingTest {

    private static final String TAG = "PortraitMattingTest";

    @Test
    public void mattePortrait() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();

        Bitmap source;
        try (InputStream in = testContext.getAssets().open("portrait_sample.jpg")) {
            source = BitmapFactory.decodeStream(in);
        }
        assertNotNull("测试人像加载失败", source);

        long loadStart = SystemClock.elapsedRealtime();
        try (PortraitMatting matting = new PortraitMatting(target)) {
            long loadCost = SystemClock.elapsedRealtime() - loadStart;

            long start = SystemClock.elapsedRealtime();
            Bitmap matted = matting.matte(source);
            long matteCost = SystemClock.elapsedRealtime() - start;
            assertNotNull(matted);

            int width = matted.getWidth();
            int height = matted.getHeight();
            assertEquals(source.getWidth(), width);
            assertEquals(source.getHeight(), height);

            int cornerAlpha = Color.alpha(matted.getPixel(2, 2));
            int centerAlpha = Color.alpha(matted.getPixel(width / 2, height / 2));
            Log.i(TAG, String.format("模型加载 %d ms，抠图 %d ms，角落 alpha=%d，中心 alpha=%d",
                    loadCost, matteCost, cornerAlpha, centerAlpha));

            assertTrue("角落应为透明，实际 alpha=" + cornerAlpha, cornerAlpha < 24);
            assertTrue("人像中心应不透明，实际 alpha=" + centerAlpha, centerAlpha > 200);

            File outDir = new File(target.getExternalFilesDir(null), "matting");
            assertTrue(outDir.exists() || outDir.mkdirs());
            try (FileOutputStream fos = new FileOutputStream(new File(outDir, "result.png"))) {
                assertTrue(matted.compress(Bitmap.CompressFormat.PNG, 100, fos));
            }
            Log.i(TAG, "抠图结果: " + new File(outDir, "result.png").getAbsolutePath());
            matted.recycle();
        } finally {
            source.recycle();
        }
    }

    @Test
    public void matteAtLowerResolutionIsFaster() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();

        Bitmap source;
        try (InputStream in = testContext.getAssets().open("portrait_sample.jpg")) {
            source = BitmapFactory.decodeStream(in);
        }
        assertNotNull("测试人像加载失败", source);

        try (PortraitMatting matting = new PortraitMatting(target, 384)) {
            long start = SystemClock.elapsedRealtime();
            Bitmap matted = matting.matte(source);
            long cost = SystemClock.elapsedRealtime() - start;
            assertNotNull(matted);
            assertEquals(source.getWidth(), matted.getWidth());
            assertEquals(source.getHeight(), matted.getHeight());
            int cornerAlpha = Color.alpha(matted.getPixel(2, 2));
            Log.i(TAG, "384 分辨率抠图 " + cost + " ms，角落 alpha=" + cornerAlpha);
            assertTrue("角落应为透明，实际 alpha=" + cornerAlpha, cornerAlpha < 24);

            File outDir = new File(target.getExternalFilesDir(null), "matting");
            assertTrue(outDir.exists() || outDir.mkdirs());
            try (FileOutputStream fos = new FileOutputStream(new File(outDir, "result_384.png"))) {
                assertTrue(matted.compress(Bitmap.CompressFormat.PNG, 100, fos));
            }
            matted.recycle();
        } finally {
            source.recycle();
        }
    }
}
