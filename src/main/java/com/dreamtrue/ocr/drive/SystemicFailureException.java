package com.dreamtrue.ocr.drive;

/**
 * 개별 이미지가 아니라 환경 설정의 문제. 10장 전부 동일하게 실패하므로
 * 격리하지 않고 배치를 즉시 중단시킨다.
 */
public class SystemicFailureException extends RuntimeException {
    public SystemicFailureException(String message) {
        super(message);
    }
}
