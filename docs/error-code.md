# 공통 에러 응답과 에러 코드

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 |
| 마지막 갱신 | 2026-08-02 |
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
| `SIGNUP_REQUIRED` | 409 | Google 신규 가입을 위한 역할·약관 추가 정보 필요 |
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
| `MATERIAL_HAS_ACTIVE_SESSION` | 409 | 활성 세션이 있어 자료 삭제 불가 (DEC-028) |
| `MATERIAL_LINKED_TO_CLASSROOM` | 409 | 강의실 주차에 연결되어 있어 자료 삭제 불가 — 먼저 연결 해제 필요 |
| `PAGE_OUT_OF_RANGE` | 400 | 페이지 번호 범위 초과 |

### 강의실

| code | HTTP | 의미 |
| --- | ---: | --- |
| `CLASSROOM_NOT_FOUND` | 404 | 강의실이 없거나 현재 사용자가 접근할 수 없음 — 소유권·멤버십 은닉 |
| `INVALID_INVITE_CODE` | 404 | 초대 코드가 없거나 완료된 강의실의 무효 코드 |
| `ALREADY_CLASSROOM_MEMBER` | 409 | 이미 승인된 강의실 멤버 |
| `JOIN_REQUEST_ALREADY_PENDING` | 409 | 같은 강의실에 대기 중인 참여 요청이 있음 |
| `JOIN_REQUEST_ALREADY_PROCESSED` | 409 | 참여 요청이 이미 승인 또는 거절됨 |
| `WEEK_NOT_FOUND` | 404 | 주차가 없거나 상위 강의실에 접근할 수 없음 |
| `WEEK_ALREADY_EXISTS` | 409 | 같은 강의실에 동일 주차 번호가 이미 존재함 |
| `MATERIAL_ALREADY_LINKED` | 409 | 같은 주차에 자료가 이미 연결됨 |
| `CLASSROOM_COMPLETED` | 409 | 완료 강의실에서 허용되지 않는 관리 쓰기 또는 참여 처리 시도 |
| `CLASSROOM_WEEK_RANGE_CONFLICT` | 409 | 종료일 축소로 기존 주차가 계산된 주차 범위를 벗어남 |

### 세션·턴

| code | HTTP | 의미 |
| --- | ---: | --- |
| `SESSION_NOT_FOUND` | 404 | 세션 없음 |
| `SESSION_ACCESS_DENIED` | 403/404 | 세션 소유권 없음 |
| `SESSION_NOT_ACTIVE` | 409 | 완료/삭제된 세션 |
| `SESSION_STATE_CONFLICT` | 409 | 현재 상태에서 실행 불가 |
| `UNSUPPORTED_EVENT_TYPE` | 400 | 알 수 없는 학습 이벤트 |
| `TURN_ALREADY_PROCESSED` | 409 | 성공 또는 진행 상태 턴의 동일 `requestId` 재전송. `FAILED` 사용자 메시지는 같은 ID로 재시도 가능 |
| `TURN_IN_PROGRESS` | 409 | 같은 세션에서 충돌하는 턴 진행 중 — 세션당 동시 턴 1개 원칙, 판정 방식(플래그/낙관적 잠금)은 구현에서 선택 |
| `TURN_CANCELLED` | 409 | 사용자가 content 수신 전에 스트리밍 턴을 취소함. SSE error에서는 `retryable=false`이며 같은 requestId로 실패 메시지를 재사용할 수 있음 |

### 학습 노트

| code | HTTP | 의미 |
| --- | ---: | --- |
| `NOTE_NOT_FOUND` | 404 | 노트가 없거나 현재 사용자의 노트가 아님 |

### 일정

| code | HTTP | 의미 |
| --- | ---: | --- |
| `SCHEDULE_NOT_FOUND` | 404 | 개인 일정이 없거나 현재 사용자의 일정이 아님 |

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

### 별도 시험

| code | HTTP | 의미 |
| --- | ---: | --- |
| `EXAM_NOT_FOUND` | 404 | 시험이 없거나 접근할 수 없음. 학생의 DRAFT 목록·상세·제출 접근도 이 코드로 은닉 |
| `EXAM_NOT_PUBLISHED` | 409 | 학생이 CLOSED 시험에 제출하거나 강사가 DRAFT 시험을 close하는 등 공개 상태가 아닌 대상에 상태 작업을 요청 |
| `EXAM_NOT_EDITABLE` | 409 | CLOSED 시험을 publish하거나 공개 이후 수정·삭제하는 등 편집 가능한 상태가 아님 |
| `EXAM_ALREADY_SUBMITTED` | 409 | 재응시가 허용되지 않은 GRADED 시험 또는 채점 중인 SUBMITTED 시험에 새 `requestId`로 다시 제출. SUBMITTED일 수 있으므로 FE는 기존 결과·polling 화면으로 유도 |
| `INVALID_EXAM_ANSWER` | 400 | 알 수 없거나 중복된 문항 ID 또는 문항 유형과 맞지 않는 답안 |

### 리포트

| code | HTTP | 의미 |
| --- | ---: | --- |
| `REPORT_NOT_FOUND` | 404 | 리포트가 없거나 현재 사용자가 해당 리포트의 강의실을 관리하지 않음 |
| `REPORT_CRITERION_LIMIT_EXCEEDED` | 400 | 기본 9종을 포함한 강의실 활성 리포트 기준이 20개를 초과하거나 자동 생성 여유 슬롯이 3개 미만임 |
| `REPORT_CRITERIA_GENERATION_NOT_READY` | 400 | 개요가 생성된 강의실 자료가 없어 평가 지표를 자동 생성할 수 없음 |
| `REPORT_CRITERION_DUPLICATE` | 409 | 정규화한 key 또는 이름이 기존 리포트 기준과 중복됨 |

### AI 연동

| code | HTTP | 의미 |
| --- | ---: | --- |
| `AI_SERVICE_UNAVAILABLE` | 503 | FastAPI/Grok 일시 불가 |
| `AI_SERVICE_TIMEOUT` | 504 | AI 호출 시간 초과 — 스트림 이벤트 30초 무응답 또는 턴 총 200초 초과 포함 |
| `AI_RESPONSE_INVALID` | 502 | 스키마에 맞지 않는 응답 |
| `AI_POLICY_REJECTED` | 502 또는 409 | Plan 정책 검증 실패 — 현재 세션 상태에서 허용되지 않는 요청이 원인이면 409, AI가 생성한 Plan 자체가 정책·스키마를 위반하면 502 |
| `AI_STREAM_INTERRUPTED` | 502 | timeout이 아닌 스트림 비정상 종료 — terminal 전 EOF, FE 연결 종료, 중계 I/O 중단 |

FastAPI 내부 API의 공통 요청·인증 오류와 xAI Files 삭제 오류는 다음과 같습니다.

| code | HTTP | category | 의미 |
| --- | ---: | --- | --- |
| `AI_INTERNAL_AUTH_FAILED` | 401 | `AUTH` | `X-Internal-Token` 누락 또는 불일치 |
| `AI_REQUEST_INVALID` | 400 / 422 | `SCHEMA` | `422`: 내부 요청의 필수 필드·타입 등 body·DTO 검증 실패, `400`: `questionId` 집합 불일치 등 필드 간 의미 검증 실패 |
| `AI_INTERNAL_ERROR` | 500 | `INTERNAL` | 분류되지 않은 AI Service 내부 오류 |
| `FILE_DELETE_FAILED` | 502 | `INTERNAL` | xAI Files 삭제 실패(404 제외), `retryable=true` |

`FILE_UPLOAD_FAILED`는 오류 봉투 code가 아니라 `/internal/ai/extract`의 `warnings[].type`입니다. xAI Files 업로드 실패·48MiB 초과를 이 warning으로 알리며 텍스트 추출 성공 응답은 HTTP 200을 유지합니다.

`AI_REQUEST_INVALID`의 두 HTTP 상태는 모두 `category=SCHEMA`, `retryable=false`인 동일한 표준 오류 봉투를 사용합니다. Spring 비동기 시험 채점 worker가 grade 호출에서 `AI_REQUEST_INVALID`을 받으면 HTTP 상태와 무관하게 재시도하지 않고 제출을 `GRADING_FAILED`로 종결합니다. 학생 입력 오류나 일반 AI 장애와 구분할 수 있도록 `submissionId`, `examId`, 오류 code를 ERROR 로그에 남기며, 이미 커밋된 제출을 보상 삭제하거나 원 POST에 500을 반환하지 않습니다(DEC-032).

새 오류 code는 구현보다 먼저 이 문서에 추가합니다.

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
