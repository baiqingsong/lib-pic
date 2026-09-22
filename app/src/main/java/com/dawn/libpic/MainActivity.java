package com.dawn.libpic;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.dawn.pic.PngAnalyzer;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private TextView jsonTextView;
    private RadioGroup modeGroup;
    private Button selectButton;
    private Button copyButton;
    private Button saveButton;
    private Button testButton;
    private ProgressBar progressBar;

    private Bitmap currentBitmap;
    private Bitmap currentPreview;
    private String currentJson;
    private List<int[]> currentAreas;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // OpenDocument 正确授予 MediaDocumentsProvider 访问权限，GetContent 在此 ROM 上会抛 SecurityException
    private final ActivityResultLauncher<String[]> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImageSelected);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView    = findViewById(R.id.imageView);
        jsonTextView = findViewById(R.id.jsonTextView);
        modeGroup    = findViewById(R.id.modeGroup);
        selectButton = findViewById(R.id.selectButton);
        copyButton   = findViewById(R.id.copyButton);
        saveButton   = findViewById(R.id.saveButton);
        testButton   = findViewById(R.id.testButton);
        progressBar  = findViewById(R.id.progressBar);

        selectButton.setOnClickListener(v -> imagePickerLauncher.launch(new String[]{"image/*"}));
        testButton.setOnClickListener(v -> loadTestImage());
        copyButton.setOnClickListener(v -> copyJson());
        saveButton.setOnClickListener(v -> saveMasks());
    }

    private void onImageSelected(Uri uri) {
        if (uri == null) return;
        boolean isDouble = ((RadioButton) findViewById(R.id.doubleRadio)).isChecked();
        setUiBusy();
        executor.execute(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    bitmap = BitmapFactory.decodeStream(is, null, opts);
                }
                if (bitmap == null) throw new IllegalArgumentException("图片解码失败，请确认文件是有效的 PNG 图片");
                processBitmapAsync(bitmap, isDouble);
            } catch (Throwable e) {
                Log.e("PicApp", "读取图片失败", e);
                runOnUiThread(() -> showError(e));
            }
        });
    }

    private void loadTestImage() {
        boolean isDouble = ((RadioButton) findViewById(R.id.doubleRadio)).isChecked();
        setUiBusy();
        executor.execute(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap;
                try (InputStream is = getAssets().open("cs.png")) {
                    bitmap = BitmapFactory.decodeStream(is, null, opts);
                }
                if (bitmap == null) throw new IllegalArgumentException("内置测试图片加载失败");
                processBitmapAsync(bitmap, isDouble);
            } catch (Throwable e) {
                Log.e("PicApp", "加载测试图片失败", e);
                runOnUiThread(() -> showError(e));
            }
        });
    }

    // runs on executor thread; all UI ops delegated to runOnUiThread
    private void processBitmapAsync(Bitmap rawBitmap, boolean isDouble) {
        try {
            Bitmap bitmap = rawBitmap;
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                bitmap.recycle();
                bitmap = converted;
            }
            List<int[]> areas = PngAnalyzer.findTransparentAreas(bitmap);

            // 输出每个区域坐标便于调试
            for (int i = 0; i < areas.size(); i++) {
                int[] a = areas.get(i);
                Log.d("PicApp", "区域#" + i + ": x=" + a[0] + " y=" + a[1] + " w=" + a[2] + " h=" + a[3]);
            }

            String json = PngAnalyzer.generateJson(bitmap, areas, isDouble);

            // 构建显示文本：先列出各区域坐标，再输出 JSON
            StringBuilder display = new StringBuilder();
            if (areas.isEmpty()) {
                display.append("未检测到透明区域，请确认图片包含透明镂空");
            } else {
                display.append("检测到 ").append(areas.size()).append(" 个透明区域\n");
                for (int i = 0; i < areas.size(); i++) {
                    int[] a = areas.get(i);
                    display.append("  #").append(i)
                           .append(": x=").append(a[0]).append(" y=").append(a[1])
                           .append(" w=").append(a[2]).append(" h=").append(a[3]).append("\n");
                }
                display.append("\n").append(json);
            }

            Bitmap preview = PngAnalyzer.drawDebugOverlay(bitmap, areas);
            final Bitmap finalBitmap = bitmap;
            final String displayText = display.toString();
            runOnUiThread(() -> {
                if (currentBitmap != null) currentBitmap.recycle();
                if (currentPreview != null) currentPreview.recycle();
                currentBitmap = finalBitmap;
                currentPreview = preview;
                currentAreas  = areas;
                currentJson   = json;
                imageView.setImageBitmap(preview);
                jsonTextView.setText(displayText);
                setUiIdle(!areas.isEmpty());
                Toast.makeText(this, "找到 " + areas.size() + " 个透明区域", Toast.LENGTH_SHORT).show();
            });
        } catch (Throwable e) {
            Log.e("PicApp", "处理图片失败", e);
            runOnUiThread(() -> showError(e));
        }
    }

    private void setUiBusy() {
        progressBar.setVisibility(View.VISIBLE);
        selectButton.setEnabled(false);
        testButton.setEnabled(false);
        copyButton.setEnabled(false);
        saveButton.setEnabled(false);
    }

    private void setUiIdle(boolean hasResult) {
        progressBar.setVisibility(View.GONE);
        selectButton.setEnabled(true);
        testButton.setEnabled(true);
        copyButton.setEnabled(hasResult);
        saveButton.setEnabled(hasResult);
    }

    private void showError(Throwable e) {
        jsonTextView.setText("处理失败: " + e.getClass().getSimpleName() + "\n" + e.getMessage());
        setUiIdle(false);
    }

    private void copyJson() {
        if (currentJson == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("JSON", currentJson));
        Toast.makeText(this, "JSON 已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    private void saveMasks() {
        if (currentBitmap == null || currentAreas == null) return;
        executor.execute(() -> {
            try {
                File outputDir = new File(getExternalFilesDir(null), "masks");
                List<String> paths = PngAnalyzer.saveTransparentMasks(currentBitmap, currentAreas, outputDir);
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "已保存 " + paths.size() + " 张切图\n路径：" + outputDir.getAbsolutePath(),
                                Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (currentBitmap != null) { currentBitmap.recycle(); currentBitmap = null; }
        if (currentPreview != null) { currentPreview.recycle(); currentPreview = null; }
    }
}
