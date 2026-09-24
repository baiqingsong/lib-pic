package com.dawn.pic;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 透明区域分析与 JSON 生成工具。
 * 对应 Python 项目 pngToJson.py 的核心逻辑。
 *
 * 输入：含透明镂空的 RGBA PNG 图片
 * 输出：每个透明区域的坐标/尺寸 JSON，以及可选的切图文件
 */
public class PngAnalyzer {

    // alpha 值 <= 此阈值视为透明（处理抗锯齿软边）
    private static final int TRANSPARENT_ALPHA_THRESHOLD = 25;
    // 重叠合并：小区域与大区域的重叠面积占小区域的比例超过该值时合并
    private static final float MERGE_OVERLAP_RATIO = 0.75f;
    // 相邻合并：小区域面积占大区域的比例低于该值且相邻时并入大区域
    private static final float SMALL_AREA_RATIO = 0.3f;
    // 相邻判定：两区域间无透明像素的最大连续列/行数（像素）
    private static final int ADJACENT_GAP = 30;

    private static boolean isTransparent(int pixel) {
        return ((pixel >> 24) & 0xFF) <= TRANSPARENT_ALPHA_THRESHOLD;
    }

    /**
     * 在位图中找出所有透明区域，返回列表，每项为 int[]{x, y, width, height}。
     * 流程：生成透明 mask → DFS 找连通区 → 过滤小噪点 → 合并重叠 → 合并相邻小区域 → 排序。
     */
    public static List<int[]> findTransparentAreas(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // DFS 找连通区域，过滤 100x100 以下的噪点
        boolean[] visited = new boolean[width * height];
        // 预分配 DFS 栈，避免每次 DFS 创建新对象；入栈即标记可确保每个像素最多入栈一次
        int[] dfsStack = new int[width * height];
        List<int[]> areas = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (!visited[idx] && isTransparent(pixels[idx])) {
                    int[] bounds = dfs(x, y, pixels, visited, width, height, dfsStack);
                    int w = bounds[2] - bounds[0] + 1;
                    int h = bounds[3] - bounds[1] + 1;
                    if (w >= 100 && h >= 100) {
                        areas.add(new int[]{bounds[0], bounds[1], w, h});
                    }
                }
            }
        }

        // 合并部分重叠的区域
        areas = mergeOverlapping(areas);

        // 合并相邻的过小区域
        areas = mergeAdjacentSmall(areas, pixels, width, height);

        // 先按 y 再按 x 排序（从上到下、从左到右）
        areas.sort((a, b) -> a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]);

        return areas;
    }

    /**
     * 非递归 DFS 找连通透明区域，返回包围盒 int[]{minX, minY, maxX, maxY}。
     * 使用预分配的原始 int[] 栈（入栈即标记），避免 Integer 装箱与重复入栈。
     */
    private static int[] dfs(int startX, int startY, int[] pixels, boolean[] visited,
                              int width, int height, int[] stack) {
        int top = 0;
        int startPos = startY * width + startX;
        visited[startPos] = true;
        stack[top++] = startPos;

        int minX = startX, maxX = startX, minY = startY, maxY = startY;

        while (top > 0) {
            int pos = stack[--top];
            int cx = pos % width;
            int cy = pos / width;
            if (cx < minX) minX = cx;
            if (cx > maxX) maxX = cx;
            if (cy < minY) minY = cy;
            if (cy > maxY) maxY = cy;

            // 右
            if (cx + 1 < width)  { int np = pos + 1;     if (!visited[np] && isTransparent(pixels[np])) { visited[np] = true; stack[top++] = np; } }
            // 左
            if (cx - 1 >= 0)     { int np = pos - 1;     if (!visited[np] && isTransparent(pixels[np])) { visited[np] = true; stack[top++] = np; } }
            // 下
            if (cy + 1 < height) { int np = pos + width; if (!visited[np] && isTransparent(pixels[np])) { visited[np] = true; stack[top++] = np; } }
            // 上
            if (cy - 1 >= 0)     { int np = pos - width; if (!visited[np] && isTransparent(pixels[np])) { visited[np] = true; stack[top++] = np; } }
        }

        return new int[]{minX, minY, maxX, maxY};
    }

    /**
     * 合并部分重叠的区域：若小区域大部分（>= MERGE_OVERLAP_RATIO）被大区域覆盖，
     * 则将两者合并为外接矩形。
     */
    private static List<int[]> mergeOverlapping(List<int[]> areas) {
        // 按面积降序排列，优先处理大区域
        areas.sort((a, b) -> (b[2] * b[3]) - (a[2] * a[3]));
        int n = areas.size();
        boolean[] removed = new boolean[n];
        List<int[]> result = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (removed[i]) continue;
            int[] cur = areas.get(i).clone();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < n; j++) {
                    if (j == i || removed[j]) continue;
                    int[] b = areas.get(j);
                    int ix0 = Math.max(cur[0], b[0]);
                    int iy0 = Math.max(cur[1], b[1]);
                    int ix1 = Math.min(cur[0] + cur[2], b[0] + b[2]);
                    int iy1 = Math.min(cur[1] + cur[3], b[1] + b[3]);
                    if (ix0 >= ix1 || iy0 >= iy1) continue;
                    long inter = (long)(ix1 - ix0) * (iy1 - iy0);
                    if (inter >= (long)b[2] * b[3] * MERGE_OVERLAP_RATIO) {
                        int nx0 = Math.min(cur[0], b[0]);
                        int ny0 = Math.min(cur[1], b[1]);
                        int nx1 = Math.max(cur[0] + cur[2], b[0] + b[2]);
                        int ny1 = Math.max(cur[1] + cur[3], b[1] + b[3]);
                        cur = new int[]{nx0, ny0, nx1 - nx0, ny1 - ny0};
                        removed[j] = true;
                        changed = true;
                    }
                }
            }
            result.add(cur);
        }
        return result;
    }

    /**
     * 水平方向：统计 x ∈ [x0, x1) 范围内，
     * 最长的「不含透明像素」的连续列数（即断点宽度）。
     */
    private static int maxTransparentBreakH(int[] pixels, int width, int height,
                                            int x0, int x1, int y0, int y1) {
        int run = 0, maxRun = 0;
        for (int x = x0; x < x1; x++) {
            boolean hasTrans = false;
            for (int y = y0; y <= y1; y++) {
                if (y >= 0 && y < height && x >= 0 && x < width && isTransparent(pixels[y * width + x])) {
                    hasTrans = true;
                    break;
                }
            }
            if (hasTrans) {
                run = 0;
            } else {
                run++;
                if (run > maxRun) maxRun = run;
            }
        }
        return maxRun;
    }

    /**
     * 垂直方向：统计 y ∈ [y0, y1) 范围内，
     * 最长的「不含透明像素」的连续行数（即断点高度）。
     */
    private static int maxTransparentBreakV(int[] pixels, int width, int height,
                                            int y0, int y1, int x0, int x1) {
        int run = 0, maxRun = 0;
        for (int y = y0; y < y1; y++) {
            boolean hasTrans = false;
            for (int x = x0; x <= x1; x++) {
                if (x >= 0 && x < width && y >= 0 && y < height && isTransparent(pixels[y * width + x])) {
                    hasTrans = true;
                    break;
                }
            }
            if (hasTrans) {
                run = 0;
            } else {
                run++;
                if (run > maxRun) maxRun = run;
            }
        }
        return maxRun;
    }

    /**
     * 合并相邻的过小区域：若小区域面积 < 大区域面积 * SMALL_AREA_RATIO
     * 且两区域之间透明像素断点 <= ADJACENT_GAP，则将小区域并入大区域。
     */
    private static List<int[]> mergeAdjacentSmall(List<int[]> areas, int[] pixels,
                                                   int width, int height) {
        areas.sort((a, b) -> (b[2] * b[3]) - (a[2] * a[3]));
        int n = areas.size();
        boolean[] removed = new boolean[n];
        List<int[]> result = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (removed[i]) continue;
            int[] cur = areas.get(i).clone();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < n; j++) {
                    if (j == i || removed[j]) continue;
                    int[] b = areas.get(j);
                    // b 的面积必须远小于 cur
                    if ((long)b[2] * b[3] >= (long)cur[2] * cur[3] * SMALL_AREA_RATIO) continue;

                    int yOverlap = Math.min(cur[1] + cur[3], b[1] + b[3]) - Math.max(cur[1], b[1]);
                    int xOverlap = Math.min(cur[0] + cur[2], b[0] + b[2]) - Math.max(cur[0], b[0]);
                    boolean merged = false;

                    // 水平相邻：b 在 cur 左侧或右侧
                    if (yOverlap > 0 && yOverlap >= b[3] * 0.5f) {
                        int gapLo = b[0] < cur[0] ? b[0] + b[2] : cur[0] + cur[2];
                        int gapHi = b[0] < cur[0] ? cur[0] : b[0];
                        int y0 = Math.max(cur[1], b[1]);
                        int y1 = Math.min(cur[1] + cur[3], b[1] + b[3]) - 1;
                        if (maxTransparentBreakH(pixels, width, height, gapLo, gapHi, y0, y1) <= ADJACENT_GAP) {
                            merged = true;
                        }
                    }
                    // 垂直相邻：b 在 cur 上方或下方
                    if (!merged && xOverlap > 0 && xOverlap >= b[2] * 0.5f) {
                        int gapLo = b[1] < cur[1] ? b[1] + b[3] : cur[1] + cur[3];
                        int gapHi = b[1] < cur[1] ? cur[1] : b[1];
                        int x0 = Math.max(cur[0], b[0]);
                        int x1 = Math.min(cur[0] + cur[2], b[0] + b[2]) - 1;
                        if (maxTransparentBreakV(pixels, width, height, gapLo, gapHi, x0, x1) <= ADJACENT_GAP) {
                            merged = true;
                        }
                    }

                    if (merged) {
                        int nx0 = Math.min(cur[0], b[0]);
                        int ny0 = Math.min(cur[1], b[1]);
                        int nx1 = Math.max(cur[0] + cur[2], b[0] + b[2]);
                        int ny1 = Math.max(cur[1] + cur[3], b[1] + b[3]);
                        cur = new int[]{nx0, ny0, nx1 - nx0, ny1 - ny0};
                        removed[j] = true;
                        changed = true;
                    }
                }
            }
            result.add(cur);
        }
        return result;
    }

    /**
     * 根据透明区域生成 JSON 字符串。
     *
     * @param bitmap  原始位图
     * @param areas   透明区域列表（每项 int[]{x, y, width, height}）
     * @param isDouble true = 双份模式（两两配对），false = 单份模式
     * @return 格式化 JSON 字符串
     */
    public static String generateJson(Bitmap bitmap, List<int[]> areas, boolean isDouble)
            throws JSONException {
        int imgW = bitmap.getWidth();
        int imgH = bitmap.getHeight();
        boolean landscape = imgW > imgH; // 横图

        JSONObject data = new JSONObject();
        data.put("bgCover", "0");
        data.put("isCut", isDouble ? "0" : "1");
        data.put("rotation", landscape ? "90" : "0");
        // 输出时统一以竖向尺寸表示（短边为 width，长边为 height）
        data.put("width", landscape ? imgH : imgW);
        data.put("height", landscape ? imgW : imgH);

        JSONArray items = new JSONArray();

        if (isDouble) {
            // 按尺寸相近（宽高各误差 ≤ 20%）进行配对，避免把大小悬殊的区域强行配对
            boolean[] used = new boolean[areas.size()];
            int itemIdx = 0;
            for (int i = 0; i < areas.size(); i++) {
                if (used[i]) continue;
                used[i] = true;
                int[] a1 = areas.get(i);

                int matchJ = -1;
                for (int j = i + 1; j < areas.size(); j++) {
                    if (used[j]) continue;
                    int[] a2 = areas.get(j);
                    float wRatio = (float) Math.min(a1[2], a2[2]) / Math.max(a1[2], a2[2]);
                    float hRatio = (float) Math.min(a1[3], a2[3]) / Math.max(a1[3], a2[3]);
                    if (wRatio >= 0.8f && hRatio >= 0.8f) {
                        matchJ = j;
                        break;
                    }
                }

                JSONObject item = new JSONObject();
                item.put("index", itemIdx++);
                item.put("left", landscape ? (imgH - a1[1] - a1[3]) : a1[0]);
                item.put("top",  landscape ? a1[0] : a1[1]);
                if (matchJ >= 0) {
                    used[matchJ] = true;
                    int[] a2 = areas.get(matchJ);
                    item.put("left_2", landscape ? (imgH - a2[1] - a2[3]) : a2[0]);
                    item.put("top_2",  landscape ? a2[0] : a2[1]);
                    item.put("repeat", "0");
                } else {
                    // 找不到尺寸相近的配对区域，退化为单份
                    item.put("repeat", "1");
                }
                item.put("rotation", landscape ? "90" : "0");
                item.put("width",  a1[2]);
                item.put("height", a1[3]);
                items.put(item);
            }
        } else {
            for (int i = 0; i < areas.size(); i++) {
                int[] a = areas.get(i);
                JSONObject item = new JSONObject();
                item.put("index", i);
                item.put("left",  landscape ? (imgH - a[1] - a[3]) : a[0]);
                item.put("top",   landscape ? a[0] : a[1]);
                item.put("rotation", landscape ? "90" : "0");
                item.put("width",  a[2]);
                item.put("height", a[3]);
                item.put("repeat", "1");
                items.put(item);
            }
        }

        // photograph 反映实际需要的照片张数，在 items 构建完后赋值
        data.put("photograph", items.length());
        data.put("items", items);
        return data.toString(2);
    }

    /**
     * 将每个透明区域裁剪为 PNG，保存到 outputDir/child/{imageName}_1.png 等文件。
     *
     * @param bitmap    原始 ARGB 位图
     * @param areas     透明区域列表
     * @param outputDir 根输出目录
     * @param imageName 图片名称（不含后缀），用作文件名前缀
     * @return 已保存文件的路径列表
     */
    public static List<String> saveTransparentMasks(Bitmap bitmap, List<int[]> areas,
                                                     File outputDir, String imageName)
            throws IOException {
        File childDir = new File(outputDir, "child");
        if (!childDir.exists() && !childDir.mkdirs()) {
            throw new IOException("无法创建 child 目录: " + childDir.getAbsolutePath());
        }
        List<String> saved = new ArrayList<>();
        for (int i = 0; i < areas.size(); i++) {
            int[] area = areas.get(i);
            Bitmap cropped = Bitmap.createBitmap(bitmap, area[0], area[1], area[2], area[3]);
            File outFile = new File(childDir, imageName + "_" + (i + 1) + ".png");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            cropped.recycle();
            saved.add(outFile.getAbsolutePath());
        }
        return saved;
    }

    /**
     * 追加/更新 outputDir/pic.json 中的一条记录。
     * key 为图片名（不含后缀），value 为解析后的 JSON 对象。
     */
    public static void savePicJson(File outputDir, String imageName, String jsonString)
            throws IOException, JSONException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("无法创建输出目录: " + outputDir.getAbsolutePath());
        }
        File picJsonFile = new File(outputDir, "pic.json");
        JSONObject root = new JSONObject();
        if (picJsonFile.exists()) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(picJsonFile))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            try { root = new JSONObject(sb.toString()); } catch (JSONException ignored) {}
        }
        root.put(imageName, new JSONObject(jsonString));
        try (FileWriter writer = new FileWriter(picJsonFile)) {
            writer.write(root.toString(2));
        }
    }

    /** 在预览图上用彩色编号边框标注每个识别出的透明区域，便于调试 */    public static Bitmap drawDebugOverlay(Bitmap bitmap, List<int[]> areas) {
        Bitmap result = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        // 标注尺寸随图片短边等比缩放，确保在大图和小图上均可读
        float textSize   = Math.max(28f, Math.min(80f, Math.min(bitmap.getWidth(), bitmap.getHeight()) * 0.12f));
        float stroke     = Math.max(3f,  textSize * 0.15f);
        float labelW     = textSize * 1.8f;
        float labelH     = textSize * 1.25f;
        Paint rectPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(stroke);
        Paint bgPaint    = new Paint();
        Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        int[] colors = {0xFFFF3333, 0xFF33CC33, 0xFF3366FF, 0xFFFF9900, 0xFFCC33CC};
        for (int i = 0; i < areas.size(); i++) {
            int[] a = areas.get(i);
            int c = colors[i % colors.length];
            rectPaint.setColor(c);
            bgPaint.setColor(c);
            canvas.drawRect(a[0], a[1], a[0] + a[2], a[1] + a[3], rectPaint);
            canvas.drawRect(a[0], a[1], a[0] + labelW, a[1] + labelH, bgPaint);
            canvas.drawText("#" + i, a[0] + stroke + 2, a[1] + labelH - stroke, textPaint);
        }
        return result;
    }

    /**
     * 将照片填充到每个透明区域，并在区域左上角标注索引编号，用于预览。
     *
     * @param base  原始模板位图（ARGB）
     * @param photo 要填入的照片位图
     * @param areas 透明区域列表
     * @return 合成后的新位图
     */
    public static Bitmap pastePhotoToTransparentAreas(Bitmap base, Bitmap photo, List<int[]> areas) {
        Bitmap result = base.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);

        Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.WHITE);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(50f);

        int bw = base.getWidth();
        int bh = base.getHeight();
        int[] basePixels = new int[bw * bh];
        base.getPixels(basePixels, 0, bw, 0, 0, bw, bh);

        for (int i = 0; i < areas.size(); i++) {
            int[] area = areas.get(i);
            int ax = area[0], ay = area[1], aw = area[2], ah = area[3];

            Bitmap scaled = Bitmap.createScaledBitmap(photo, aw, ah, true);
            int[] scaledPixels = new int[aw * ah];
            scaled.getPixels(scaledPixels, 0, aw, 0, 0, aw, ah);

            // 仅将照片贴到透明像素处（保留模板中不透明的素材）
            int[] masked = new int[aw * ah];
            for (int py = 0; py < ah; py++) {
                for (int px = 0; px < aw; px++) {
                    int baseAlpha = (basePixels[(ay + py) * bw + (ax + px)] >> 24) & 0xFF;
                    masked[py * aw + px] = baseAlpha <= TRANSPARENT_ALPHA_THRESHOLD
                            ? scaledPixels[py * aw + px]
                            : Color.TRANSPARENT;
                }
            }
            Bitmap maskedBmp = Bitmap.createBitmap(masked, aw, ah, Bitmap.Config.ARGB_8888);
            canvas.drawBitmap(maskedBmp, ax, ay, photoPaint);
            maskedBmp.recycle();
            scaled.recycle();

            // 绘制索引编号（白底黑字）
            String text = String.valueOf(i);
            Rect textBounds = new Rect();
            textPaint.getTextBounds(text, 0, text.length(), textBounds);
            float tx = ax + 10f;
            float ty = ay + 10f + textBounds.height();
            canvas.drawRect(tx - 5, ay + 5, tx + textBounds.width() + 5, ty + 5, bgPaint);
            canvas.drawText(text, tx, ty, textPaint);
        }

        return result;
    }

    /**
     * 根据 generateJson() 输出的 JSON 配置，将照片列表合成到模板中。
     * 遵循 bgCover / rotation / repeat / left_2 / top_2 等所有字段。
     *
     * @param template   含透明镂空的模板位图（ARGB_8888）
     * @param jsonString generateJson() 返回的 JSON 字符串
     * @param photos     按 index 顺序排列的人像列表，不足时循环使用最后一张
     * @return           合成后的位图
     */
    public static Bitmap compositePhotos(Bitmap template, String jsonString, List<Bitmap> photos)
            throws JSONException {
        if (template == null || photos == null || photos.isEmpty()) return null;

        JSONObject cfg   = new JSONObject(jsonString);
        int bgCover      = jsonOptInt(cfg, "bgCover", 0);
        int rotation     = jsonOptInt(cfg, "rotation", 0);
        int canvasW      = jsonOptInt(cfg, "width",  template.getWidth());
        int canvasH      = jsonOptInt(cfg, "height", template.getHeight());
        JSONArray items  = cfg.optJSONArray("items");

        // 横图时 JSON 记录的是竖向尺寸，需要交换回来作为实际画布尺寸
        if (rotation == 90) { int t = canvasW; canvasW = canvasH; canvasH = t; }

        Bitmap result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        Bitmap frame = rotation == 90 ? rotateBitmap(template, 90) : template;

        // bgCover=1: 相框在照片下面；bgCover=0: 相框压在照片上面
        if (bgCover == 1) canvas.drawBitmap(frame, 0, 0, null);

        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item  = items.getJSONObject(i);
                int itemW        = jsonOptInt(item, "width",  0);
                int itemH        = jsonOptInt(item, "height", 0);
                int left         = jsonOptInt(item, "left",   0);
                int top          = jsonOptInt(item, "top",    0);
                int left2        = jsonOptInt(item, "left_2", -1);
                int top2         = jsonOptInt(item, "top_2",  -1);
                int repeat       = jsonOptInt(item, "repeat", 1);
                int itemRotation = jsonOptInt(item, "rotation", 0);
                int photoIdx     = Math.min(jsonOptInt(item, "index", i), photos.size() - 1);

                if (itemW <= 0 || itemH <= 0) continue;
                Bitmap photo = photos.get(photoIdx);
                if (photo == null) continue;

                Bitmap cropped = cropToFit(photo, itemW, itemH);
                if (itemRotation == 90) {
                    Bitmap rotated = rotateBitmap(cropped, 90);
                    cropped.recycle();
                    cropped = rotated;
                }

                canvas.drawBitmap(cropped, left, top, null);
                // repeat="0" 表示双份，需要在第二个位置再绘制一次
                if (repeat == 0 && left2 >= 0 && top2 >= 0) {
                    canvas.drawBitmap(cropped, left2, top2, null);
                }
                cropped.recycle();
            }
        }

        if (bgCover == 0) canvas.drawBitmap(frame, 0, 0, null);

        if (frame != template && !frame.isRecycled()) frame.recycle();
        return result;
    }

    /** 居中裁剪并缩放 source，使结果恰好为 targetW × targetH */
    private static Bitmap cropToFit(Bitmap source, int targetW, int targetH) {
        int srcW = source.getWidth(), srcH = source.getHeight();
        float scale = Math.max((float) targetW / srcW, (float) targetH / srcH);
        int scaledW = Math.round(srcW * scale);
        int scaledH = Math.round(srcH * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true);
        int x = (scaledW - targetW) / 2;
        int y = (scaledH - targetH) / 2;
        Bitmap cropped = Bitmap.createBitmap(scaled, x, y, targetW, targetH);
        if (scaled != source && scaled != cropped) scaled.recycle();
        return cropped;
    }

    /** 顺时针旋转 Bitmap degrees 度，返回新 Bitmap */
    private static Bitmap rotateBitmap(Bitmap src, int degrees) {
        Matrix m = new Matrix();
        m.setRotate(degrees, src.getWidth() / 2f, src.getHeight() / 2f);
        // 旋转后平移到第一象限
        float[] v = new float[9];
        m.getValues(v);
        m.postTranslate(-v[Matrix.MTRANS_X], -v[Matrix.MTRANS_Y]);
        int dstW = (degrees % 180 == 90) ? src.getHeight() : src.getWidth();
        int dstH = (degrees % 180 == 90) ? src.getWidth()  : src.getHeight();
        Bitmap dst = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
        new Canvas(dst).drawBitmap(src, m, new Paint(Paint.ANTI_ALIAS_FLAG));
        return dst;
    }

    /** 兼容 JSON 中值为字符串或整数两种情况 */
    private static int jsonOptInt(JSONObject obj, String key, int def) {
        Object v = obj.opt(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}

