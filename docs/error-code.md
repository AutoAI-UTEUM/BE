# 공통 에러 응답과 에러 코드

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 |
| 마지막 갱신 | 2026-07-21 |
| 코드 형식 | `UPPER_SNAKE_CASE` |

## 1. 응답 형식

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "요청 값을 확인해 주세요.",
    "details": [
      {
        "field": "email",
        "reason": "올바른 이메일 형식이어야 합니다."
      }
    ]
  },
  "traceId": "01J...",
  "timestamp": "2026-07-10T09:00:00Z"
}
```

- `code`는 FE 분기와 로그 검색에 사용하는 안정된 값입니다.
- `message`는 사용자에게 노출 가능한 문구입니다.
- `details`는 입력 오류처럼 필요한 경우에만 제공합니다.
- stack trace, SQL, 내부 URL, 프롬프트, 토큰, 파일 경로는 응답에 포함하지 않습니다.
- `traceId`로 서버 로그를 연결합니다.

## 2. HTTP 상태 사용 원칙

| 상태 | 사용 |
| --- | --- |
| 400 | 요청 형식/값 오류 |
| 401 | 인증 누락, 만료, 위조 |
| 403 | 역할 또는 소유권 부족 |
| 404 | 리소스 없음 또는 존재를 숨겨야 하는 소유권 오류 정책 |
| 409 | 중복, 현재 상태와 충돌, 이미 제출/완료 |
| 413 | 업로드 또는 요청 크기 초과 |
| 415 | 지원하지 않는 콘텐츠 타입(Content-Type) |
| 422 | 문법은 맞지만 처리할 수 없는 도메인 입력을 쓸지 TBD |
| 429 | 요청/AI 사용량 제한 |
| 500 | 예상하지 못한 서버 오류 |
| 502 | FastAPI/Grok의 잘못된 응답 |
| 503 | 의존 서비스 일시 불가 |
| 504 | AI 또는 내부 서비스 시간 초과 |

## 3. 코드 목록

### 공통·인증

| code | HTTP | 의미 |
| --- | ---: | --- |
| `VALIDATION_FAILED` | 400 | 필드 검증 실패 |
| `MALFORMED_REQUEST` | 400 | JSON/요청 구조 오류 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 콘텐츠 타입 |
| `AUTHENTICATION_REQUIRED` | 401 | 인증 정보 없음 |
| `TOKEN_INVALID` | 401 | 위조/형식 오류 토큰 |
| `TOKEN_EXPIRED` | 401 | 만료된 토큰 |
| `ACCESS_DENIED` | 403 | 역할/권한 부족 |
| `RESOURCE_NOT_FOUND` | 404 | 일반 리소스 없음 |
| `RATE_LIMIT_EXCEEDED` | 429 | 호출 제한 초과 |
| `INTERNAL_SERVER_ERROR` | 500 | 예상하지 못한 오류 |

### 사용자

| code | HTTP | 의미 |
| --- | ---: | --- |
| `EMAIL_ALREADY_EXISTS` | 409 | 이메일 중복 |
| `INVALID_CREDENTIALS` | 401 | 이메일/비밀번호 불일치 |
| `USER_INACTIVE` | 403 | 비활성/삭제 계정 |
| `USER_NOT_FOUND` | 404 | 사용자 없음 |

### 자료

| code | HTTP | 의미 |
| --- | ---: | --- |
| `MATERIAL_NOT_FOUND` | 404 | 자료 없음 |
| `MATERIAL_ACCESS_DENIED` | 403/404 | 자료 접근 불가 |
| `INVALID_PDF_FILE` | 400 | PDF가 아니거나 손상됨 |
| `FILE_TOO_LARGE` | 413 | 파일 제한 초과 |
| `MATERIAL_PROCESSING` | 409 | 아직 처리 중 |
| `MATERIAL_PROCESSING_FAILED` | 409 | 텍스트 추출 등 처리 실패 |
| `PAGE_OUT_OF_RANGE` | 400 | 페이지 번호 범위 초과 |

### 세션·턴

| code | HTTP | 의미 |
| --- | ---: | --- |
| `SESSION_NOT_FOUND` | 404 | 세션 없음 |
| `SESSION_ACCESS_DENIED` | 403/404 | 세션 소유권 없음 |
| `SESSION_NOT_ACTIVE` | 409 | 완료/삭제된 세션 |
| `SESSION_STATE_CONFLICT` | 409 | 현재 상태에서 실행 불가 |
| `UNSUPPORTED_EVENT_TYPE` | 400 | 알 수 없는 학습 이벤트 |
| `TURN_ALREADY_PROCESSED` | 409 또는 기존 결과 반환 | 동일 요청 중복 |
| `TURN_IN_PROGRESS` | 409 | 같은 세션에서 충돌하는 턴 진행 중 — 세션당 동시 턴 1개 원칙, 판정 방식(플래그/낙관적 잠금)은 구현에서 선택 |

### 퀴즈·진단

| code | HTTP | 의미 |
| --- | ---: | --- |
| `QUIZ_NOT_FOUND` | 404 | 퀴즈 없음 |
| `UNSUPPORTED_QUIZ_TYPE` | 400 | 지원하지 않는 유형 |
| `INVALID_QUIZ_ANSWER` | 400 | 문항 누락/알 수 없는 문항 ID |
| `QUIZ_ALREADY_SUBMITTED` | 409 | 재제출 불가 상태 |
| `QUIZ_NOT_SUBMITTABLE` | 409 | 현재 제출할 수 없는 퀴즈 |
| `GRADING_RESULT_INVALID` | 502 | AI 채점 결과 불일치 |
| `DIAGNOSIS_NOT_FOUND` | 404 | 진단 없음 |
| `DIAGNOSIS_NOT_PENDING` | 409 | 답변 대기 상태 아님 |

### AI 연동

| code | HTTP | 의미 |
| --- | ---: | --- |
| `AI_SERVICE_UNAVAILABLE` | 503 | FastAPI/Grok 일시 불가 |
| `AI_SERVICE_TIMEOUT` | 504 | AI 호출 시간 초과 |
| `AI_RESPONSE_INVALID` | 502 | 스키마에 맞지 않는 응답 |
| `AI_POLICY_REJECTED` | 502 또는 409 | Plan 정책 검증 실패 — 현재 세션 상태에서 허용되지 않는 요청이 원인이면 409, AI가 생성한 Plan 자체가 정책·스키마를 위반하면 502 |
| `AI_STREAM_INTERRUPTED` | 502/504 | 스트림 비정상 종료 — 중계/응답 오류로 끊기면 502, 시간 초과로 끊기면 504 |

## 4. FE 처리 기준

| 범주 | 처리 |
| --- | --- |
| 400/409 | 해당 입력/상태 안내, 가능한 경우 다시 시도 |
| 401 | 토큰 정책에 따라 갱신 또는 로그인 이동 |
| 403/404 | 접근 불가 안내 후 안전한 화면으로 이동 |
| 429 | 재시도 가능 시점 안내 |
| 502/503/504 | 현재 상태를 유지하고 제한된 재시도 제공 |

FE는 `message` 문자열을 파싱하지 않고 `code`로 분기합니다.

## 5. 로깅 기준

- 서버 로그에는 `traceId`, 사용자/세션의 안전한 식별자, 에러 코드, 처리 구간을 남깁니다.
- 비밀번호, JWT, Grok(xAI) API Key, 전체 PDF 텍스트, 학생 답안 원문은 기본 오류 로그에 남기지 않습니다.
- AI 원문 로깅이 꼭 필요하면 마스킹, 접근 통제, 보관 기간을 먼저 결정합니다.

