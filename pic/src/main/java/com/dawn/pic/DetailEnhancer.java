package com.dawn.pic;

import android.graphics.Bitmap;

/**
 * 照片清晰度增强：自适应降噪 + 亮度通道锐化（带边缘门控，抑制光晕与噪点放大），可选整数倍放大。
 *
 * <p>处理在 Y（亮度）通道上完成，再按亮度比例回写 RGB，避免彩色边缘；
 * alpha 通道原样保留，因此可以直接作用于抠图结果。
 *
 * <p>说明：这是传统图像处理（不是生成式超分），不会凭空"编造"细节，
 * 只放大并强化照片里已有的结构，适合证件照这类要求真实还原的场景。
 */
public final class DetailEnhancer {

    /** 放大后的像素上限，避免大图 OOM（约 2900×2900，1920×1080 可放大到 3840×2160） */
    private static final int MAX_OUTPUT_PIXELS = 8_500_000;

    /** 逐带处理的行数，控制峰值内存 */
    private static final int BAND_HEIGHT = 256;

    /** 5 抽头高斯模糊半径 */
    private static final int BLUR_RADIUS = 2;

    /** 9 抽头高斯模糊半径（粗尺度，用于补偿失焦/缩放造成的模糊） */
    private static final int COARSE_RADIUS = 4;

    private static final float[] KERNEL_5 = {0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f};
    private static final float[] KERNEL_9 = {
            0.00390625f, 0.03125f, 0.109375f, 0.21875f, 0.2734375f,
            0.21875f, 0.109375f, 0.03125f, 0.00390625f};

    private static final float DEFAULT_DETAIL = 1.0f;
    private static final float DEFAULT_DENOISE = 0.6f;

    private DetailEnhancer() {
    }

    /** 默认参数（细节 1.0、降噪 0.6、不放大）增强清晰度 */
    public static Bitmap enhance(Bitmap src) {
        return enhance(src, DEFAULT_DETAIL, DEFAULT_DENOISE, 1);
    }

    /**
     * @param src             原图（ARGB_8888；透明像素会被跳过）
     * @param detailStrength  细节强度，建议 0.5~2.0（会按原图锐度自动缩放）
     * @param denoiseStrength 降噪强度，0 = 不降噪，建议 0~1
     * @param upscaleFactor   放大倍数（1 或 2）；超过 {@link #MAX_OUTPUT_PIXELS} 时自动忽略
     * @return 新的 ARGB_8888 位图，尺寸为原图 × upscaleFactor
     */
    public static Bitmap enhance(Bitmap src, float detailStrength, float denoiseStrength, int upscaleFactor) {
        if (src == null || src.isRecycled()) {
            throw new IllegalArgumentException("待增强的图片无效");
        }
        Bitmap work = toArgb8888(src);
        try {
            int factor = Math.max(1, upscaleFactor);
            boolean upscaled = false;
            if (factor > 1) {
                long target = (long) work.getWidth() * factor * work.getHeight() * factor;
                if (target <= MAX_OUTPUT_PIXELS) {
                    try {
                        Bitmap scaled = Bitmap.createScaledBitmap(
                                work, work.getWidth() * factor, work.getHeight() * factor, true);
                        if (scaled != work) {
                            if (work != src) work.recycle();
                            work = scaled;
                            upscaled = true;
                        }
                    } catch (OutOfMemoryError ignored) {
                        // 内存不足时退回不放大，保证基本增强仍然可用
                    }
                }
            }
            // 放大后双三次插值本身已抑制高频噪声，再做双边降噪收益低、耗时高
            float denoise = upscaled ? 0f : denoiseStrength;
            return enhanceInPlaceCopy(work, detailStrength * autoStrengthScale(work), denoise);
        } finally {
            if (work != src && !work.isRecycled()) work.recycle();
        }
    }

    // ---------------------------------------------------------------- 核心处理

    /**
     * 按原图锐度自适应调整力度：越模糊加成越多，已经很锐的照片只做轻微提升，避免过锐利的光晕。
     */
    static float autoStrengthScale(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int sampleWidth = Math.min(256, width);
        int sampleHeight = Math.max(1, height * sampleWidth / width);

        Bitmap small = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true);
        int[] pixels = new int[sampleWidth * sampleHeight];
        small.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);
        if (small != bitmap) small.recycle();

        float[] luma = new float[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            luma[i] = ((pixels[i] >>> 24) & 0xFF) > 128 ? luminance(pixels[i]) : Float.NaN;
        }

        double sum = 0;
        int count = 0;
        for (int y = 1; y < sampleHeight - 1; y++) {
            for (int x = 1; x < sampleWidth - 1; x++) {
                int idx = y * sampleWidth + x;
                if (Float.isNaN(luma[idx])) continue;
                float gx = luma[idx + 1] - luma[idx - 1];
                float gy = luma[idx + sampleWidth] - luma[idx - sampleWidth];
                if (Float.isNaN(gx) || Float.isNaN(gy)) continue;
                sum += Math.sqrt(gx * gx + gy * gy);
                count++;
            }
        }
        if (count == 0) return 1f;

        float meanGradient = (float) (sum / count);
        float scale = 12f / (meanGradient + 5f);
        return clamp(scale, 0.45f, 2.0f);
    }

    private static Bitmap enhanceInPlaceCopy(Bitmap work, float detailStrength, float denoiseStrength) {
        int width = work.getWidth();
        int height = work.getHeight();
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        float rangeSigma = 8f + 22f * clamp(denoiseStrength, 0f, 1f);
        float[] rangeLut = buildRangeLut(2f * rangeSigma * rangeSigma);

        int bandRows = Math.min(BAND_HEIGHT, height);
        for (int bandTop = 0; bandTop < height; bandTop += bandRows) {
            int bandBottom = Math.min(height, bandTop + bandRows);
            int srcTop = Math.max(0, bandTop - COARSE_RADIUS - 1);
            int srcBottom = Math.min(height, bandBottom + COARSE_RADIUS + 1);
            int srcRows = srcBottom - srcTop;

            int[] pixels = new int[width * srcRows];
            work.getPixels(pixels, 0, width, 0, srcTop, width, srcRows);

            float[] luma = new float[width * srcRows];
            for (int i = 0; i < luma.length; i++) {
                luma[i] = luminance(pixels[i]);
            }

            float[] base = denoiseStrength > 0f
                    ? bilateral3x3(luma, width, srcRows, rangeLut)
                    : luma;
            float[] blurredFine = gaussianBlur(base, width, srcRows, KERNEL_5, BLUR_RADIUS);
            float[] blurredCoarse = gaussianBlur(base, width, srcRows, KERNEL_9, 4);

            int outRows = bandBottom - bandTop;
            int[] outPixels = new int[width * outRows];
            for (int y = 0; y < outRows; y++) {
                int localRow = (bandTop + y) - srcTop;
                for (int x = 0; x < width; x++) {
                    int idx = localRow * width + x;
                    int pixel = pixels[idx];
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha == 0) {
                        outPixels[y * width + x] = pixel;
                        continue;
                    }
                    float value = base[idx];
                    // 双尺度高频分量：细尺度补细节，粗尺度补模糊（失焦/缩放）
                    float detail = 0.6f * (value - blurredFine[idx])
                            + 0.9f * (value - blurredCoarse[idx]);
                    float gain = edgeGate(luma, width, srcRows, x, localRow)
                            * detailStrength
                            * (alpha / 255f);
                    float newValue = clamp(value + gain * detail, 0f, 255f);
                    outPixels[y * width + x] = rescale(pixel, value, newValue);
                }
            }
            out.setPixels(outPixels, 0, width, 0, bandTop, width, outRows);
        }
        return out;
    }

    /** 按亮度比例缩放 RGB，保持色相/饱和度不变 */
    private static int rescale(int pixel, float oldLuma, float newLuma) {
        int alpha = (pixel >>> 24) & 0xFF;
        if (oldLuma <= 1f) {
            return pixel;
        }
        float ratio = newLuma / oldLuma;
        int r = clamp255(((pixel >> 16) & 0xFF) * ratio);
        int g = clamp255(((pixel >> 8) & 0xFF) * ratio);
        int b = clamp255((pixel & 0xFF) * ratio);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** 边缘越强，锐化越强；平坦区域只给 35%，避免放大噪点 */
    private static float edgeGate(float[] luma, int width, int rows, int x, int row) {
        int left = Math.max(0, x - 1);
        int right = Math.min(width - 1, x + 1);
        int up = Math.max(0, row - 1);
        int down = Math.min(rows - 1, row + 1);

        float tl = luma[up * width + left];
        float tc = luma[up * width + x];
        float tr = luma[up * width + right];
        float ml = luma[row * width + left];
        float mr = luma[row * width + right];
        float bl = luma[down * width + left];
        float bc = luma[down * width + x];
        float br = luma[down * width + right];

        float gx = (tr + 2f * mr + br) - (tl + 2f * ml + bl);
        float gy = (bl + 2f * bc + br) - (tl + 2f * tc + tr);
        float magnitude = (float) Math.sqrt(gx * gx + gy * gy) * 0.25f;
        return 0.35f + 0.65f * Math.min(1f, magnitude / 10f);
    }

    /** 3×3 双边滤波：只在亮度接近的邻域内平滑，保住边缘 */
    private static float[] bilateral3x3(float[] src, int width, int rows, float[] rangeLut) {
        final float[] spatial = {1f, 2f, 1f, 2f, 4f, 2f, 1f, 2f, 1f};
        float[] dst = new float[src.length];
        for (int y = 0; y < rows; y++) {
            int up = Math.max(0, y - 1);
            int down = Math.min(rows - 1, y + 1);
            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - 1);
                int right = Math.min(width - 1, x + 1);
                float center = src[y * width + x];
                float sum = 0f;
                float weightSum = 0f;
                for (int dy = 0; dy < 3; dy++) {
                    int yy = dy == 0 ? up : (dy == 1 ? y : down);
                    for (int dx = 0; dx < 3; dx++) {
                        int xx = dx == 0 ? left : (dx == 1 ? x : right);
                        float sample = src[yy * width + xx];
                        int diff = Math.round(Math.abs(sample - center));
                        float weight = spatial[dy * 3 + dx] * rangeLut[diff > 255 ? 255 : diff];
                        sum += sample * weight;
                        weightSum += weight;
                    }
                }
                dst[y * width + x] = sum / weightSum;
            }
        }
        return dst;
    }

    /** 亮度差 0~255 的高斯权重查表，避免逐像素调用 Math.exp */
    private static float[] buildRangeLut(float rangeDenom) {
        float[] lut = new float[256];
        for (int i = 0; i < lut.length; i++) {
            lut[i] = (float) Math.exp(-(i * (float) i) / rangeDenom);
        }
        return lut;
    }

    /** 可分离高斯模糊（kernel 长度为奇数，radius = (len-1)/2） */
    private static float[] gaussianBlur(float[] src, int width, int rows, float[] kernel, int radius) {
        float[] tmp = new float[src.length];
        float[] dst = new float[src.length];

        for (int y = 0; y < rows; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                float sum = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int xx = Math.min(width - 1, Math.max(0, x + k));
                    sum += src[rowOffset + xx] * kernel[k + radius];
                }
                tmp[rowOffset + x] = sum;
            }
        }
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int yy = Math.min(rows - 1, Math.max(0, y + k));
                    sum += tmp[yy * width + x] * kernel[k + radius];
                }
                dst[y * width + x] = sum;
            }
        }
        return dst;
    }

    private static float luminance(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    private static Bitmap toArgb8888(Bitmap source) {
        if (source.getConfig() == Bitmap.Config.ARGB_8888) return source;
        return source.copy(Bitmap.Config.ARGB_8888, true);
    }

    private static int clamp255(float value) {
        int v = Math.round(value);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
