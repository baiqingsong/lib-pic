package com.dawn.libpic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;

/**
 * 验证 MainActivity 里“选图 → 抠图 → 显示结果”的完整 UI 流程（跳过系统文件选择器）。
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityMattingTest {

    @Test
    public void mattingFlowUpdatesPreviewAndEnablesSave() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();

        File photo = new File(target.getCacheDir(), "ui_portrait_sample.jpg");
        try (InputStream in = testContext.getAssets().open("portrait_sample.jpg");
             OutputStream out = new FileOutputStream(photo)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        final Uri uri = Uri.fromFile(photo);

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.matteFromUri(uri, "ui_portrait_sample"));

            String info = waitFor(() -> {
                String[] text = new String[1];
                scenario.onActivity(a ->
                        text[0] = ((TextView) a.findViewById(R.id.matteInfoText)).getText().toString());
                return text[0];
            }, 60000, "抠图完成", "抠图失败");

            assertTrue("抠图未完成，界面提示：" + info, info.startsWith("抠图完成"));
            scenario.onActivity(a -> assertTrue("保存抠图按钮应可用",
                    ((Button) a.findViewById(R.id.saveMatteButton)).isEnabled()));
            scenario.onActivity(a -> assertTrue("增强清晰度按钮应可用",
                    ((Button) a.findViewById(R.id.enhanceButton)).isEnabled()));

            scenario.onActivity(MainActivity::enhanceCurrent);
            String enhancedInfo = waitFor(() -> {
                String[] text = new String[1];
                scenario.onActivity(a ->
                        text[0] = ((TextView) a.findViewById(R.id.matteInfoText)).getText().toString());
                return text[0];
            }, 60000, "清晰度增强完成", "增强失败");
            assertTrue("清晰度增强未完成，界面提示：" + enhancedInfo,
                    enhancedInfo.startsWith("清晰度增强完成"));
            scenario.onActivity(a -> assertTrue("增强后保存按钮仍可用",
                    ((Button) a.findViewById(R.id.saveMatteButton)).isEnabled()));
        }
    }

    /** 相机拍照链路：系统有相机应用，且 FileProvider 能生成可写 Uri */
    @Test
    public void cameraCaptureIsWiredUp() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        assertTrue("设备上没有可处理拍照的应用", intent.resolveActivity(target.getPackageManager()) != null);

        File dir = new File(target.getCacheDir(), "captures");
        assertTrue("无法创建拍照缓存目录", dir.exists() || dir.mkdirs());
        Uri uri = FileProvider.getUriForFile(target,
                target.getPackageName() + ".fileprovider", new File(dir, "probe.jpg"));
        assertEquals("content", uri.getScheme());
    }

    /** 轮询等待 UI 文本达到终态（成功或失败），超时返回最后一次的文本 */
    private static String waitFor(Callable<String> reader, long timeoutMs, String... prefixes)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = reader.call();
            if (text != null) {
                for (String prefix : prefixes) {
                    if (text.startsWith(prefix)) return text;
                }
            }
            Thread.sleep(200);
        }
        return text == null ? "" : text;
    }
}
