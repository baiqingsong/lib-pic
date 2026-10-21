# lib-pic

图片的相关处理

- `pic` 模块：可独立发布的图片处理库（`PngAnalyzer` + `PortraitMatting`）。
- `app` 模块：人像抠图测试应用（拍照 / 选图 → 抠图 → 保存透明 PNG）。

## 功能

### 1. 透明镂空模板解析（`PngAnalyzer`）

输入含透明镂空的 RGBA PNG，识别每个透明区域并输出坐标/尺寸 JSON，支持切图保存、照片合成预览。

### 2. 离线人像抠图（`PortraitMatting`）

用 **MODNet**（AAAI 2022, trimap-free 人像软分割）ONNX 模型 + **ONNX Runtime** CPU 推理，在设备本地抠出人像，输出透明背景 PNG。

- **完全离线**：不联网、不依赖 Face++ 等第三方人体抠像接口，无调用费用与授权费。
- **模型随 APK 打包**：`pic/src/main/assets/portrait_matting/modnet.onnx`（约 25 MB），首次使用时解压到应用私有目录后复用。
- **软边保留**：模型输出为 0~1 的 alpha，经软阈值 + 过渡带平滑后作为遮罩，发丝部分保留半透明。

#### 用法

```java
// context 可传 Activity / Application
try (PortraitMatting matting = new PortraitMatting(context)) {
    Bitmap cutout = matting.matte(bitmap);   // 背景 alpha = 0，尺寸与原图一致
    try (FileOutputStream fos = new FileOutputStream(outFile)) {
        cutout.compress(Bitmap.CompressFormat.PNG, 100, fos);   // 透明背景 PNG
    }
}
```

`PortraitMatting` 内部持有 ORT session，**建议复用同一实例**（首次加载约 0.2~1.2 s，之后每次推理不再加载模型）；`matte()` 为同步调用，请在子线程执行。

推理分辨率可调（不影响输出尺寸，只影响细节与耗时）：

```java
new PortraitMatting(context, 384);   // 边长需为 32 的倍数，默认 512（模型原生分辨率）
```

#### 性能（RK3568，4×Cortex-A55，Android 11，实测）

| 推理分辨率 | 单张耗时（768×768 输入照片） |
| --- | --- |
| 512（默认） | ≈ 3.5 s |
| 384 | ≈ 2.3 s |

模型加载（已解压）≈ 0.2~0.3 s；首次启动解压 25 MB 模型约 1 s。示例应用体积增加约 25 MB（模型）+ 约 17 MB（各 ABI 的 ORT 动态库）。

> 进程内首次调用还要额外承担 JIT 预热与首次推理开销，冷启动实测约 8 s；复用 `PortraitMatting` 实例后稳定在上表数值。

#### 模型与授权

- 模型来源：[HivisionIDPhotos](https://github.com/Zeyi-Lin/HivisionIDPhotos) 发布的 `modnet_photographic_portrait_matting.onnx`（`pretrained-model` release），与 HuggingFace `Xenova/modnet` 中的 `onnx/model.onnx` 为同一文件。
- SHA-256：`07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9`
- 授权：[MODNet](https://github.com/ZHKKKe/MODNet) 代码与模型均为 Apache-2.0；[HivisionIDPhotos](https://github.com/Zeyi-Lin/HivisionIDPhotos) 为 Apache-2.0。商用请自行保留相应版权与许可声明。
- 换模型：把任意同输入输出约定（`NCHW` 图像 → `[1,1,H,W]` alpha）的 ONNX 放到 assets 并用 `new PortraitMatting(modelFile)` 加载。BiRefNet 系列精度更高（尤其发丝），但模型约 220 MB，CPU 推理明显更慢，需按设备实测取舍。

#### 已知限制

- 示例应用抠图时长边会被限制到 2048 px（降采样解码，控制内存占用），如需原分辨率输出可自行调大 `MATTE_MAX_DECODE_SIZE`。
- 整图推理、未做人脸检测裁剪：人像在画面中占比过小时边缘精度会下降（可先用 FaceDetector/RetinaFace 裁剪再抠图）。
- 单人像效果最好；多人或复杂遮挡场景不做实例分离。
- 输入按模型原生 512×512 处理再上采样，超大图（> 2000 px）的细发丝细节受模型分辨率限制。

### 3. 清晰度增强（`DetailEnhancer`）

用传统图像处理提升"相对模糊"照片的清晰度：**自适应双边降噪 + 双尺度亮度锐化 + 可选 2× 放大**，完全离线、无需额外模型。

```java
Bitmap better = DetailEnhancer.enhance(matte);              // 默认：细节 1.0、降噪 0.6、不放大
Bitmap bigger = DetailEnhancer.enhance(matte, 1.0f, 0.6f, 2); // 同时放大 2×
```

- 只在 **Y（亮度）通道**处理，再按亮度比例回写 RGB —— 不会产生彩色边缘；
- **边缘门控**：平坦区域只给 35% 力度，避免放大噪点；边缘区域满力度锐化；
- **按原图锐度自动调力**：已锐利的照片轻处理（防过曝/光晕），模糊照片加重（最多 2 倍力度）；
- **alpha 通道原样保留**，可直接作用于抠图结果（半透明发丝按 alpha 比例减弱锐化）；
- 超过 850 万像素（约 2900×2900）时自动跳过放大，避免 OOM；内存不足时也会退回不放大。

#### 性能（RK3568 实测）

| 输入 | 耗时 |
| --- | --- |
| 768×768 | 0.76 s |
| 1920×1080 | 2.5 s |
| 1920×1080 → 3840×2160（2×） | 5.7 s |

#### 效果（客观指标）

- 清晰照片：拉普拉斯方差 597 → 1226（边缘能量提升约 2 倍），过冲像素占比仅 0.48% → 0.75%，无明显光晕；
- 模拟模糊照片（5×5 均值模糊）：方差 11.4 → 18.1，与原图相比 PSNR **29.34 dB → 29.80 dB**（增强后更接近真实图像，而不是单纯放大噪声）。

#### 能力边界（重要）

- 这是**信号增强**，不是生成式超分：能强化照片里已有的结构，**不能凭空补全已经丢失的细节**。
- 真正"AI 补全细节"需要 Real-ESRGAN / GFPGAN 一类生成模型：RK3568（4×Cortex-A55）上单张 1080p 需数十秒到数分钟，模型 60~300 MB，而且会**虚构**五官细节，证件照场景不建议使用。
- 建议使用场景：轻微失焦/压缩模糊/低分辨率放大的证件照与半身照。

## 远程引用（JitPack）

`pic` 模块已配置 `maven-publish`，其他项目可以直接依赖调用，无需拷贝源码：

```gradle
// settings.gradle 或根 build.gradle 的 repositories
repositories {
    google()
    mavenCentral()                     // onnxruntime-android 来自这里
    maven { url 'https://jitpack.io' } // 发布好的 pic AAR
}

dependencies {
    implementation 'com.github.baiqingsong:pic:<tag>'   // 例如 1.0.0
}
```

发布新版本（JitPack 用 git tag 作为版本号）：

```bash
git tag 1.0.0
git push origin 1.0.0
```

首次访问 `https://jitpack.io/com/github/baiqingsong/pic/1.0.0/` 会触发构建并缓存产物，构建日志在 jitpack.io 可查。仓库需公开，或授权 JitPack 访问私有仓库。

**调用方要求**：`minSdk 28`；AGP 7+ / `compileSdk 34`；构建机 JDK 11+（仓库不再写死 `org.gradle.java.home`，本机可在 `~/.gradle/gradle.properties` 指定自己的 JDK）。

**本地先行验证**（不依赖 JitPack）：

```bash
./gradlew :pic:publishToMavenLocal
# 再用 mavenLocal() + com.github.baiqingsong:pic:1.0.0
```

发布产物 AAR 已包含：`classes.jar`、**模型 `assets/portrait_matting/modnet.onnx`**、consumer ProGuard 规则（保留 `com.dawn.pic.**` 与 `ai.onnxruntime.**`，R8 全量混淆实测可用）。

### 离线能力（已验证）

- `pic` 库和 app 都**没有声明 `INTERNET` 权限**，代码中也没有任何 socket/HTTP 调用；
- 模型随 AAR 打包，首次使用时解压到应用私有目录（`files/models/modnet_v1.onnx`），之后直接复用；
- 推理全部在本机 CPU 上完成（ONNX Runtime 本地 so），不经过云服务；
- 实测：设备开启飞行模式（无 WiFi/数据、DNS 不可用）后执行 `./gradlew :app:connectedDebugAndroidTest`，4 个用例全部通过（512 推理 3.4 s / 384 推理 2.2 s），与联网时一致。

## 测试应用（app 模块）

页面只保留抠图相关流程：

- **拍照抠图**：唤起系统相机（`ACTION_IMAGE_CAPTURE` + FileProvider 写入应用缓存），拍完自动抠图；
- **从相册选择**：选本地图片抠图；
- 预览区用棋盘格底色显示透明结果，底部显示尺寸与耗时；
- **增强清晰度**：对抠图结果做自适应降噪 + 双尺度锐化，可勾选"放大 2×"（超过 850 万像素自动跳过并提示）；
- **保存抠图 PNG**：写入系统相册 `Pictures/LibPic/`（API 29+ 走 MediaStore，**无需存储权限**；API 28 才申请写权限）。

相机照片会按 EXIF 方向自动摆正，长边超过 2048 px 时降采样解码以控制内存。

## 测试

```bash
# 仪器测试（需连接设备）
./gradlew :app:connectedDebugAndroidTest
```

`PortraitMattingTest` 校验模型加载、透明度结果与耗时；`MainActivityMattingTest` 校验“选图 → 抠图 → 可保存”的完整流程、增强清晰度按钮链路，以及相机链路（相机应用可解析 + FileProvider 可用）；`DetailEnhancerTest` 校验尺寸/alpha 保留、锐度提升（拉普拉斯方差）、模糊照片恢复度与耗时。
