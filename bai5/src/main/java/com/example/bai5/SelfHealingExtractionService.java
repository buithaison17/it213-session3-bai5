package com.example.bai5;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SelfHealingExtractionService {
    private final ChatModel chatModel;

    public BookExtract extractWithRetry(String rawText, Integer maxRetries) {
        BeanOutputConverter<BookExtract> converter = new BeanOutputConverter<>(BookExtract.class);
        List<String> errors = new ArrayList<>();
        String template = """
                VAI TRÒ:
                Bạn là AI chuyên phân tích và trích xuất thông tin
                sách từ văn bản không có cấu trúc.
                
                MỤC TIÊU:
                Trích xuất các thông tin:
                - title: tên sách
                - description: mô tả sách
                - author: tác giả
                - publishYear: năm xuất bản
                
                NGỮ CẢNH:
                Văn bản cần phân tích:
                
                {rawText}
                
                LỖI TỪ CÁC LẦN THỬ TRƯỚC:
                {errorFeedback}
                
                NHIỆM VỤ:
                1. Đọc và phân tích toàn bộ văn bản.
                2. Trích xuất title.
                3. Trích xuất description.
                4. Trích xuất author.
                5. Trích xuất publishYear.
                6. Nếu có lỗi parse ở lần trước, hãy sửa lại
                   JSON dựa trên thông báo lỗi được cung cấp.
                
                RÀNG BUỘC NGHIÊM NGẶT:
                - Chỉ sử dụng thông tin xuất hiện trong văn bản.
                - Không được tự suy đoán dữ liệu.
                - Nếu không tìm thấy thông tin, trả về null.
                - publishYear phải là số nguyên.
                - Chỉ trả về một JSON object.
                - Không trả về Markdown.
                - Không sử dụng ```json.
                - Không thêm lời giải thích.
                - Không thêm field ngoài schema.
                - JSON phải hợp lệ và có thể parse trực tiếp.
                
                ĐỊNH DẠNG ĐẦU RA:
                {formatInstructions}
                """;
        for (int i = 0; i < maxRetries; i++) {
            try {
                String errorFeedback = errors.isEmpty()
                        ? "Không có lỗi từ lần thử trước."
                        : String.join("\n", errors);

                Prompt prompt = new PromptTemplate(template)
                        .create(Map.of("rawText", rawText,
                                "formatInstructions", converter.getFormat(),
                                "errorFeedback", errorFeedback));

                String response = chatModel.call(prompt).getResult().getOutput().getText();
                return converter.convert(response);
            } catch (RuntimeException e) {
                String error = e.getMessage();
                errors.add(
                        "Attempt " + (i + 1) + ": " + error
                );
            }
        }
        return new BookExtract(
                null,
                null,
                null,
                null);
    }
}
