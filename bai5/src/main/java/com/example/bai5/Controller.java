package com.example.bai5;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class Controller {
    private final SelfHealingExtractionService selfHealingExtractionService;

    @PostMapping
    public BookExtract bookExtract(@RequestBody Request request) {
        return selfHealingExtractionService.extractWithRetry(request.rawText(), request.maxRetries());
    }
}
