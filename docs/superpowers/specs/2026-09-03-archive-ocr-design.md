# 기록물 정리 OCR 솔루션 — 설계 문서

- 작성일: 2026-09-03
- 상태: 승인됨 (구현 계획 작성 대기)
- 언어/런타임: Java 21, Spring Boot (Gradle Wrapper 동봉)

## 1. 배경과 목적

드림트루 업무수행능력 평가 과제. 기록물 사진을 Claude로 OCR 하여 결과와 원본 사진을
Google Sheet에 자동 기록하는 배치 프로그램을 만든다.

과제 원문에서 도출한 필수 요구사항:

| # | 요구사항 | 출처 |
|---|---|---|
| R1 | 입력은 특정 디렉토리에 저장된 기록물 사진 | "입력은 기록물의 사진으로 함" |
| R2 | **Claude를 활용**하여 인식(OCR) | "Claude를 활용하여 인식(OCR)" — 모델 교체 불가 조건 |
| R3 | 인식 결과와 **원본 사진**을 Google Sheet에 자동 추가 | "인식된 결과와 원본 사진을 Google Sheet에 자동으로 추가" |
| R4 | 양식 내 **모든 열**이 자동으로 채워져야 함 | "각 열의 모든 내용이 자동으로 채워져야 합니다" |
| R5 | 파일번호는 1 고정, 세부번호는 순차 | "파일번호는 1 고정, 세부번호는 순차 기록" |
| R6 | 프로그램 + 실행방법(README.md) 제출 | "프로그램과 실행방법(Readme.md)을 같이 보내야" |

## 2. 입력 실측

`입력.zip` 전개 결과 JPEG 10장.

| 파일 | 해상도 | JPEG | base64 | 비주얼 토큰 |
|---|---|---|---|---|
| img_01 | 1000×1400 | 122 KB | 162 KB | 1800 |
| img_02 | 1100×1400 | 110 KB | 146 KB | 2000 |
| img_03 | 1004×1292 | 84 KB | 112 KB | 1692 |
| img_04 | 1036×1376 | 73 KB | 97 KB | 1850 |
| img_05 | 1200×850 | 68 KB | 90 KB | 1333 |
| img_06 | 818×1212 | 46 KB | 61 KB | 1320 |
| img_07 | 984×1326 | 76 KB | 101 KB | 1728 |
| img_08 | 1150×1350 | 87 KB | 116 KB | 2058 |
| img_09 | 1020×1314 | 76 KB | 101 KB | 1739 |
| img_10 | 980×1370 | 82 KB | 110 KB | 1715 |
| | | | **합계** | **17,235** |

비주얼 토큰 = `⌈가로/28⌉ × ⌈세로/28⌉` (28×28 픽셀 패치 단위).

## 3. 산출물 형식 (평가양식 xlsx 분석)

첨부된 `기록물_OCR_평가결과_예시.xlsx`를 분해하여 확인한 실제 구조.

### 3.1 `목록` 시트

| A | B | C | D |
|---|---|---|---|
| 폴더번호 | 파일번호 | 세부번호 | 제목 |
| 1 | 1 | 1 | 문화공보부 발족에 따른 소속기관 이관 통보(문관 제1968-234호, 1968.7.25) |
| 1 | 1 | 2 | 수집자료 정리 목록표 |
| … | | | (총 10행) |

`제목` 셀은 해당 상세 시트로 이동하는 하이퍼링크.

### 3.2 상세 시트 `1-1` ~ `1-10` (시트명 = 파일번호-세부번호)

```
A1  ◀ 목록으로            (목록 시트로 돌아가는 하이퍼링크)
A3  파일번호     B3  1
A4  세부번호     B4  n
A5  제목        B5  <제목>
A6  분석(내용)   B6  <전사 + 특이사항>
A7  사진        B7  <이미지>
```

`사진` 칸에 문자열 `(사진 없음)`이 들어간 시트가 예시에 존재한다 → 사진 칸에 텍스트가
오는 경우를 양식이 이미 허용한다.

### 3.3 `분석(내용)` 칸에서 역산한 출력 규칙

예시 10건의 정답을 분석하여 도출:

| 규칙 | 근거 (예시 원문) |
|---|---|
| 원문 레이아웃 보존 전사 (요약·번역 금지) | 전 건 |
| 표는 파이프 구분 | `연번 \| 자료명 \| 수량 \| 생산연도 \| 비고` (1-2), 물품수불대장 (1-8) |
| 한자 원문 유지 + 독음 병기 | `記錄物目錄(기록물 목록)`, `二百二十四(224) 묶음` |
| 연호는 서기 환산 주석 | `檀紀 4296年은 서기 1963年에 해당함(원문에는 서기 표기 없음)` |
| 레이아웃 특성은 대괄호로 명시 | `[표지 중앙 세로쓰기 · 사각 테두리 안]` |
| 말미에 특이사항 블록 | 문서 종류, 보존상태, 판독 불가부, 관인·서명 유무 |
| 제목에 식별정보 괄호 병기 | `보관증 — 김경석 수집 기록물 인수·보관 증명 (1968.8.1.)` |

**예시의 특이사항 표기는 일관되지 않다** (`※ 특이사항:`, `※ 문서 종류:`, `[특이사항]`
세 가지가 혼재). 본 설계는 이를 필드로 분리하여 받고 프로그램이 일정한 순서로 조립한다
(§5.2 참조). 결과적으로 예시보다 일관된 산출물이 된다.

## 4. 아키텍처

```
                  input/img_01.jpg  (로컬 원본)
                          │
          ┌───────────────┴───────────────┐
          │                               │
    [갈래 A] base64 인코딩            [갈래 B] 원본 바이트 그대로
          │                               │
    Claude Messages API             Google Drive 업로드
          │                          → 링크 공유 권한
    구조화 출력 (OcrResult)            → thumbnail URL
          │                               │
    output/raw/*.json (캐시)               │
          └───────────────┬───────────────┘
                          │
                  Google Sheets API
              목록 시트 + 상세 시트 10개
```

핵심: **Drive는 OCR 경로에 없다.** Claude에는 로컬 원본이 base64로 직접 전달되고,
Drive는 `=IMAGE()` 수식이 요구하는 이미지 호스팅 역할만 한다. 두 갈래는 독립적이므로
한쪽 실패가 다른 쪽 결과를 버리지 않는다.

Drive를 경유해 URL을 Claude에 넘기지 않는 이유:
1. Drive 썸네일은 축소·재압축된 사본이라 판독률이 떨어진다 (원본은 어차피 무축소 처리됨)
2. 업로드 실패 시 OCR도 불가능해지는 결합이 생긴다
3. 썸네일 생성 지연을 OCR 시작 전에 기다려야 한다

### 4.1 패키지 구조

```
src/main/java/com/dreamtrue/ocr/
├── OcrApplication.java
├── runner/OcrBatchRunner.java          CommandLineRunner
├── config/OcrProperties.java           @ConfigurationProperties("ocr")
├── image/ImageScanner.java             디렉토리 스캔·정렬·채번·MIME 판별
├── image/SourceImage.java              record(path, mediaType, width, height)
├── claude/ClaudeOcrClient.java         Anthropic Messages API 호출
├── claude/OcrPrompt.java               시스템/사용자 프롬프트
├── claude/OcrResult.java               구조화 출력 스키마 (record)
├── cache/OcrResultStore.java           output/raw/*.json 쓰기·읽기
├── domain/Outcome.java                 sealed interface — 성공값 또는 실패사유
├── domain/ArchiveRecord.java           시트 1건 (번호 + 두 개의 Outcome)
├── domain/RecordAssembler.java         §6.1 실패 조합 4가지 → 셀 값 유도
├── domain/AnalysisComposer.java        OcrResult → 분석(내용) 문자열 조립
├── drive/DriveImageUploader.java       업로드·권한·썸네일 URL 검증
├── sheets/SheetsWriter.java            시트 생성·값 기록·서식
├── sheets/SheetLayout.java             셀 좌표·수식 빌더
└── report/BatchReport.java             성공/실패 요약, 종료 코드
```

파일당 200~400줄을 목표로 하며 800줄을 넘기지 않는다.

## 5. 컴포넌트 설계

### 5.1 ImageScanner — 채번

- 지원 확장자: jpg, jpeg, png, gif, webp (Claude 지원 포맷)
- MIME 타입은 **확장자가 아니라 매직 바이트**로 판별. 확장자와 실제 포맷이 다르면 API가
  거부하는데, 확장자만 신뢰하면 원인 파악이 어려운 오류가 된다.
- 파일명 오름차순 정렬 후 세부번호 1..N을 **OCR 이전에 확정**한다.
  성공한 것만 세어 채번하면 실패 시 번호가 밀리고, 재실행 시 번호가 달라져 이미 검토한
  시트와 대조가 불가능해진다.
- 폴더번호·파일번호는 설정값 (기본 1, 1) — R5.

### 5.2 ClaudeOcrClient — OCR

**모델:** 기본 `claude-opus-5`. `ocr.claude.model` 및 `--model`로 전환 가능.

근거: Opus 5는 고해상도 티어(장변 2576px / 4784 비주얼 토큰)라 §2의 이미지 10장이
전부 무축소로 처리된다. 표준 티어인 Haiku 4.5에서는 10장 중 8장이 상한(1568토큰)을
넘어 강제 축소되며, 세로쓰기 한자·손글씨·훼손 문서 판독이 과제의 핵심 난이도이므로
축소 손실을 허용하지 않는다. 전량 1회 실비는 약 $0.54.

**요청 구성:**
- 이미지 1장당 요청 1건 (멀티턴 아님)
- content 블록 순서는 `[image, text]` — 공식 권장인 image-then-text
- 이미지 소스는 `base64`. Files API는 동일 이미지를 여러 요청에 재사용할 때 이득이나
  본 케이스는 단발 호출이므로 왕복만 늘어난다.
- 리사이즈·재인코딩 없음. 장변 2576px 초과 또는 base64 10MB 초과 시 **축소하지 않고
  명확한 메시지로 실패**시킨다. 축소 구현은 현 입력에서 한 번도 실행되지 않을 경로인데
  ImageIO 읽기·스케일링·재인코딩이 통째로 따라붙어 비용이 맞지 않는다. 검증만 남긴다.
- 적응형 사고(adaptive thinking) 사용, `output_config.effort`는 기본 `high`
- **`temperature`/`top_p`/`top_k`는 사용 불가** — Opus 5 계열에서 제거되어 400 응답.
  재현성은 프롬프트 제약으로 확보한다.

**출력:** Java SDK의 구조화 출력(`.outputConfig(OcrResult.class)`)을 사용한다. POJO에서
JSON 스키마가 자동 생성되고 타입이 붙은 객체가 반환되므로 파싱 코드가 없다.

```
record OcrResult(
    String title,               // 문서명 + 괄호 식별정보
    String transcription,       // 원문 레이아웃 보존 전사 (본문만)
    String documentType,        // 인쇄체 시행문 / 손글씨 낱장 메모 / 편철 표지 …
    String preservationState,   // 황변·얼룩·수침 등, 판독 지장 여부
    String illegibleParts,      // 판독 불가 구간, 없으면 "없음"
    String sealsAndSignatures,  // 관인·서명 유무와 형태, 없으면 "확인되지 않음"
    String eraNote              // 檀紀·간지 등 서기 환산 주석, 해당 없으면 null
) {}
```

각 필드에 `@JsonPropertyDescription`으로 지시문을 부여하여 프롬프트 일부를 스키마로
이전한다.

**프롬프트 금지 규칙:** 추측 금지(안 보이는 글자를 채우지 말 것), 요약 금지, 현대어
번역 금지. 판독 불가 구간은 창작하지 말고 `illegibleParts`로 넘긴다.

### 5.3 AnalysisComposer — 분석(내용) 조립

`OcrResult`의 필드를 정해진 순서로 결합하여 최종 셀 문자열을 만든다.

```
<transcription>

※ 특이사항: 문서 종류는 <documentType>. 보존상태는 <preservationState>.
  관인·서명: <sealsAndSignatures>. 판독 불가 부분: <illegibleParts>.
  [<eraNote>가 있으면] <eraNote>
```

모델이 특이사항 블록을 누락할 수 없고, 10건이 동일한 서술 순서를 갖는다. 조립 규칙은
순수 함수이므로 단위테스트 대상이 된다.

### 5.4 OcrResultStore — 결과 저장과 재사용

이미지 1장당 `output/raw/<파일명>.json` 1개를 저장한다.

```json
{
  "sourceFile": "img_03.jpg",
  "model": "claude-opus-5",
  "ocredAt": "2026-09-03T11:42:07+09:00",
  "usage": { "inputTokens": 3181, "outputTokens": 1204 },
  "result": { "title": "…", "transcription": "…", "…": "…" }
}
```

**쓰기는 항상, 읽기는 `--retry-failed`일 때만.**

| | 저장된 결과가 있으면 | 없으면 |
|---|---|---|
| 기본 실행 | 무시하고 API 호출 | API 호출 |
| `--retry-failed` | 재사용 | API 호출 ← 실패했던 장만 |

쓰기는 어느 모드에서나 항상 수행한다. `OcrResult` POJO와 Jackson이 이미 있어 비용이
거의 없다.

자동 재사용을 하지 않으므로 **캐시 무효화 판정이 필요 없다.** 기본 실행이 항상 새 결과를
가져오기 때문에 이미지 교체·프롬프트 수정·모델 변경 어느 경우에도 낡은 결과가 섞일 수
없다. sha256 비교, promptVersion 비교, model 비교, 무효화 로그, `--no-cache` 플래그가
모두 불필요해진다.

낡은 결과를 쓰게 되는 경우는 `--retry-failed`를 직접 지정했을 때뿐이며, 이는 "디스크에 있는
것을 사용하라"는 명시적 요청이므로 예상 밖의 동작이 아니다.

`--skip-ocr`(저장된 결과가 없으면 API 를 호출하지 않고 실패시키는 모드)은 두지 않는다.
저장된 결과가 모두 있는 정상 상황에서 `--retry-failed` 와 동작이 같고, 다른 경우는
저장 파일이 비어 있을 때뿐인데 그때는 시트가 실패 표시로 덮어써져 손해만 크다.
API 키가 없으면 Anthropic 클라이언트 빈 생성 단계에서 실패하므로 "키 없이 시트만
다시 만든다"는 용도로도 쓸 수 없다.

**용처 (둘 다 `--retry-failed` 사용):**

1. **시트 서식 조정** — OCR 결과는 불변인데 열 너비·행 높이·이미지 크기를 여러 번 고친다.
   매 회차마다 Claude를 10번 호출할 이유가 없다
2. **계통 실패 복구** — Drive 권한 오류로 중단된 뒤 권한을 고치고 재실행할 때, 이미 성공한
   OCR을 다시 수행하지 않는다
3. **부분 실패 재시도** (`--retry-failed`) — 실패한 장만 다시 처리한다. 별도의 실패 상태
   저장소가 필요 없다: OCR 실패는 `output/raw/*.json`의 부재가, 업로드 실패는 Drive 폴더
   내 파일의 부재가 곧 표식이며, 업로더가 이미 같은 이름을 재사용하도록 되어 있어
   성공한 장은 다시 올라가지 않는다

부수 효과로 저장된 JSON 자체가 검수 자산이 된다. 시트에는 조립된 최종 문자열만 보이지만
이 파일에는 필드별로 분리된 원본이 남는다.

Anthropic의 서버 측 prompt caching은 사용하지 않는다. 순차 실행이므로 시스템 프롬프트
프리픽스는 2번째 호출부터 잘 적중하겠지만, 절약액이 약 $0.06 수준이라 breakpoint 배치와
프리픽스 안정성 관리 비용이 맞지 않는다.

### 5.4.1 ArchiveRecord와 RecordAssembler

두 갈래의 결과를 `Outcome<T>`(성공값 또는 실패사유)로 감싸 한 레코드에 담는다.

```
sealed interface Outcome<T> permits Ok, Failed {}

record ArchiveRecord(
    int folderNumber, int fileNumber, int detailNumber,
    Path source,
    Outcome<OcrResult> ocr,
    Outcome<String>    photoUrl
) {}
```

`RecordAssembler`는 이 레코드에서 제목·분석(내용)·사진 세 셀의 최종 문자열을 유도한다.
§6.1의 실패 조합 4가지가 이 한 곳의 switch 패턴 매칭으로 표현되므로, 실패 처리 규칙이
여러 컴포넌트에 흩어지지 않는다. 외부 I/O가 없는 순수 함수여서 네 조합을 그대로
단위테스트한다.

### 5.5 DriveImageUploader

1. 대상 폴더에 동일 파일명이 있으면 재사용 (멱등)
2. 원본 바이트 그대로 업로드 (재인코딩 없음)
3. `permissions.create(type=anyone, role=reader)` — `=IMAGE()`는 Sheets 렌더러가
   **익명으로** 이미지를 가져가므로 링크 공유가 없으면 셀이 깨진다
4. 썸네일 URL(`thumbnail?id=…&sz=w1000`)이 유효해질 때까지 짧은 백오프로 확인.
   업로드 직후에는 썸네일이 아직 생성되지 않아 일시적으로 404가 난다

### 5.6 SheetsWriter

**2단계로 나뉜다.** 목록 시트의 하이퍼링크는 `=HYPERLINK("#gid=<시트ID>", …)` 형태인데
gid는 시트를 생성해야 알 수 있다. 단일 batchUpdate로는 불가능하다.

1. `addSheet` × 11 → 응답에서 각 시트의 gid 수집
2. 수집한 gid로 수식을 조립하여 값·서식 기록

- 값 기록은 `USER_ENTERED` (수식으로 해석되어야 함)
- 사진 셀: `=IMAGE(url, 4, height, width)` — mode 4(사용자 지정 크기)를 써서 세로 문서와
  가로 문서(img_05는 1200×850)가 섞여도 행 높이가 일정하게 유지되도록 한다
- 서식: 열 너비, 분석 셀 줄바꿈, 사진 행 높이, 헤더 강조
- **실패한 레코드는 목록 시트에서 행에 옅은 붉은 배경을 깐다.** 목록만 보면 제목이
  정상이라 사진이 빠진 것을 알 수 없기 때문이다. 평가양식이 4열로 고정이므로 열을
  늘리지 않고 배경색으로만 표시한다 — 열 구성과 값은 그대로 유지된다.
- 재실행 시 기존 목록/상세 시트를 삭제 후 재생성하여 멱등하게 동작
- 모든 이미지 처리가 끝난 뒤 마지막에 1회 기록한다. 반쯤 쓰이다 만 시트가 생기지 않는다

## 6. 에러 처리

### 6.1 실패 조합별 기록 내용

실패한 장도 행과 시트를 그대로 차지한다. 10장이면 어떤 경우에도 목록 10행, 상세 시트 10개.

| A(OCR) | B(업로드) | 기록 내용 |
|---|---|---|
| 성공 | 성공 | 정상 |
| 성공 | 실패 | 제목·분석 정상. 사진 셀에 `[사진 업로드 실패] <사유> — 원본: input/img_03.jpg` |
| 실패 | 성공 | 사진 정상 삽입. 제목은 파일명으로 대체, 분석 셀에 `[OCR 실패] <사유>` |
| 실패 | 실패 | 행 유지, 두 셀 모두 실패 표시 |

실패 문구에 로컬 원본 경로를 병기하여 수동 복구 대상을 즉시 식별할 수 있게 한다.

### 6.2 개별 실패와 계통 실패의 구분

**개별 실패** (네트워크 순단, 일시적 5xx, 썸네일 지연) — 지수 백오프 + jitter로 재시도
후 격리하고 계속 진행.

**계통 실패** (403·404·권한 계열: 폴더 ID 오류, 서비스 계정 권한 없음, 조직 정책의 링크
공유 차단) — 특정 장의 문제가 아니라 환경 설정 문제이므로 10장 전부 동일하게 실패한다.
개별 실패처럼 다루면 같은 403을 재시도까지 곱해 30회 반복한 뒤에야 결론이 나온다.

→ **첫 발생 시점에 즉시 중단**하고 수정 대상을 지목한다:

```
[중단] Drive 업로드 권한 오류 (403 insufficientPermissions)
  폴더 ID : 1AbC...xYz
  서비스 계정 : dreamtrue-ocr@<project>.iam.gserviceaccount.com
  확인 : 해당 폴더를 위 계정에 '편집자'로 공유했는지 확인하세요.
         조직 정책이 링크 공유를 차단하는 경우 =IMAGE() 렌더링이 불가능합니다.
  OCR 결과는 output/raw/ 에 보존되었습니다. 권한 수정 후 --retry-failed 로 재실행하세요.
```

### 6.3 재시도

- Anthropic 429·5xx: **SDK 에 위임**한다. Anthropic Java SDK 가 재시도하며 `Retry-After`
  헤더를 존중하므로, 애플리케이션에 백오프 루프를 하나 더 두면 호출 횟수가 곱해져
  (3×3=9회) 레이트리밋을 악화시킨다. `maxRetries` 만 명시한다.
- 그 밖의 실패(400, 구조화 출력 없음 등)는 재시도하지 않고 그 장만 격리한다. 대부분
  같은 요청을 다시 보내도 같은 결과이고, 재시도 수단은 `--retry-failed` 로 이미 있다.
- 구조화 출력 스키마 위반: 1회 재시도
- Drive/Sheets 일시적 5xx: 지수 백오프

### 6.4 종료

```
처리 완료: 10건 중 9건 성공

  실패 1건
    img_03.jpg  세부번호 3  사진 업로드 실패 (503 backendError, 3회 재시도 후 포기)

  실패한 항목만 다시 시도:
    ./gradlew bootRun --args='--retry-failed'

  시트: https://docs.google.com/spreadsheets/d/…
```

종료 코드: `0` 전량 성공 / `1` 부분 실패 / `2` 계통 실패.

### 6.5 실행 순서 — 순차

**병렬 처리를 하지 않는다.** 이미지를 파일명 순으로 하나씩, 각 이미지 안에서도
OCR → 업로드 순으로 처리한다.

이미지 10장 기준 동시 3건의 이득은 실행 시간 5~10분이 2~4분이 되는 정도이고, 캐시가
있어 OCR을 다시 도는 일 자체가 드물다. 반면 세마포어·실행기 설정, 스레드 안전한 결과
수집, 순서 보장, 스레드 간 에러 집계가 붙는다. 특히 **로그가 뒤섞여** 프롬프트를
고쳐가며 결과를 검토하는 작업(이 과제 작업량의 대부분)이 불편해진다. 레이트리밋과
재시도가 얽히는 문제도 순차에서는 발생하지 않는다.

다만 **이미지 1장 처리는 자기완결적인 함수로 유지**하여 공유 가변 상태를 두지 않는다.
나중에 병렬화가 필요해지면 반복 부분만 바꾸면 되도록 모양을 남겨둔다.

## 7. 설정

```yaml
ocr:
  input-dir: ./input
  output-dir: ./output
  folder-number: 1
  file-number: 1
  claude:
    model: claude-opus-5
    effort: high
    max-tokens: 16000
    api-key: ""          # 비우면 ANTHROPIC_API_KEY 환경변수 사용
  google:
    oauth-client-path: ./credentials/oauth-client.json
    token-store-path: ./credentials/tokens
    spreadsheet-id: <필수>
    drive-folder-id: <필수>
```

- 키·ID 는 저장소 루트의 `application-local.yml`(gitignore 대상)에 넣는다.
  `application.yml` 의 `spring.config.import: optional:file:./application-local.yml`
  이 이를 병합한다. 파일이 없으면 무시되므로 환경변수만으로도 실행된다 —
  자격증명이 커밋되는 사고를 구조적으로 막으면서 두 방식을 모두 지원한다.
- API 키는 `ocr.claude.api-key`, 비어 있으면 `ANTHROPIC_API_KEY` 환경변수
- Google 인증은 **사용자 계정 OAuth(데스크톱 앱)**. 서비스 계정은 개인 Drive에 파일을
  소유할 수 없어 업로드가 `403 storageQuotaExceeded` 로 실패한다(공유 드라이브가 있는
  Workspace 환경 전용). 채점자 대부분이 개인 Google 계정이므로 OAuth 를 쓴다.
  부수 효과로 스프레드시트·폴더를 별도 공유할 필요가 없어져 설정 단계가 줄어든다.
  최초 1회 브라우저 동의 후 토큰은 `credentials/tokens/` 에 캐시된다.
- `credentials/`, `output/`은 `.gitignore` 처리, `application-example.yml`만 동봉

### 7.1 CLI 플래그

| 플래그 | 기본값 | 설명 |
|---|---|---|
| `--input` | `./input` | 입력 디렉토리 |
| `--output` | `./output` | 출력 디렉토리 (캐시·리포트) |
| `--model` | `claude-opus-5` | 모델 전환 |
| `--retry-failed` | off | 저장된 결과는 재사용하고 없는 장만 API 호출 |

실행 예:

```
./gradlew bootRun --args='--input=./input --retry-failed'
```

## 8. 테스트 전략

TDD로 진행하며 커버리지 80% 이상을 목표로 한다.

**단위 테스트**
- `ImageScanner`: 정렬·확장자 필터·채번, 매직 바이트 MIME 판별, 실패해도 번호가 밀리지 않음
- `RecordAssembler`: §6.1 실패 조합 4가지가 각각 올바른 셀 문자열을 만드는지
- `AnalysisComposer`: 특이사항 블록이 항상 생성됨, `eraNote`가 null일 때 생략됨,
  관인 없는 문서가 "확인되지 않음"으로 채워짐
- `OcrResultStore`: 저장·읽기 왕복, 파일이 없을 때 빈 Optional
- `SheetLayout`: `IMAGE()`/`HYPERLINK()` 수식 빌더, 셀 좌표 매핑, 가로/세로 문서 크기 인자
- `BatchReport`: 종료 코드 산출

**통합 테스트**
- MockWebServer로 Anthropic 응답을 스텁하고 Drive/Sheets는 인터페이스 페이크로 대체하여
  파이프라인 전체를 검증
- 실패 조합 4가지(§6.1)가 각각 올바른 셀 내용을 만드는지
- 계통 실패 시 첫 발생에서 중단되는지

**옵트인 테스트**
- 실제 API 호출 테스트는 `ANTHROPIC_API_KEY` 존재 시에만 활성화

## 9. 제출물

- 소스 + Gradle Wrapper (`./gradlew bootRun` 한 줄로 실행)
- `README.md` (한국어): GCP 프로젝트 준비, Sheets/Drive API 활성화, 서비스 계정 키 발급,
  스프레드시트·폴더 공유 절차, 설정값, 실행 명령, 결과 확인, 모델별 비용표, 트러블슈팅
- `application-example.yml`
- 자격증명·출력물은 `.gitignore`

## 10. 명시적 비범위 (YAGNI)

- **2차 검증 패스** (같은 이미지를 재판독하여 대조): 정확도는 오르나 비용·시간이 2배이고
  과제 요구사항 밖
- **Spring Batch**: 이미지 10장 규모에 Job/Step 구성과 메타데이터 테이블은 과잉
- **REST API**: 배치 과제이므로 CLI로 충분
- **DB 영속화**: 결과 JSON 파일로 충분
- **자동 캐시 재사용·무효화 판정**: 기본 실행을 항상 새 호출로 두면 불필요해진다
- **Anthropic prompt caching**: 적중은 되겠으나 절약액이 약 $0.06 수준
- **병렬 처리**: 10장 규모에서 절약되는 수 분보다 스레드 관리·로그 가독성 손실이 크다
- **초과 이미지 자동 축소**: 현 입력에서 발동하지 않는 경로. 검증 후 명확히 실패시킨다
- **로컬 이미지 리사이즈/썸네일 생성**: 원본이 무축소 처리되고, Drive 썸네일은 Google이
  생성하므로 픽셀을 건드릴 이유가 없음

## 11. 참고 근거

- 이미지 한도·토큰 계산·해상도 티어: Claude 공식 Vision 문서
  (고해상도 티어 = Claude 4.7 이후, 장변 2576px / 4784 비주얼 토큰)
- 이미지당 최대 크기: 직접 호출 시 base64 10MB
- Opus 5에서 `temperature`/`top_p`/`top_k` 제거
- Java SDK 구조화 출력: `.outputConfig(POJO.class)` → `StructuredMessageCreateParams<T>`
