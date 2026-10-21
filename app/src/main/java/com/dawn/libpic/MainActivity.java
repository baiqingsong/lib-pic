package com.dawn.libpic;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.dawn.pic.PortraitMatting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 人像抠图测试页：拍照（或选图）→ 离线 MODNet 抠图 → 预览 / 保存透明 PNG。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PicApp";
    private static final String FILE_PROVIDER_SUFFIX = ".fileprovider";

    private static final int REQ_WRITE_PERMISSION = 101;

    // 抠图输入的长边上限，避免超大照片解码时占用过多内存（输出尺寸随之受限）
    private static final int MATTE_MAX_DECODE_SIZE = 2048;

    private ImageView imageView;
    private Button cameraButton;
    private Button pickButton;
    private Button saveMatteButton;
    private ProgressBar progressBar;
    private TextView matteInfoText;

    private Bitmap currentMatte;
    private String currentMatteName;
    private PortraitMatting portraitMatting;
    private Drawable normalPreviewBg;

    // 相机拍照目标
    private Uri pendingCaptureUri;
    private String pendingCaptureName;

    // 用户从系统授权弹窗同意后自动重试被中断的保存动作
    private Runnable pendingPermissionAction;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                Uri uri = pendingCaptureUri;
                String name = pendingCaptureName;
                pendingCaptureUri = null;
                pendingCaptureName = null;
                if (!success || uri == null) {
                    setMatteInfo("已取消拍照");
                    return;
                }
                matteFromUri(uri, name);
            });

    private final ActivityResultLauncher<String[]> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;
                        matteFromUri(uri, getImageName(uri));
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        cameraButton = findViewById(R.id.cameraButton);
        pickButton = findViewById(R.id.pickButton);
        saveMatteButton = findViewById(R.id.saveMatteButton);
        progressBar = findViewById(R.id.progressBar);
        matteInfoText = findViewById(R.id.matteInfoText);

        normalPreviewBg = imageView.getBackground();

        cameraButton.setOnClickListener(v -> takePhoto());
        pickButton.setOnClickListener(v -> pickImageLauncher.launch(new String[]{"image/*"}));
        saveMatteButton.setOnClickListener(v -> saveMatte());
    }

    // ---------------------------------------------------------------- 拍照 / 选图

    /** 唤起系统相机，把照片写到应用缓存目录（FileProvider 授权） */
    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "captures");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建拍照缓存目录");
            }
            String name = "camera_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, name + ".jpg");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + FILE_PROVIDER_SUFFIX, file);
            pendingCaptureUri = uri;
            pendingCaptureName = name;
            takePictureLauncher.launch(uri);
        } catch (Throwable e) {
            Log.e(TAG, "启动相机失败", e);
            Toast.makeText(this, "启动相机失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- 抠图

    /** 对指定图片执行抠图，结果替换预览；包内可见便于测试直接调用 */
    void matteFromUri(Uri uri, String imageName) {
        setUiBusy();
        setMatteInfo("抠图中…（首次使用需解压模型，请稍候）");
        executor.execute(() -> {
            try {
                Bitmap source = decodeBitmap(uri);
                if (portraitMatting == null) portraitMatting = new PortraitMatting(this);
                long start = SystemClock.elapsedRealtime();
                Bitmap matte = portraitMatting.matte(source);
                long cost = SystemClock.elapsedRealtime() - start;
                int srcW = source.getWidth();
                int srcH = source.getHeight();
                source.recycle();

                runOnUiThread(() -> {
                    if (currentMatte != null) currentMatte.recycle();
                    currentMatte = matte;
                    currentMatteName = imageName;
                    imageView.setBackground(createCheckerDrawable());
                    imageView.setImageBitmap(matte);
                    setMatteInfo(String.format("抠图完成：%d×%d，耗时 %d ms", srcW, srcH, cost));
                    setUiIdle();
                    Toast.makeText(this, "抠图完成，可保存透明 PNG", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable e) {
                Log.e(TAG, "人像抠图失败", e);
                runOnUiThread(() -> {
                    setMatteInfo("抠图失败: " + e.getMessage());
                    setUiIdle();
                    Toast.makeText(this, "抠图失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---------------------------------------------------------------- 保存

    private void saveMatte() {
        if (currentMatte == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasStoragePermission()) {
            pendingPermissionAction = this::saveMatte;
            requestStoragePermission();
            return;
        }
        final Bitmap matte = currentMatte;
        final String fileName = (currentMatteName == null ? "matte" : currentMatteName) + "_matte.png";
        executor.execute(() -> {
            try {
                String location = writePng(matte, fileName);
                Log.d(TAG, "saveMatte: " + location);
                runOnUiThread(() -> Toast.makeText(this,
                        "已保存：" + location, Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                Log.e(TAG, "保存抠图失败", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 写入系统相册 Pictures/LibPic，返回便于提示的相对路径或绝对路径 */
    private String writePng(Bitmap bitmap, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/LibPic");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) throw new IOException("无法在相册中创建文件");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    throw new IOException("PNG 编码失败");
                }
            } catch (IOException e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return Environment.DIRECTORY_PICTURES + "/LibPic/" + fileName;
        }

        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LibPic");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File outFile = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IOException("PNG 编码失败");
            }
        }
        return outFile.getAbsolutePath();
    }

    // ---------------------------------------------------------------- 图片解码

    /** 按长边上限降采样解码，并按 EXIF 方向摆正（相机照片常带旋转信息） */
    private Bitmap decodeBitmap(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = openStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        int sample = 1;
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MATTE_MAX_DECODE_SIZE) {
                sample *= 2;
            }
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream is = openStream(uri)) {
            bitmap = BitmapFactory.decodeStream(is, null, opts);
        }
        if (bitmap == null) throw new IOException("图片解码失败");

        int rotation = readExifRotation(uri);
        if (rotation != 0) {
            Bitmap rotated = rotateBitmap(bitmap, rotation);
            bitmap.recycle();
            bitmap = rotated;
        }
        return bitmap;
    }

    private InputStream openStream(Uri uri) throws IOException {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("无法读取图片: " + uri);
        return is;
    }

    private int readExifRotation(Uri uri) {
        try (InputStream is = openStream(uri)) {
            int orientation = new ExifInterface(is)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static Bitmap rotateBitmap(Bitmap src, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    // ---------------------------------------------------------------- 权限（仅 API 28 保存需要）

    private boolean hasStoragePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE_PERMISSION) return;
        Runnable action = pendingPermissionAction;
        pendingPermissionAction = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) action.run();
        } else {
            Toast.makeText(this, "存储权限被拒绝，无法保存", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- UI

    private void setUiBusy() {
        progressBar.setVisibility(View.VISIBLE);
        cameraButton.setEnabled(false);
        pickButton.setEnabled(false);
        saveMatteButton.setEnabled(false);
    }

    private void setUiIdle() {
        progressBar.setVisibility(View.GONE);
        cameraButton.setEnabled(true);
        pickButton.setEnabled(true);
        saveMatteButton.setEnabled(currentMatte != null);
    }

    private void setMatteInfo(String text) {
        matteInfoText.setText(text);
    }

    /** 棋盘格背景，用于预览透明区域 */
    private Drawable createCheckerDrawable() {
        int cell = 12;
        int size = cell * 2;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = ((x / cell) + (y / cell)) % 2 == 1;
                pixels[y * size + x] = dark ? 0xFFCCCCCC : 0xFFFFFFFF;
            }
        }
        Bitmap tile = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        BitmapDrawable drawable = new BitmapDrawable(getResources(), tile);
        drawable.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        return drawable;
    }

    /** 从 Uri 提取文件名（不含后缀） */
    private String getImageName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        return name != null ? name : "matte";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (portraitMatting != null) {
            portraitMatting.close();
            portraitMatting = null;
        }
        if (currentMatte != null) {
            currentMatte.recycle();
            currentMatte = null;
        }
    }
}
