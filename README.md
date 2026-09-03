# 기록물 정리 OCR 솔루션

특정 디렉토리의 기록물 사진을 Claude로 OCR 하여, 인식 결과와 원본 사진을
Google Sheet에 자동으로 기록하는 배치 프로그램입니다.

## 동작

```
input/*.jpg
   ├─ Claude Messages API (base64 직접 전달)  → 제목 + 분석(내용)
   └─ Google Drive 업로드 → 링크 공유          → 시트 셀의 =IMAGE()
                    ↓
          Google Sheet
            목록 시트    : 폴더번호 | 파일번호 | 세부번호 | 제목
            상세 시트 N개 : 파일번호/세부번호/제목/분석(내용)/사진
```

파일번호는 1 고정, 세부번호는 파일명 정렬 순서로 1부터 부여됩니다.

## 요구사항

- JDK 21 이상 (`java -version`으로 확인)
- Gradle 설치 불필요 — `./gradlew`가 알아서 받습니다
- Anthropic API 키
- Google Cloud 프로젝트

## 준비

설정값은 모두 저장소 루트의 `application-local.yml` 한 파일에 넣습니다.
이 파일은 `.gitignore` 대상이라 커밋되지 않습니다.

```bash
cp application-example.yml application-local.yml
```

아래 절차를 따라가며 이 파일의 빈 칸을 채우면 됩니다.

### 1. Anthropic API 키

[console.anthropic.com](https://console.anthropic.com) → Settings → API Keys 에서
발급한 뒤 `application-local.yml` 에 넣습니다.

```yaml
ocr:
  claude:
    api-key: "sk-ant-..."
```

환경변수 방식도 그대로 지원합니다. `api-key` 를 비워 두면
`ANTHROPIC_API_KEY` 환경변수를 읽습니다.

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

### 2. Google Cloud 설정

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 만듭니다.
2. **API 및 서비스 → 라이브러리**에서 다음 두 개를 사용 설정합니다.
   - Google Sheets API
   - Google Drive API
3. **API 및 서비스 → 사용자 인증 정보 → 사용자 인증 정보 만들기 → 서비스 계정**
   으로 서비스 계정을 만듭니다.
4. 만든 서비스 계정 → **키 → 키 추가 → 새 키 만들기 → JSON**을 내려받아
   `credentials/service-account.json`으로 저장합니다.
5. 서비스 계정 이메일(`...@<프로젝트>.iam.gserviceaccount.com`)을 복사해 둡니다.

### 3. 스프레드시트와 Drive 폴더 공유

이 단계를 빠뜨리면 403으로 중단됩니다.

1. 빈 Google 스프레드시트를 만들고, **공유**에서 위 서비스 계정 이메일을
   **편집자**로 추가합니다.
   URL의 `/d/`와 `/edit` 사이 문자열이 스프레드시트 ID입니다.
2. Google Drive에 폴더를 만들고, 같은 서비스 계정 이메일을 **편집자**로 추가합니다.
   URL의 `folders/` 뒤 문자열이 폴더 ID입니다.

### 4. 설정 파일 정리

3단계에서 얻은 두 ID 를 `application-local.yml` 에 넣습니다.

```yaml
ocr:
  google:
    spreadsheet-id: "여기에_스프레드시트_ID"
    drive-folder-id: "여기에_폴더_ID"
```

최종적으로 `application-local.yml` 은 이런 모습이 됩니다.

```yaml
ocr:
  claude:
    api-key: "sk-ant-..."
  google:
    credentials-path: ./credentials/service-account.json
    spreadsheet-id: "1AbC...xYz"
    drive-folder-id: "1DeF...uVw"
```

`src/main/resources/application.yml` 은 손대지 않아도 됩니다. 그 파일은 기본값만
담고 있고, `application-local.yml` 이 있으면 해당 값들을 덮어씁니다. 파일이 없으면
무시되므로, 환경변수만으로 실행해도 동작합니다.

## 실행

기록물 사진을 `input/`에 넣고:

```bash
./gradlew bootRun
```

옵션:

```bash
# 입력 디렉토리 지정
./gradlew bootRun --args='--input=./다른폴더'

# 출력 디렉토리 지정 (OCR 결과 JSON과 리포트가 저장되는 위치)
./gradlew bootRun --args='--output=./다른출력폴더'

# 실패했던 항목만 다시 처리 (성공한 것은 저장된 결과 재사용)
./gradlew bootRun --args='--retry-failed'

# 저장된 OCR 결과만 사용 (Claude를 호출하지 않음)
./gradlew bootRun --args='--skip-ocr'

# 모델 변경
./gradlew bootRun --args='--ocr.claude.model=claude-sonnet-5'
```

실행이 끝나면 콘솔에 스프레드시트 URL과 성공/실패 요약이 출력됩니다.

## 결과 확인

- **Google Sheet** — `목록` 시트의 제목을 클릭하면 해당 상세 시트로 이동하고,
  상세 시트 좌상단 `◀ 목록으로`로 돌아옵니다.
- **`<--output>/raw/*.json`** — Claude 응답 원본이 항목별로 분리되어 남습니다.
  시트에는 조립된 최종 문자열만 보이므로, 항목별 원본을 보려면 이 파일을 확인하세요.
  (기본값: `output/raw/`)

## 재실행 모드

OCR 결과는 항상 `<--output>/raw/`에 저장됩니다 (기본값: `output/raw/`).
기본 실행은 이 파일을 **읽지 않고** 매번 새로 호출하므로 낡은 결과가 섞일 일이 없습니다.

| 명령 | 저장된 결과가 있으면 | 없으면 |
|---|---|---|
| `./gradlew bootRun` | 무시하고 새로 호출 | 새로 호출 |
| `... --args='--retry-failed'` | 재사용 | 새로 호출 |
| `... --args='--skip-ocr'` | 재사용 | 실패 처리 (호출 안 함) |

**`--retry-failed`** — 일부 이미지만 실패했을 때 씁니다. 성공한 것은 저장된 결과를
재사용하고 실패했던 것만 다시 처리하므로, 10장 중 1장이 실패했다면 1장만 비용이 듭니다.
사진 업로드도 마찬가지입니다. 이미 올라간 파일은 다시 올리지 않습니다.

실패가 있으면 실행 결과 마지막에 이 명령이 그대로 안내됩니다.

**`--skip-ocr`** — Claude를 절대 호출하지 않습니다.

- 시트 서식(열 너비, 행 높이)을 다듬으며 여러 번 돌릴 때
- Drive 권한 오류로 중단된 뒤 권한을 고치고 재실행할 때

`--retry-failed`와 달리 저장된 결과가 없는 장은 호출하지 않고 실패로 남깁니다.
"호출하지 않는다"가 확실해야 할 때 씁니다.

## 모델과 비용

기본 모델은 `claude-opus-5`입니다. 10장 1회 실행 실비는 약 $0.54입니다.

| 모델 | 10장 1회 | 비고 |
|---|---|---|
| `claude-opus-5` (기본) | ~$0.54 | 고해상도 티어(장변 2576px)라 입력 이미지가 축소되지 않음 |
| `claude-sonnet-5` | ~$0.21 | 동일한 고해상도 티어 |
| `claude-haiku-4-5` | ~$0.11 | 표준 티어(1568 비주얼 토큰)라 다수 이미지가 강제 축소됨 |

세로쓰기 한자·손글씨·훼손 문서 판독이 이 작업의 난이도이므로 기본값을 Opus 5로
두었습니다.

## 종료 코드

| 코드 | 의미 |
|---|---|
| 0 | 전량 성공 |
| 1 | 부분 실패 (일부 이미지의 OCR 또는 업로드 실패, 시트는 기록됨). `--retry-failed`로 재시도 |
| 2 | 계통 실패 (권한·설정 오류로 중단) |

## 문제 해결

**`Drive 업로드 권한 오류 (403 insufficientPermissions)`**
Drive 폴더를 서비스 계정 이메일에 편집자로 공유했는지 확인하세요. 조직 정책이
링크 공유를 차단하는 경우 `=IMAGE()` 렌더링이 불가능합니다.

**시트의 사진 칸이 깨져 보임**
Drive가 썸네일을 생성하는 데 시간이 걸릴 수 있습니다. 잠시 후 새로고침하세요.
계속 깨진다면 업로드된 파일의 공유 설정이 "링크가 있는 모든 사용자"인지 확인하세요.

**`ocr.google.spreadsheet-id 가 비어 있습니다`**
`src/main/resources/application.yml`에 스프레드시트 ID를 넣지 않았습니다.

**설정한 값이 반영되지 않음**
`application-local.yml` 이 저장소 루트(`build.gradle` 과 같은 위치)에 있는지
확인하세요. `src/main/resources/` 안이 아닙니다. 파일명 오타도 흔한 원인입니다.

**`서비스 계정 키 파일을 찾을 수 없습니다`**
`credentials/service-account.json` 경로를 확인하세요.

**이미지 장변 초과 오류**
이 프로그램은 판독 품질을 위해 이미지를 자동 축소하지 않습니다. 장변 2576px 이하로
줄여서 다시 넣어 주세요.

## 테스트

```bash
./gradlew test
```

외부 API를 호출하지 않습니다. Anthropic·Drive·Sheets는 모두 대역으로 대체됩니다.

## 구조

| 패키지 | 책임 |
|---|---|
| `image` | 스캔·정렬·매직바이트 MIME 판별·채번 |
| `claude` | 프롬프트, 크기 검증, Messages API 호출 |
| `store` | OCR 결과 JSON 저장·읽기 |
| `drive` | 업로드·링크 공유·썸네일 URL |
| `sheets` | 셀 좌표·수식 조립, 시트 생성 2단계 |
| `domain` | 도메인 타입, 분석 내용 조립, 실패 조합 처리 |
| `report` | 요약 출력, 종료 코드 |
| `runner` | 전체 배선 |

설계 근거는 `docs/superpowers/specs/2026-09-03-archive-ocr-design.md`에 있습니다.
