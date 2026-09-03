package com.dreamtrue.ocr;

import com.dreamtrue.ocr.config.OcrProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OcrProperties.class)
public class OcrApplication {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(OcrApplication.class, args)));
    }
}
