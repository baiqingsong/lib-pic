package com.dawn.libserial;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
    private ProgressBar progressBar;

    private Bitmap currentBitmap;
    private String currentJson;
    private List<int[]> currentAreas;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImageSelected);

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
        progressBar  = findViewById(R.id.progressBar);

        selectButton.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));
        copyButton.setOnClickListener(v -> copyJson());
        saveButton.setOnClickListener(v -> saveMasks());
    }

    private void onImageSelected(Uri uri) {
        if (uri == null) return;

        boolean isDouble = ((RadioButton) findViewById(R.id.doubleRadio)).isChecked();

        progressBar.setVisibility(View.VISIBLE);
        selectButton.setEnabled(false);
        copyButton.setEnabled(false);
        saveButton.setEnabled(false);

        executor.execute(() -> {
            try {
                Bitmap bitmap;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    bitmap = BitmapFactory.decodeStream(is);
                }
                if (bitmap == null) throw new IllegalArgumentException("图片解码失败");

                // 确保是 ARGB_8888 格式（支持透明通道）
                Bitmap argb = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (argb != bitmap) bitmap.recycle();

                List<int[]> areas = PngAnalyzer.findTransparentAreas(argb);
                String json = PngAnalyzer.generateJson(argb, areas, isDouble);

                // 切换到主线程更新 UI
                runOnUiThread(() -> {
                    if (currentBitmap != null) currentBitmap.recycle();
                    currentBitmap = argb;
                    currentAreas  = areas;
                    currentJson   = json;

                    imageView.setImageBitmap(argb);
                    jsonTextView.setText(json);
                    progressBar.setVisibility(View.GONE);
                    selectButton.setEnabled(true);
                    copyButton.setEnabled(true);
                    saveButton.setEnabled(true);
                    Toast.makeText(this, "找到 " + areas.size() + " 个透明区域", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    jsonTextView.setText("处理失败: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    selectButton.setEnabled(true);
                });
            }
        });
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
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }
}
