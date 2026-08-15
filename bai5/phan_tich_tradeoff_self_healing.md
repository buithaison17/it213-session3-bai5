# Phân tích Trade-off của Self-Healing / Error Feedback Loop

## 1. Tổng quan

Trong hệ thống ETL sử dụng LLM, `BeanOutputConverter.convert()` chuyển JSON do LLM trả về thành Java object. Nếu JSON sai định dạng, quá trình parse có thể phát sinh exception.

Self-Healing xử lý bằng cách lấy `exception.getMessage()`, đưa lỗi trở lại prompt và yêu cầu LLM sửa kết quả:

```text
Raw Text
   |
   v
ChatModel
   |
   v
JSON Output
   |
   v
BeanOutputConverter.convert()
   |
   +---- SUCCESS ---> Java Record
   |
   +---- ERROR
          |
          v
   exception.getMessage()
          |
          v
     Error Feedback
          |
          v
       ChatModel
          |
          v
        Retry
          |
          +---- SUCCESS ---> Java Record
          |
          +---- ERROR ---> Retry tiếp / Fallback
```

---

## 2. Trade-off về Latency

### Không có Self-Healing

Một request thành công ngay lần đầu:

```text
Request
  |
  v
ChatModel
  |
  v
JSON
  |
  v
convert()
  |
  v
Success
```

Nếu LLM mất khoảng 5 giây thì latency khoảng 5 giây.

### Có Self-Healing

Nếu lần đầu JSON lỗi:

```text
ChatModel #1
     |
     X JSON lỗi
     |
     v
Error Feedback
     |
     v
ChatModel #2
     |
     v
JSON đúng
```

Nếu mỗi lần gọi mất khoảng 5 giây thì latency có thể tăng lên khoảng 10 giây.

Nếu có nhiều retry:

```text
Initial call
    ↓
Retry 1
    ↓
Retry 2
    ↓
Retry 3
```

thời gian phản hồi tiếp tục tăng.

### Đánh giá

**Ưu điểm:**

- Tăng khả năng hoàn thành request thay vì trả lỗi ngay.
- Có thể tự phục hồi các lỗi JSON đơn giản.

**Nhược điểm:**

- Mỗi retry làm tăng thời gian phản hồi.
- Không phù hợp nếu endpoint yêu cầu latency cực thấp.

**Khuyến nghị:** giới hạn retry ở khoảng 2–3 lần.

---

## 3. Trade-off về chi phí Token

Mỗi lần retry có thể phải gửi lại:

```text
Prompt
+
Raw Text
+
Format Instructions
+
Error Feedback
```

Ví dụ:

```text
Raw CV                 : 1,000 tokens
Prompt + instructions  :   500 tokens
Error feedback         :   100 tokens
```

Một lần gọi khoảng 1.600 tokens.

```text
1 lần gọi → 1.600 tokens
2 lần gọi → 3.200 tokens
3 lần gọi → 4.800 tokens
```

Với hàng trăm hoặc hàng nghìn CV mỗi ngày, chi phí có thể tăng đáng kể.

### Ưu điểm

- Có thể cứu được request bị lỗi thay vì phải xử lý thủ công.
- Giảm số trường hợp phải gửi lại toàn bộ workflow từ đầu.

### Nhược điểm

- Retry đồng nghĩa với thêm request đến LLM.
- CV càng dài thì chi phí retry càng cao.
- Error feedback được tích lũy quá nhiều cũng làm prompt dài.

### Cách tối ưu

1. Giới hạn số retry.
2. Chỉ gửi error message cần thiết.
3. Không tích lũy lỗi vô hạn.
4. Giữ prompt ngắn gọn.
5. Ưu tiên model có khả năng Structured Output/JSON tốt.

---

## 4. Trade-off về độ tin cậy

### Ưu điểm

Không có retry:

```text
LLM
 ↓
JSON lỗi
 ↓
Exception
 ↓
HTTP 500
```

Có retry:

```text
LLM
 ↓
JSON lỗi
 ↓
Jackson error
 ↓
Feedback
 ↓
LLM retry
 ↓
JSON đúng
 ↓
Success
```

Các lỗi như:

```text
Missing closing brace
Missing quote
Sai kiểu dữ liệu
```

có khả năng được model tự sửa.

Điều này giúp tăng resilience của hệ thống.

### Nhược điểm

Self-Healing không đảm bảo thành công 100%.

Ví dụ:

```text
Attempt 1 → JSON sai
Attempt 2 → JSON sai
Attempt 3 → JSON sai
```

Nguyên nhân có thể là:

- Prompt chưa đủ rõ.
- Model không tuân thủ format.
- Hallucination.
- Schema phức tạp.
- Provider/model gặp lỗi.
- Lỗi là semantic chứ không phải syntax.

Vì vậy vẫn cần fallback:

```java
return new BookExtract(
    null,
    null,
    null,
    null
);
```

---

## 5. JSON hợp lệ chưa chắc dữ liệu đúng

Self-Healing chủ yếu xử lý lỗi format/parse.

Ví dụ:

```json
{
  "title": "Clean Code",
  "description": "A book about software.",
  "author": "Robert C. Martin",
  "publishYear": -500
}
```

JSON trên có thể hợp lệ về syntax nhưng dữ liệu nghiệp vụ có thể sai.

Vì vậy pipeline thực tế nên là:

```text
LLM
 |
 v
JSON
 |
 v
BeanOutputConverter
 |
 v
Java Record
 |
 v
Business Validation
 |
 +---- Valid ------> Save DB
 |
 +---- Invalid ----> Error Feedback / Retry
```

Như vậy Self-Healing có thể kết hợp với validation để xử lý cả lỗi parse và một phần lỗi nghiệp vụ.

---

## 6. Bảng tổng hợp Trade-off

| Tiêu chí | Không Self-Healing | Có Self-Healing |
|---|---|---|
| Latency | Thấp | Cao hơn khi retry |
| Token | Thấp | Cao hơn |
| Chi phí API | Thấp | Cao hơn |
| Khả năng phục hồi | Thấp | Cao hơn |
| Xử lý JSON lỗi | Dễ thất bại | Có thể tự sửa |
| Độ phức tạp code | Thấp | Cao hơn |
| Trải nghiệm hệ thống | Có thể HTTP 500 | Có thể tự phục hồi |
| Fallback | Nên có | Bắt buộc nên có |
| Đảm bảo dữ liệu đúng | Không | Không |

---

## 7. Khuyến nghị

Không nên retry vô hạn.

Ví dụ:

```java
maxRetries = 2;
```

Luồng:

```text
Initial attempt
      |
      v
   Error?
      |
      +---- No ----> Success
      |
     Yes
      |
      v
   Retry #1
      |
      +---- Success
      |
     Error
      |
      v
   Retry #2
      |
      +---- Success
      |
     Error
      |
      v
   Fallback
```

Đây là sự cân bằng giữa:

- Reliability
- Latency
- Token cost

---

## 8. Kết luận

Self-Healing / Error Feedback Loop là pattern hữu ích khi xây dựng ETL sử dụng LLM.

Cốt lõi:

```text
LLM
 ↓
Parse
 ↓
Error
 ↓
exception.getMessage()
 ↓
Error Feedback
 ↓
LLM Retry
 ↓
Parse
 ↓
Success / Fallback
```

### Ưu điểm

- Tăng khả năng tự phục hồi.
- Giảm lỗi JSON làm hệ thống trả HTTP 500.
- Có thể sửa các lỗi parse đơn giản.
- Tăng độ ổn định của pipeline AI.

### Nhược điểm

- Tăng latency.
- Tăng token usage.
- Tăng chi phí API.
- Code phức tạp hơn.
- Không đảm bảo model luôn sửa được lỗi.

### Kết luận kiến trúc

Giải pháp production nên kết hợp:

**Retry có giới hạn + Error Feedback + Validation + Fallback + Logging/Monitoring**

Self-Healing nên được xem là cơ chế **tăng khả năng phục hồi**, không phải cơ chế đảm bảo LLM luôn trả dữ liệu chính xác.
