# Release v1.0.0 — NomLens (NomNa OCR) 📜✨

We are excited to announce the initial release of **NomLens (v1.0.0)** — an on-device AI-powered OCR and document scanner app for historical Vietnamese **Hán-Nôm** manuscripts.

---

## 🌟 What's New in v1.0.0

### 🚀 Core Features
- **100% On-Device & Offline OCR**: Real-time inference powered by lightweight TensorFlow Lite (TFLite) models on Android. Zero data is sent to external servers.
- **Historical Reading Order Preservation**: Automatic **Right-to-Left (RTL)** and **Top-to-Bottom** column sorting, honoring traditional East Asian & Vietnamese manuscript layouts.
- **CameraX Document Scanner**: Interactive camera viewfinder with tap-to-focus, zoom controls, torch/flash toggle, and gallery image picker.
- **Authentic Hán-Nôm Typography**: Embedded `NomNaTong-Regular.ttf` font supporting thousands of rare CJK Ideographs Extension glyphs.
- **Interactive Visual Canvas**: Tap detected bounding boxes on the document to view column crops, character sequences, and transcription details.
- **Confidence Analytics**: Per-character confidence scores and CTC greedy decoding diagnostics.
- **Local Scan History**: Save, search, inspect, and export past scans and transcripts directly from the app.

---

## 🧠 Bundled ML Models

1. **Text Column Detector (`detect_model.tflite`)**:
   - **Architecture**: Ultralytics YOLOv8s ($640 \times 640$).
   - **Quantization**: INT8 & Float32 models trained on page-level document layouts.
2. **Text Recognizer (`recognise_model.tflite`)**:
   - **Architecture**: Residual CRNN (Stem + 4-Stage Residual Backbone $\to$ Learned $1 \times 12$ Width Projection $\to$ 2-layer BiGRU $\to$ CTC Head).
   - **Vocabulary**: 7,481 classes mapped via `vocab.txt`.
3. **Dataset Source**:
   - Trained on the public [Kaggle NomNaOCR Dataset by Quang Dang](https://www.kaggle.com/datasets/quandang/nomnaocr).

---

## 📦 Assets & Artifacts

- **Build Target**: Android 8.0+ (`minSdk = 26`, `targetSdk = 35`, Java 17).
- **Source Code**: [GitHub Repository](https://github.com/quannnguyen2009/NomLens)
- **Git Tag**: [`v1.0.0`](https://github.com/quannnguyen2009/NomLens/releases/tag/v1.0.0)
- **Signed Release APK**: `app/build/outputs/apk/release/app-release.apk`
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`

