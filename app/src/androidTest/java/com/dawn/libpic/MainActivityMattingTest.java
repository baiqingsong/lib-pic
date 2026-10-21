package com.dawn.libpic;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.widget.Button;
import android.widget.TextView;

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
            scenario.onActivity(activity -> activity.onMatteImageSelected(uri));

            String info = waitFor(() -> {
                String[] text = new String[1];
                scenario.onActivity(a ->
                        text[0] = ((TextView) a.findViewById(R.id.matteInfoText)).getText().toString());
                return text[0];
            }, 60000);

            assertTrue("抠图未完成，界面提示：" + info, info.startsWith("抠图完成"));
            scenario.onActivity(a -> assertTrue("保存抠图按钮应可用",
                    ((Button) a.findViewById(R.id.saveMatteButton)).isEnabled()));
        }
    }

    /** 轮询等待 UI 文本达到终态（成功或失败），超时返回最后一次的文本 */
    private static String waitFor(Callable<String> reader, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = reader.call();
            if (text != null && (text.startsWith("抠图完成") || text.startsWith("抠图失败"))) {
                return text;
            }
            Thread.sleep(200);
        }
        return text == null ? "" : text;
    }
}
