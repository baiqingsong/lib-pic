# lib-pic

图片的相关处理

- `pic` 模块：可独立发布的图片处理库（`PngAnalyzer` + `PortraitMatting`）。
- `app` 模块：验证用的示例应用。

## 功能

### 1. 透明镂空模板解析（`PngAnalyzer`）

输入含透明镂空的 RGBA PNG，识别每个透明区域并输出坐标/尺寸 JSON，支持切图保存、照片合成预览。

### 2. 离线人像抠图（`PortraitMatting`）

用 **MODNet**（AAAI 2022, trimap-free 人像软分割）ONNX 模型 + **ONNX Runtime** CPU 推理，在设备本地抠出人像，输出透明背景 PNG。

- **完全离线**：不联网、不依赖 Face++ 等第三方人体抠像接口，无调用费用与授权费。
- **模型随 APK 打包**：`pic/src/main/assets/modnet.onnx`（约 25 MB），首次使用时解压到应用私有目录后复用。
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

## 测试

```bash
# 仪器测试（需连接设备）
./gradlew :app:connectedDebugAndroidTest
```

`PortraitMattingTest` 校验模型加载、透明度结果与耗时；`MainActivityMattingTest` 校验示例应用“选图 → 抠图 → 可保存”的完整流程。
