package com.dreamtrue.ocr.domain;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record OcrResult(
        @JsonPropertyDescription("문서의 성격을 나타내는 제목. 문서번호·일자 등 식별정보가 있으면 괄호로 병기한다. 예: 보관증 — 김경석 수집 기록물 인수·보관 증명 (1968.8.1.)")
        String title,

        @JsonPropertyDescription("원문을 레이아웃 그대로 옮긴 전사. 표는 '항목 | 항목' 파이프 형식으로 옮긴다. 요약·번역하지 말고 원문 표기를 보존한다. 특이사항은 여기에 쓰지 않는다.")
        String transcription,

        @JsonPropertyDescription("문서 종류. 예: 인쇄체 단면 시행문(통보 공문), 손글씨 낱장 메모(작업 일지), 기록물 편철 표지")
        String documentType,

        @JsonPropertyDescription("보존상태. 황변·얼룩·수침·훼손 여부와 본문 판독에 지장이 있는지. 예: 용지 전반 황변, 우측 하단 원형 얼룩, 본문 판독 가능")
        String preservationState,

        @JsonPropertyDescription("판독이 불가능한 구간. 추측해서 채우지 말고 여기에 기술한다. 없으면 정확히 '없음'")
        String illegibleParts,

        @JsonPropertyDescription("관인·서명의 유무와 형태. 없으면 정확히 '확인되지 않음'")
        String sealsAndSignatures,

        @JsonPropertyDescription("檀紀·간지 등 서기가 아닌 연호가 있으면 서기 환산 주석. 해당 없으면 null")
        String eraNote
) {}
