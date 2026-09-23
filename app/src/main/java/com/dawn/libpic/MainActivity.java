package com.dawn.libpic;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dawn.pic.PngAnalyzer;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private TextView jsonTextView;
    private Button selectButton;
    private Button copyButton;
    private Button saveButton;
    private Button testButton;
    private ProgressBar progressBar;

    private static final int REQ_WRITE_PERMISSION = 101;

    private Bitmap currentBitmap;
    private Bitmap currentPreview;
    private String currentJson;
    private String currentImageName;
    private Uri currentSourceUri;
    private List<int[]> currentAreas;

    // 用户从 Settings 授权后自动重试保存
    private boolean pendingSave = false;

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
        String imageName = getImageName(uri);
        currentSourceUri = uri;
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
                processBitmapAsync(bitmap, isDouble, imageName);
            } catch (Throwable e) {
                Log.e("PicApp", "读取图片失败", e);
                runOnUiThread(() -> showError(e));
            }
        });
    }

    private void loadTestImage() {
        boolean isDouble = ((RadioButton) findViewById(R.id.doubleRadio)).isChecked();
        setUiBusy();
        currentSourceUri = null; // 测试图来自 assets，无文件路径
        executor.execute(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap;
                try (InputStream is = getAssets().open("cs.png")) {
                    bitmap = BitmapFactory.decodeStream(is, null, opts);
                }
                if (bitmap == null) throw new IllegalArgumentException("内置测试图片加载失败");
                processBitmapAsync(bitmap, isDouble, "cs");
            } catch (Throwable e) {
                Log.e("PicApp", "加载测试图片失败", e);
                runOnUiThread(() -> showError(e));
            }
        });
    }

    // runs on executor thread; all UI ops delegated to runOnUiThread
    private void processBitmapAsync(Bitmap rawBitmap, boolean isDouble, String imageName) {
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
                display.append("模式: ").append(isDouble ? "双份" : "单份").append("\n");
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
                currentImageName = imageName;
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
        if (currentBitmap == null || currentAreas == null || currentImageName == null) return;
        if (!hasStoragePermission()) {
            Log.d("PicApp", "saveMasks: 无存储权限，申请中");
            pendingSave = true;
            requestStoragePermission();
            return;
        }
        final String imageName = currentImageName;
        executor.execute(() -> {
            try {
                File outputDir = resolveOutputDir();
                Log.d("PicApp", "saveMasks: outputDir=" + outputDir.getAbsolutePath());
                List<String> paths = PngAnalyzer.saveTransparentMasks(
                        currentBitmap, currentAreas, outputDir, imageName);
                Log.d("PicApp", "saveMasks: 切图已保存 " + paths.size() + " 张 → " + paths);
                PngAnalyzer.savePicJson(outputDir, imageName, currentJson);
                Log.d("PicApp", "saveMasks: pic.json 已更新");
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "已保存 " + paths.size() + " 张切图，并更新 pic.json\n路径：" + outputDir.getAbsolutePath(),
                                Toast.LENGTH_LONG).show()
                );
            } catch (Throwable e) {
                Log.e("PicApp", "saveMasks: 保存失败", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    /** 优先保存到源图同目录，无法解析时回退到 Download/LibPic/ */
    private File resolveOutputDir() {
        if (currentSourceUri != null) {
            File sourceDir = resolveSourceDirectory(currentSourceUri);
            if (sourceDir != null && sourceDir.isDirectory()) return sourceDir;
        }
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "LibPic");
    }

    /** 解析 Uri 对应的真实目录；支持 ExternalStorageProvider 和 MediaStore */
    private File resolveSourceDirectory(Uri uri) {
        // ExternalStorageProvider：content://com.android.externalstorage.documents/document/primary:path/file.png
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            String docId = DocumentsContract.getDocumentId(uri);
            String[] parts = docId.split(":", 2);
            if (parts.length == 2 && "primary".equalsIgnoreCase(parts[0])) {
                return new File(Environment.getExternalStorageDirectory(), parts[1]).getParentFile();
            }
        }
        // 通用回退：查询 DATA 列（部分中新机型上可能返回 null）
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String path = c.getString(0);
                if (path != null && !path.isEmpty()) return new File(path).getParentFile();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "请开启『所有文件访问』权限，完成后返回应用重试", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveMasks();
            } else {
                pendingSave = false;
                Toast.makeText(this, "存储权限被拒绝，无法保存", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // API 30+ 用户从 Settings 授权后返回自动重试
        if (pendingSave && hasStoragePermission()) {
            pendingSave = false;
            saveMasks();
        }
    }

    /** 从 Uri 提取文件名（不含后缀） */
    private String getImageName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        return name != null ? name : "image";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (currentBitmap != null) { currentBitmap.recycle(); currentBitmap = null; }
        if (currentPreview != null) { currentPreview.recycle(); currentPreview = null; }
    }
}
