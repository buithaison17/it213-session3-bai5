package com.example.bai5;

public record Request(
        String rawText,
        Integer maxRetries
) {
}
