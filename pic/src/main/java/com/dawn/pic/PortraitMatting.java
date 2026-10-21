package com.dawn.pic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * 离线人像抠图：MODNet 人像软分割模型（ONNX）+ ONNX Runtime CPU 推理。
 *
 * <p>完全离线，不依赖任何联网服务；模型随 APK 打包，可运行在 arm64/armeabi-v7a 设备上。
 *
 * <p>用法：
 * <pre>
 *   try (PortraitMatting matting = new PortraitMatting(context)) {
 *       Bitmap cutout = matting.matte(photo);   // 背景 alpha = 0，可直接保存为透明 PNG
 *   }
 * </pre>
 *
 * <p>模型文件默认从 assets/{@value #DEFAULT_MODEL_ASSET} 读取，首次使用时解压到应用私有目录并复用。
 */
public class PortraitMatting implements Closeable {

    /** assets 中的默认模型路径（放在子目录，避免与宿主应用的同名资源冲突） */
    public static final String DEFAULT_MODEL_ASSET = "portrait_matting/modnet.onnx";

    /** 模型训练分辨率，输入张量固定为 512x512（模型本身支持动态尺寸） */
    private static final int DEFAULT_INPUT_SIZE = 512;

    /** alpha 软阈值：低于 LOW 视为纯背景，高于 HIGH 视为纯前景，抑制光晕与噪点 */
    private static final float ALPHA_CLIP_LOW = 0.02f;
    private static final float ALPHA_CLIP_HIGH = 0.98f;

    /** 只对过渡带像素做平滑，避免把纯前景/纯背景像素一起糊掉 */
    private static final float ALPHA_BAND_EPS = 0.01f;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final int inputWidth;
    private final int inputHeight;

    private boolean closed;

    /** 从 assets 加载默认模型 */
    public PortraitMatting(Context context) throws IOException {
        this(ensureModelFile(context), 0);
    }

    /**
     * 从 assets 加载默认模型，并指定推理分辨率。
     *
     * @param inputSize 推理边长（建议 32 的倍数，如 512 / 384 / 256）；传 0 表示用模型默认值
     */
    public PortraitMatting(Context context, int inputSize) throws IOException {
        this(ensureModelFile(context), inputSize);
    }

    /** 从指定模型文件加载 */
    public PortraitMatting(File modelFile) throws IOException {
        this(modelFile, 0);
    }

    /** 从指定模型文件加载，并指定推理分辨率 */
    public PortraitMatting(File modelFile, int inputSize) throws IOException {
        if (modelFile == null || !modelFile.isFile()) {
            throw new IOException("抠图模型不存在: " + modelFile);
        }
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
            options.setIntraOpNumThreads(threads);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(modelFile.getAbsolutePath(), options);
            inputName = session.getInputNames().iterator().next();

            long[] shape = readInputShape();
            int modelHeight = shape.length == 4 && shape[2] > 0 ? (int) shape[2] : DEFAULT_INPUT_SIZE;
            int modelWidth = shape.length == 4 && shape[3] > 0 ? (int) shape[3] : DEFAULT_INPUT_SIZE;
            // MODNet 编码器有 32 倍下采样，输入边长需为 32 的倍数
            int side = inputSize > 0 ? Math.max(32, inputSize / 32 * 32) : 0;
            inputHeight = side > 0 ? side : modelHeight;
            inputWidth = side > 0 ? side : modelWidth;
        } catch (OrtException e) {
            throw new IOException("抠图模型加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 抠出人像，返回带透明背景的 ARGB_8888 位图（尺寸与原图一致）。
     *
     * @param source 原始照片，非 ARGB_8888 会自动转换
     * @return 背景 alpha 被置为 0 的新位图
     */
    public synchronized Bitmap matte(Bitmap source) throws IOException {
        if (closed) throw new IOException("PortraitMatting 已关闭");
        if (source == null || source.isRecycled()) throw new IOException("待抠图的图片无效");

        Bitmap argb = toArgb8888(source);
        try {
            float[] alpha = inferAlpha(argb, inputWidth, inputHeight);
            refineAlpha(alpha, inputWidth, inputHeight);
            Bitmap mask = alphaToMask(alpha, inputWidth, inputHeight);
            try {
                return applyMask(argb, mask);
            } finally {
                mask.recycle();
            }
        } catch (OrtException e) {
            throw new IOException("抠图推理失败: " + e.getMessage(), e);
        } finally {
            if (argb != source) argb.recycle();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            session.close();
        } catch (OrtException ignored) {
        }
    }

    // ---------------------------------------------------------------- 模型 & 推理

    private long[] readInputShape() throws OrtException {
        Map<String, NodeInfo> infos = session.getInputInfo();
        NodeInfo info = infos.get(inputName);
        if (info != null && info.getInfo() instanceof TensorInfo) {
            return ((TensorInfo) info.getInfo()).getShape();
        }
        return new long[]{1, 3, DEFAULT_INPUT_SIZE, DEFAULT_INPUT_SIZE};
    }

    /** 缩放到模型输入尺寸 → 归一化到 [-1, 1] NCHW → 推理，返回 [0,1] 的 alpha（行优先） */
    private float[] inferAlpha(Bitmap src, int width, int height) throws OrtException {
        Bitmap scaled = Bitmap.createScaledBitmap(src, width, height, true);
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != src) scaled.recycle();

        int plane = width * height;
        float[] nchw = new float[plane * 3];
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            nchw[i] = ((p >> 16) & 0xFF) / 127.5f - 1f;
            nchw[plane + i] = ((p >> 8) & 0xFF) / 127.5f - 1f;
            nchw[2 * plane + i] = (p & 0xFF) / 127.5f - 1f;
        }

        try (OnnxTensor input = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(nchw), new long[]{1, 3, height, width});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            FloatBuffer buffer = output.getFloatBuffer();
            float[] alpha = new float[plane];
            buffer.get(alpha);
            return alpha;
        }
    }

    // ---------------------------------------------------------------- 后处理

    /** 软阈值 + 过渡带平滑：去掉低置信度噪点/光晕，并抹掉 512 分辨率上采样后的锯齿 */
    private static void refineAlpha(float[] alpha, int width, int height) {
        float span = ALPHA_CLIP_HIGH - ALPHA_CLIP_LOW;
        for (int i = 0; i < alpha.length; i++) {
            float a = (alpha[i] - ALPHA_CLIP_LOW) / span;
            alpha[i] = a < 0f ? 0f : (a > 1f ? 1f : a);
        }
        smoothTransparentBand(alpha, width, height);
    }

    private static void smoothTransparentBand(float[] alpha, int width, int height) {
        float[] src = alpha.clone();
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - 1);
            int y1 = Math.min(height - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                float a = src[idx];
                if (a <= ALPHA_BAND_EPS || a >= 1f - ALPHA_BAND_EPS) continue;
                int x0 = Math.max(0, x - 1);
                int x1 = Math.min(width - 1, x + 1);
                float sum = 0f;
                for (int yy = y0; yy <= y1; yy++) {
                    int row = yy * width;
                    for (int xx = x0; xx <= x1; xx++) sum += src[row + xx];
                }
                alpha[idx] = sum / 9f;
            }
        }
    }

    private static Bitmap alphaToMask(float[] alpha, int width, int height) {
        int[] pixels = new int[alpha.length];
        for (int i = 0; i < alpha.length; i++) {
            int a = Math.round(alpha[i] * 255f);
            // 预乘 alpha 的白色，供 DST_IN 使用
            pixels[i] = (a << 24) | (a << 16) | (a << 8) | a;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap applyMask(Bitmap source, Bitmap mask) {
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, 0f, 0f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(mask, null, new Rect(0, 0, width, height), paint);
        paint.setXfermode(null);
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private static Bitmap toArgb8888(Bitmap source) {
        if (source.getConfig() == Bitmap.Config.ARGB_8888) return source;
        return source.copy(Bitmap.Config.ARGB_8888, false);
    }

    /**
     * 把 assets 中的模型解压到应用私有目录，避免每次启动都读 25MB 资源。
     * 文件名带版本号，模型升级时换名即可触发重新解压。
     */
    private static synchronized File ensureModelFile(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "models");
        File model = new File(dir, "modnet_v1.onnx");
        if (model.isFile() && model.length() > 0) return model;
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建模型目录: " + dir.getAbsolutePath());
        }
        File tmp = new File(dir, "modnet_v1.onnx.tmp");
        try (InputStream in = context.getAssets().open(DEFAULT_MODEL_ASSET);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (!tmp.renameTo(model)) {
            tmp.delete();
            throw new IOException("模型解压失败: " + model.getAbsolutePath());
        }
        return model;
    }
}
