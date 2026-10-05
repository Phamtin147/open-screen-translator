# OPEN SCREEN TRANSLATOR
> **Dự án**: Android Screen Translator thuần khiết (Clean Architecture, 0% Quảng Cáo, Dịch Không Giới Hạn, Hỗ trợ On-Device & Free Cloud)  
> **Ngôn ngữ**: Kotlin (Android SDK 26 - 34)  
> **Công nghệ lõi**: Google ML Kit OCR, ML Kit Translate, MediaProjection API, WindowManager Overlay, Coroutines & Flow

---

## 1. TẠI SAO PHẢI XÂY DỰNG DỰ ÁN NÀY?

Các ứng dụng dịch màn hình thương mại (như *Tap to Translate Screen*, *Bubble Translator*, *ai-screen-translator*) hiện nay gặp các nhược điểm nghiêm trọng:
1. **Quảng cáo gây ức chế**: Bắt người dùng xem clip 30s sau vài lần bấm, chèn banner đè lên game.
2. **Khóa tính năng (Paywall/Free-tier Quota)**: Giới hạn 10-20 lần chạm/ngày; bắt mua gói tháng/năm.
3. **Mất an toàn dữ liệu**: Chụp màn hình gửi lên máy chủ bên thứ ba.
4. **App cồng kềnh**: Nhồi nhét hàng chục SDK theo dõi và quảng cáo.

**Open Screen Translator** được thiết kế lại từ đầu với triết lý:
- **Clean Code & Tối giản**: Không có bất kỳ dòng code quảng cáo hay tracking nào.
- **Dịch Không Giới Hạn**: Dịch hàng triệu câu, cày game cả ngày không bao giờ bị khóa.
- **Đa cơ chế dịch (Triple Engine)**:
  - *Google Gemini AI Mode*: Dịch bằng mô hình ngôn ngữ lớn (LLM) thông minh nhất, văn phong mượt mà, hỗ trợ cắm API Key miễn phí từ Google AI Studio (`aistudio.google.com`).
  - *On-Device Mode (ML Kit)*: Chạy trên chip điện thoại, không cần mạng, độ trễ < 100ms, bảo mật 100%.
  - *Cloud Free Mode*: Kết nối thẳng tới endpoint dịch miễn phí không giới hạn của Google Translate không cần API Key.
- **Cơ chế Chạm Thông Minh (Tap to Toggle)**:
  - Bấm vào bong bóng -> Quét và hiển thị bản dịch.
  - Bấm lại vào bong bóng (icon dấu X) -> Tắt sạch các bản dịch ngay lập tức mà không làm che màn hình game/truyện.
  - Cho phép tùy chỉnh thời gian tự tắt (0s = thủ công, hoặc 5s, 10s, 15s).

---

## 2. BẢN CHẤT CÔNG NGHỆ VÀ CƠ CHẾ HOẠT ĐỘNG (ARCHITECTURE & FLOW)

```
             ┌──────────────────────────────────────────────────┐
             │            User Bấm Vào Bong Bóng Nổi            │
             └────────────────────────┬─────────────────────────┘
                                      │
                                      ▼
                      ┌────────────────────────────────┐
                      │    FloatingBubbleService.kt    │
                      │  (Foreground Service Android)  │
                      └───────────────┬────────────────┘
                                      │
                 ┌────────────────────┴────────────────────┐
                 ▼                                         ▼
   MediaProjection API / ImageReader             Hiển thị Spinner xoay
   Chụp FrameBuffer màn hình -> Bitmap           trên Bong bóng nổi
                 │
                 ▼
          OcrManager.kt (Google ML Kit Text Recognition)
          Nhận diện từng đoạn text và lấy Tọa Độ BoundingBox: Rect(left, top, right, bottom)
                 │
                 ▼
       TranslationManager.kt (Dịch đồng thời bằng Coroutines async/awaitAll)
       Mode 1: ML Kit On-Device (Offline) 
       Mode 2: Free Google Translate HTTP API (Online, No Key)
                 │
                 ▼
          OverlayManager.kt (WindowManager + TYPE_APPLICATION_OVERLAY)
          Vẽ các thẻ TextView bán trong suốt đè chính xác lên vị trí chữ gốc
```

---

## 3. CÁC THÀNH PHẦN KỸ THUẬT QUAN TRỌNG TRONG CODEBASE

### 1. `FloatingBubbleService.kt`
- Khởi chạy dưới dạng **Foreground Service** với cờ `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` (bắt buộc trên Android 10+ và 14).
- Quản lý vòng đời của `MediaProjection`, `VirtualDisplay`, và `ImageReader`.
- Đảm bảo app không bị Android hệ điều hành tiêu diệt (kill) khi người dùng mở game nặng (RAM cao).

### 2. `OcrManager.kt`
- Sử dụng Google ML Kit Vision: `com.google.android.gms:play-services-mlkit-text-recognition`.
- Sử dụng mô hình nhận diện mảng khối chữ (`visionText.textBlocks`), trích xuất cả nội dung chữ và tọa độ `boundingBox` của từng khối thoại.
- Cơ chế **Cache Recognizer**: Tránh tạo mới instance `TextRecognizer` mỗi lần chụp nhằm triệt tiêu hiện tượng lag giật và Garbage Collection (GC) spikes.

### 3. `TranslationManager.kt`
- Quản lý cơ chế dịch kép (Dual-Engine):
  - **On-Device**: Tận dụng `com.google.mlkit:translate`. Tự động gọi `downloadModelIfNeeded()` để kéo file nơ-ron ngôn ngữ về máy lưu trữ offline.
  - **Cloud Free Endpoint**: Tự động parse chuỗi JSON từ Google Translate Engine mà không tốn chi phí API Key của người dùng.

### 4. `OverlayManager.kt`
- Tương tác trực tiếp với `WindowManager` của Android.
- Đăng ký cửa sổ nổi với cờ:
  - `TYPE_APPLICATION_OVERLAY`
  - `FLAG_NOT_FOCUSABLE` (không chiếm quyền bàn phím của app bên dưới)
  - `FLAG_LAYOUT_NO_LIMITS` (cho phép vẽ tràn toàn màn hình)
- Hỗ trợ kéo thả bong bóng (`OnTouchListener`) và hẹn giờ tự động biến mất (`autoClearSeconds`) sau khi người dùng đọc xong.

---

## 4. BỘ CÂU HỎI VẤN ĐÁP / PHỎNG VẤN MÔN HỌC (PRM - ANDROID PROGRAMMING)

**Q1: Tại sao MediaProjection bắt buộc phải đi kèm với Foreground Service trên Android đời mới?**
> *Trả lời*: Vì lý do bảo mật quyền riêng tư của người dùng. Bắt đầu từ Android 10 (Q) và khắt khe hơn trên Android 14 (U), việc chụp màn hình người dùng ở phạm vi hệ thống (ngoài ứng dụng) bị coi là tác vụ nhạy cảm. Hệ điều hành yêu cầu phải có một Foreground Service kèm theo Notification liên tục trên thanh thông báo (`NotificationCompat.Builder.setOngoing(true)`), để người dùng luôn biết rằng màn hình đang có khả năng bị ứng dụng ghi lại.

**Q2: Tại sao ứng dụng lại dùng WindowManager để vẽ Overlay thay vì mở một Activity trong suốt (Transparent Activity)?**
> *Trả lời*: Nếu mở một Transparent Activity, Activity đó sẽ nhảy lên đỉnh của Back Stack, làm Activity/Game bên dưới rơi vào trạng thái `onPause()` và mất quyền Focus (người dùng sẽ bị khựng game hoặc dừng video đang xem). Khi dùng `WindowManager.addView()` với cờ `TYPE_APPLICATION_OVERLAY` và `FLAG_NOT_FOCUSABLE`, giao diện dịch được hiển thị dưới dạng một Sub-Window độc lập, hoàn toàn không làm gián đoạn luồng xử lý và thao tác cảm ứng của game bên dưới.

**Q3: Kỹ thuật xử lý bất đồng bộ trong TranslationManager có gì đặc biệt?**
> *Trả lời*: Khi OCR trả về một danh sách gồm nhiều khối chữ (`ocrBlocks`), nếu dịch tuần tự (sequential) thì thời gian chờ sẽ là tổng thời gian của từng khối (rất chậm). Ứng dụng áp dụng Kotlin Coroutines với `ocrBlocks.map { block -> async { ... } }.awaitAll()`. Toàn bộ các khối văn bản được gửi đi dịch song song đồng thời trên `Dispatchers.IO`, giảm độ trễ từ vài giây xuống còn vài trăm mili-giây.

---

## 5. HƯỚNG DẪN CÀI ĐẶT & CHẠY ỨNG DỤNG

### Cài đặt lên máy thật qua ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
