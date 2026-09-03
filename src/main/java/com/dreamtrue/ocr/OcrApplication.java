package com.dreamtrue.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Slf4j
@SpringBootApplication
public class OcrApplication {
    public static void main(String[] args) {
        log.info("archive-ocr 시작");
        SpringApplication.run(OcrApplication.class, args);
    }
}
