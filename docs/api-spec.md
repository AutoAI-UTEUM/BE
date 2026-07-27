# API 명세서

| 항목 | 내용 |
| --- | --- |
| 상태 | 계약 초안 |
| 마지막 갱신 | 2026-07-23 |
| 외부 호출자 | Frontend |
| 내부 호출자 | Spring → FastAPI |

> 구현 이후에는 생성된 OpenAPI가 필드 수준 계약의 실행 가능한 기준입니다. 이 문서와 Swagger는 같은 PR에서 갱신합니다.

## 1. 공통 규칙

- 외부 API base path는 `/api` 무버전으로 확정입니다(DEC-015 — 외부 공개 시 `/api/v1` 도입). breaking change는 FE·BE 합의 후 동시 배포로 반영합니다.
- 인증 API를 제외한 외부 API는 `Authorization: Bearer {accessToken}`을 요구합니다.
- 시간은 ISO-8601 UTC로 반환합니다.
- 페이지네이션은 `page=0`, `size=20` 기본 초안을 사용합니다.
- 리소스 소유권 위반은 다른 사용자 데이터 존재 여부를 과도하게 노출하지 않도록 처리합니다.
- 요청/응답 필드명은 JSON `camelCase`를 사용합니다.
- 성공·실패 envelope 적용 범위는 전 API에서 일관되게 유지합니다.

### 성공 응답

```json
{
  "success": true,
  "data": {},
  "message": "요청이 성공했습니다."
}
```

### 실패 응답

```json
{
  "success": false,
  "error": {
    "code": "SESSION_NOT_FOUND",
    "message": "학습 세션을 찾을 수 없습니다.",
    "details": []
  },
  "traceId": "01J...",
  "timestamp": "2026-07-10T09:00:00Z"
}
```

자세한 코드는 [에러 코드](error-code.md)를 따릅니다.

## 2. Spring 외부 API

| Method | URL | 설명 | 인증 | 권한/소유권 |
| --- | --- | --- | :---: | --- |
| POST | `/api/auth/signup` | 회원가입 | N | 전체 |
| POST | `/api/auth/login` | 로그인 | N | 전체 |
| POST | `/api/auth/refresh` | access 재발급 (refresh 쿠키 회전) | 쿠키 | refresh 쿠키 보유자 |
| POST | `/api/auth/logout` | 로그아웃 (refresh 폐기·쿠키 만료) | 쿠키 | refresh 쿠키 보유자 (멱등) |
| GET | `/api/users/me` | 내 정보 조회 | Y | 본인 |
| DELETE | `/api/users/me` | 회원 탈퇴(논리 삭제+익명화 — DEC-028) | Y | 본인 (비밀번호 재확인) |
| POST | `/api/materials` | PDF 업로드 | Y | USER, ADMIN 초안 |
| GET | `/api/materials` | 자료 목록 | Y | 본인 소유 자료 (DEC-026) |
| GET | `/api/materials/{materialId}` | 자료 상세 | Y | 본인 소유 자료 |
| DELETE | `/api/materials/{materialId}` | 자료 논리 삭제 (DEC-028) | Y | 본인 소유 자료 |
| GET | `/api/materials/{materialId}/pages/{pageNumber}` | 페이지 텍스트 | Y | 본인 소유 자료 — 운영 비노출, dev/디버깅 한정(DEC-025) |
| POST | `/api/sessions` | 학습 세션 생성 | Y | USER |
| GET | `/api/sessions` | 내 세션 목록 조회 | Y | 본인 |
| GET | `/api/sessions/{sessionId}` | 세션 상태 조회 | Y | 세션 소유자 |
| DELETE | `/api/sessions/{sessionId}` | 세션 논리 삭제 | Y | 세션 소유자 |
| PATCH | `/api/sessions/{sessionId}/page` | 페이지 이동 | Y | 세션 소유자 |
| POST | `/api/sessions/{sessionId}/turns` | 학습 턴 처리 | Y | 세션 소유자 |
| GET | `/api/sessions/{sessionId}/stream` | SSE 스트림 (fetch + Bearer — DEC-021) | Y | 세션 소유자 |
| GET | `/api/sessions/{sessionId}/messages` | 메시지 조회 | Y | 세션 소유자 |
| GET | `/api/sessions/{sessionId}/quizzes` | 퀴즈 기록 조회 | Y | 세션 소유자 |
| GET | `/api/quizzes/{quizId}` | 퀴즈 공개 문항 조회 | Y | 세션 소유자 |
| POST | `/api/quizzes/{quizId}/submit` | 퀴즈 제출 | Y | 세션 소유자 |
| GET | `/api/users/me/memory?materialId={materialId}` | 학습자 메모리 조회(자료별) | Y | 본인 |
| POST | `/api/sessions/{sessionId}/complete` | 세션 종료 | Y | 세션 소유자 |

## 3. 인증 API

### POST `/api/auth/signup`

```json
{
  "email": "user@example.com",
  "password": "password123",
  "name": "홍길동"
}
```

`data`:

```json
{
  "userId": 1,
  "email": "user@example.com",
  "name": "홍길동"
}
```

비밀번호 정책(확정): **8~64자, 영문·숫자 각 1자 이상 포함**(특수문자 허용). 위반 시 `VALIDATION_FAILED` + `details: [{ "field": "password", "reason": "..." }]`.

주요 오류: `VALIDATION_FAILED`, `EMAIL_ALREADY_EXISTS`.

### POST `/api/auth/login`

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

`data`:

```json
{
  "accessToken": "jwt-token",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": 1,
    "email": "user@example.com",
    "name": "홍길동",
    "role": "USER"
  }
}
```

refresh token은 응답 body에 포함하지 않고 쿠키로 발급합니다(DEC-004 Accepted). 쿠키 계약(확정): 이름 `edupilot_refresh`, `HttpOnly`, `Secure`, `SameSite=Lax`, **`Path=/api/auth`**(refresh·logout 요청에만 전송되도록 최소화), Max-Age 14일. 서버는 refresh 해시를 DB에 저장하고 회전·재사용 감지·강제 폐기를 지원합니다. access token 만료는 1시간이며 FE는 메모리에 보관합니다(localStorage 금지). 주요 오류: `INVALID_CREDENTIALS`, `USER_INACTIVE`.

### POST `/api/auth/refresh`

요청 body 없음 — `edupilot_refresh` 쿠키만 사용합니다.

`data` (login 응답과 동일 형식):

```json
{
  "accessToken": "jwt-token",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

- **회전**: 성공 시 기존 refresh는 폐기되고 새 refresh 쿠키가 재발급됩니다. FE는 401 수신 시 이 API를 `credentials: "include"`로 호출해 access를 재발급받습니다.
- **재사용 감지**: 이미 폐기(회전)된 refresh가 재사용되면 탈취 신호로 간주해 **해당 사용자의 refresh를 전량 폐기**하고 401을 반환합니다. FE 분기 단순화를 위해 별도 코드 없이 `TOKEN_INVALID`로 통일합니다(재로그인 유도).
- 주요 오류: `TOKEN_INVALID`(401 — 쿠키 없음·미존재·폐기·만료·재사용 감지), `USER_INACTIVE`(403 — 탈퇴·비활성 사용자).

### POST `/api/auth/logout`

요청 body 없음 — `edupilot_refresh` 쿠키의 refresh를 폐기하고 쿠키를 만료(Max-Age=0)시킵니다. 이미 폐기됐거나 쿠키가 없어도 200을 반환합니다(멱등). access token은 서버가 무효화하지 않으며 만료(최대 1시간)로 소멸합니다 — FE는 로그아웃 시 메모리의 access를 즉시 삭제합니다.

### DELETE `/api/users/me`

```json
{
  "password": "password123"
}
```

회원 탈퇴(DEC-028). 비밀번호 재확인 후 `status=DELETED` 전환과 동시에 개인 식별 정보를 익명화합니다(email → `deleted_{id}`, name → 고정 문구, password_hash 무효화 — 재가입 허용). refresh token은 전부 폐기합니다. 소유 자료·세션은 함께 논리 삭제하고, 퀴즈 제출·평가·메모리 레코드는 익명 상태로 보존합니다. 복구는 지원하지 않으므로 FE는 확인 모달을 거쳐 호출합니다. 주요 오류: `INVALID_CREDENTIALS`.

## 4. 자료 API

### POST `/api/materials`

`Content-Type: multipart/form-data`

| part | 타입 | 필수 | 설명 |
| --- | --- | :---: | --- |
| `file` | PDF binary | Y | 최대 45MB·300페이지, `%PDF-` 매직 바이트 검증(DEC-016) |
| `title` | string | Y | 자료 제목 |

`data` 초안:

```json
{
  "materialId": 10,
  "title": "선형회귀 기초",
  "pageCount": 25,
  "processingStatus": "READY",
  "createdAt": "2026-07-10T09:00:00Z"
}
```

업로드 직후 응답은 `processingStatus=PROCESSING`, `pageCount=null`입니다. Spring이 백그라운드에서 내부 API `POST /internal/ai/extract`로 추출을 요청하고, 결과 저장 후 `READY`(실패 시 `FAILED`)로 전이합니다(DEC-006). `processingStatus`는 `PROCESSING`, `READY`, `FAILED` 3값을 사용합니다. FE는 자료 상세 재조회로 상태를 확인합니다.

### GET `/api/materials`

Query: `page`, `size`, 선택 검색/정렬 필드는 TBD.

`data`:

```json
{
  "items": [
    {
      "materialId": 10,
      "title": "선형회귀 기초",
      "pageCount": 25,
      "processingStatus": "READY",
      "createdAt": "2026-07-10T09:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### GET `/api/materials/{materialId}`

자료 제목, 페이지 수, 처리 상태, 학습 가능 여부를 반환합니다. 원본 파일 접근은 Spring의 인증된 다운로드 스트리밍(`GET /api/materials/{materialId}/file` 초안)으로 제공하며, S3 전환 시 presigned URL 방식으로 변경합니다(DEC-005).

### DELETE `/api/materials/{materialId}`

자료를 논리 삭제(`status=DELETED`)합니다(DEC-028). 삭제된 자료는 목록·상세·세션 생성에서 제외합니다. 해당 자료의 ACTIVE 세션이 있으면 `MATERIAL_HAS_ACTIVE_SESSION`(409)으로 거부합니다 — 세션을 완료하거나 삭제한 뒤 재시도합니다. 완료된 세션·퀴즈·평가 기록은 보존하며, storage 파일은 즉시 삭제하지 않습니다(물리 삭제 배치는 이후 개선안). 주요 오류: `MATERIAL_NOT_FOUND`, `MATERIAL_HAS_ACTIVE_SESSION`.

### GET `/api/materials/{materialId}/pages/{pageNumber}`

페이지 번호와 추출 텍스트를 반환합니다. 운영 FE에는 노출하지 않고 dev/디버깅 프로파일에서만 활성화합니다(DEC-025). 추출 텍스트는 AI 문맥 전용이며 FE는 PDF 원본 뷰어를 사용합니다.

## 5. 세션 API

### POST `/api/sessions`

```json
{
  "materialId": 10
}
```

`data`:

```json
{
  "sessionId": 100,
  "materialId": 10,
  "currentPage": 1,
  "pageStatus": "NOT_EXPLAINED",
  "status": "ACTIVE",
  "reused": false,
  "uiActions": [
    {
      "type": "BINARY_DECISION",
      "content": "강의를 시작할까요?",
      "yesEvent": "EXPLAIN_CURRENT_PAGE",
      "noEvent": "WAIT"
    }
  ]
}
```

같은 자료에 기존 `ACTIVE` 세션이 있으면 새로 만들지 않고 그 세션을 반환하며 `reused: true`로 표시합니다(DEC-024). 처음부터 다시 시작하려면 기존 세션을 삭제한 뒤 생성합니다.

### GET `/api/sessions`

내 세션 목록을 최근 갱신 순으로 반환합니다. Query: `page`, `size`, `status`(선택, 기본은 `ACTIVE`+`COMPLETED`, `DELETED` 제외). 로그아웃·기기 변경 후 재진입 시 FE가 `sessionId`를 얻는 진입점입니다.

`data` 초안:

```json
{
  "items": [
    {
      "sessionId": 100,
      "materialId": 10,
      "materialTitle": "선형회귀 기초",
      "currentPage": 3,
      "status": "ACTIVE",
      "updatedAt": "2026-07-10T09:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### GET `/api/sessions/{sessionId}`

`data`:

```json
{
  "sessionId": 100,
  "materialId": 10,
  "currentPage": 3,
  "pageStatus": "EXPLAINED",
  "status": "ACTIVE",
  "pendingDiagnosis": null,
  "activeQuizId": null,
  "uiActions": [
    {
      "type": "BINARY_DECISION",
      "content": "다음 페이지로 이동할까요?",
      "yesEvent": "MOVE_NEXT_PAGE",
      "noEvent": "WAIT"
    }
  ],
  "updatedAt": "2026-07-10T09:00:00Z"
}
```

`uiActions`는 마지막 턴/페이지 이동/퀴즈 제출 응답에서 내려간 최신 UI 액션을 그대로 반환해, 새로고침·재진입 후에도 진행 중이던 선택 UI를 복원할 수 있게 합니다. `activeQuizId`가 있으면 FE는 `GET /api/quizzes/{quizId}`로 풀이 화면을 복원합니다.

`conversationSummary`·`learnerMemoryDigest`는 **내부 AI 스냅샷 전용이며 세션 상세 응답에 포함하지 않습니다**(확정 — DEC-025의 내부 텍스트 비노출 원칙, 메모리 API의 "공개 가능한 요약만" 원칙과 정합). 서버는 내부 턴 스냅샷 구성에만 사용하고, 학습자에게 보여줄 개인화 요약은 `GET /api/users/me/memory`가 담당합니다.

#### uiActions 위젯

AI Service의 `uiActions`는 예약 필드이며 항상 빈 배열입니다. 위젯은 Spring이
마지막 상태 전이에 따라 생성해 외부 응답에 포함합니다.

서버가 발급하는 위젯 스키마는 다음 2종입니다.

```json
{
  "type": "BINARY_DECISION",
  "content": "퀴즈를 진행할까요?",
  "yesEvent": "SHOW_QUIZ_TYPE_SELECT",
  "noEvent": "MOVE_NEXT_PAGE"
}
```

```json
{
  "type": "DIAGNOSIS_QUESTION",
  "content": "왜 역수를 곱하는지가 막혔나요?",
  "diagnosisId": 30
}
```

- `BINARY_DECISION`: `{type, content, yesEvent, noEvent}`를 모두 포함합니다.
- `DIAGNOSIS_QUESTION`: `{type, content, diagnosisId}`인 입력형 위젯이며
  `yesEvent`·`noEvent`를 포함하지 않습니다.
- `QUIZ_TYPE_SELECT`: FE 로컬 전용 타입입니다. 서버는 이 타입을 발급하거나
  저장하지 않으며, 유형 선택 후 FE가 `QUIZ_TYPE_SELECTED` 턴을 보냅니다.

위젯 생성 규칙은 다음과 같습니다.

| 규칙 | 마지막 상태 전이 | 생성 위젯·동작 |
| --- | --- | --- |
| W1 | 세션 최초 생성 | `BINARY_DECISION("강의를 시작할까요?", EXPLAIN_CURRENT_PAGE, WAIT)` |
| W2 | 페이지 이동 완료 | `BINARY_DECISION("현재 페이지를 설명할까요?", EXPLAIN_CURRENT_PAGE, WAIT)` |
| W3 | 현재 페이지 설명 완료 | `BINARY_DECISION("퀴즈를 진행할까요?", SHOW_QUIZ_TYPE_SELECT, MOVE_NEXT_PAGE)` |
| W4 | W3의 yes 선택 | FE가 로컬 `QUIZ_TYPE_SELECT`를 표시하고 선택값으로 `QUIZ_TYPE_SELECTED` 턴 호출 |
| W5 | 퀴즈 제출 파이프라인 완료 후 다음 학습 가능 | 마지막 페이지가 아니면 `BINARY_DECISION("다음 페이지로 이동할까요?", MOVE_NEXT_PAGE, WAIT)`. 마지막 페이지면 `BINARY_DECISION("학습을 완료할까요?", COMPLETE_SESSION, WAIT)`이며 yes 선택 시 FE가 `POST /api/sessions/{sessionId}/complete` 호출 |
| W6 | 기준 미달이고 진단 생성 성공 | `DIAGNOSIS_QUESTION(content, diagnosisId)` |
| W7 | 진단 답변의 교정 완료 | W5와 같은 다음 페이지/마지막 페이지 완료 분기 |

한 응답에서 여러 상태가 연속으로 바뀌어도 위젯은 **마지막 상태 전이 1개에
대해서만** 생성합니다. 재진입 복원 대상은 Spring이 발급·저장한 위젯만입니다.
W4는 FE 로컬 상태이므로 W4 표시 중 재진입하면 저장된 W3 위젯으로 복원합니다.
`MOVE_NEXT_PAGE`는 turns 이벤트가 아니라 페이지 PATCH 호출,
`COMPLETE_SESSION`은 turns 이벤트가 아니라 complete API 호출로 해석합니다.

### DELETE `/api/sessions/{sessionId}`

세션을 논리 삭제(`status=DELETED`)합니다. 삭제된 세션은 목록·조회에서 제외하고, 이후 턴·페이지 이동·제출 요청은 `SESSION_NOT_ACTIVE`로 거부합니다. 진행 중 턴과 충돌하면 `SESSION_STATE_CONFLICT`를 반환합니다.

### PATCH `/api/sessions/{sessionId}/page`

```json
{
  "pageNumber": 4
}
```

`data`:

```json
{
  "sessionId": 100,
  "currentPage": 4,
  "pageStatus": "NOT_EXPLAINED",
  "uiActions": [
    {
      "type": "BINARY_DECISION",
      "content": "현재 페이지를 설명할까요?",
      "yesEvent": "EXPLAIN_CURRENT_PAGE",
      "noEvent": "WAIT"
    }
  ]
}
```

페이지 범위 초과는 `PAGE_OUT_OF_RANGE`, 진행 중 턴과의 충돌은 `SESSION_STATE_CONFLICT`로 처리합니다.

### POST `/api/sessions/{sessionId}/turns`

공통 요청:

```json
{
  "requestId": "client-generated-id",
  "eventType": "USER_QUESTION",
  "payload": {
    "message": "편차가 정확히 무슨 뜻이야?"
  }
}
```

이벤트별 payload 초안:

| eventType | payload |
| --- | --- |
| `EXPLAIN_CURRENT_PAGE` | `{ "detailLevel": "NORMAL" }` |
| `USER_QUESTION` | `{ "message": "..." }` |
| `QUIZ_TYPE_SELECTED` | `{ "quizType": "MCQ" }` |
| `DIAGNOSIS_ANSWER_SUBMITTED` | `{ "diagnosisId": 30, "answer": "..." }` |

교정 후 추가 질문은 별도 이벤트 없이 `USER_QUESTION`을 재사용합니다. 직전 교정(repair)이 존재하면 Spring이 내부 턴 스냅샷의 `latestRepair`에 교정 답변 원문(또는 원문을 보존한 요약)을 포함해 전달하고, Orchestrator가 교정 후속 여부를 판단해 QaAgent를 선택합니다([에이전트 시스템 명세](agent-system-spec.md) §9.9 참고).

동일 `requestId` 재전송 처리(확정): **`TURN_ALREADY_PROCESSED`(409)로 거부**합니다. 기존 결과를 재반환하는 replay는 제공하지 않으며, FE는 409 수신 시 세션 상세·메시지 재조회로 최신 상태를 복원합니다(DEC-024 복원 체계 재사용). 스트리밍 재연결 요구가 생기면 기존 결과 반환 방식으로 확장을 재검토합니다(이후 개선안).

`data`:

```json
{
  "turnId": "turn-123",
  "sessionId": 100,
  "messages": [
    {
      "messageId": 501,
      "senderType": "AI",
      "messageType": "QA",
      "content": "편차는 어떤 값이 평균에서 얼마나 떨어져 있는지를 뜻합니다.",
      "pageNumber": 3,
      "createdAt": "2026-07-10T09:00:00Z"
    }
  ],
  "uiActions": [],
  "state": {
    "currentPage": 3,
    "pageStatus": "EXPLAINED"
  }
}
```

퀴즈 생성 턴(`QUIZ_TYPE_SELECTED`)의 응답에는 문항 본문을 싣지 않습니다. 대신 `state.activeQuizId`에 생성된 퀴즈의 `quizId`를 포함하고, FE는 `GET /api/quizzes/{quizId}`로 공개 문항을 조회해 풀이 UI를 엽니다. 저득점 진단에서 `uiActions`에 `diagnosisId`를 싣는 방식과 같은 참조 전달 원칙입니다.

### GET `/api/sessions/{sessionId}/messages`

커서 기반 페이지네이션을 사용합니다(DEC-024 부가 확정 — 채팅 무한 스크롤 패턴에 적합).

Query:

| 파라미터 | 필수 | 설명 |
| --- | :---: | --- |
| `cursor` | N | 이전 응답의 `nextCursor` 값. 없으면 최신 메시지부터 조회 |
| `size` | N | 기본 30, 최대 100 |

`data`:

```json
{
  "items": [
    {
      "messageId": 498,
      "senderType": "AI",
      "messageType": "EXPLANATION",
      "content": "...",
      "pageNumber": 3,
      "createdAt": "2026-07-10T09:00:00Z"
    }
  ],
  "nextCursor": "471",
  "hasMore": true
}
```

- 서버는 커서 기준 **더 과거 방향**으로 `size`개를 조회하고, `items`는 시간 오름차순으로 반환합니다(FE는 리스트 앞에 prepend). 첫 호출(커서 없음)은 최신 `size`개를 반환합니다.
- `nextCursor`는 다음(더 과거) 조회에 그대로 전달하는 불투명 문자열이며, 더 없으면 `null`·`hasMore=false`입니다. 구현은 `(created_at, id)` 복합 정렬 커서를 권장하되 커서 값의 내부 구조에 FE가 의존하지 않습니다.
- Base64 형식·내부 필드·시간·메시지 ID가 유효하지 않은 커서는 `VALIDATION_FAILED`(400)로 거부합니다.
- 삭제·완료된 세션도 소유자는 메시지를 조회할 수 있는지: 완료(COMPLETED)는 조회 허용, 삭제(DELETED)는 목록·조회와 동일하게 차단합니다.

### GET `/api/sessions/{sessionId}/quizzes`

퀴즈 제목, 유형, 범위, 제출 상태, 점수 요약을 반환합니다. 정답/루브릭은 포함하지 않습니다.

### POST `/api/sessions/{sessionId}/complete`

활성 세션을 완료 처리하고 최종 상태를 반환합니다. `COMPLETED → ACTIVE` 재개는 MVP에서 지원하지 않으며, 재학습은 새 세션 생성으로 처리합니다(DEC-024 부가 확정).

## 6. 퀴즈 API

### GET `/api/quizzes/{quizId}`

퀴즈의 공개 문항을 반환합니다. FE는 퀴즈 생성 턴 응답의 `state.activeQuizId`를 받아 이 API로 문항을 조회하며, 풀이 중 새로고침 후 재진입 시에도 같은 API로 문항을 복원합니다.

`data` 초안:

```json
{
  "quizId": 50,
  "sessionId": 100,
  "quizType": "MCQ",
  "title": "선형회귀 핵심 확인",
  "page": 3,
  "coverageStartPage": 1,
  "coverageEndPage": 3,
  "questionCount": 5,
  "questions": [],
  "submitted": false
}
```

- `questions`는 `public_question_json` 기반의 공개 필드만 포함합니다. 정답, 루브릭, 모범 답안(`private_answer_json`)은 절대 포함하지 않습니다.
- 권한은 퀴즈가 속한 세션의 소유자입니다. 소유권 위반은 `QUIZ_NOT_FOUND`로 은닉 처리합니다.
- 문항 수는 기본 5개, 5~10개 범위입니다(QUIZ-003). `questionCount`는 `questions` 배열 길이와 항상 일치합니다.

### 유형별 문항 스키마 (공개/비공개 분리 확정안)

문항 공통 필드(공개): `questionId`(퀴즈 내 유일, 예: "q1"), `questionText`, `maxScore`.

| 유형 | 공개 필드 (public_question_json → questions[]) | 비공개 필드 (private_answer_json — 채점·해설 전용) |
| --- | --- | --- |
| MCQ | 공통 + `options: [{ "optionId": "a", "text": "..." }]` (4지 기본) | `correctOptionId`, `explanation` |
| OX | 공통 (questionText가 진위 판별 문장) | `correctAnswer: boolean`, `explanation` |
| SHORT | 공통 | `referenceAnswer`, `acceptableKeywords: []`, `rubric` |
| ESSAY | 공통 | `modelAnswer`, `rubric: [{ "criterion": "...", "weight": 0.5 }]` — **weight 합계 = 1.0 검증**(DEC-002 D4, 위반 시 생성 실패 처리) |

- 필드명은 [에이전트 시스템 명세](agent-system-spec.md) §4.4 유형별 최소 필드와 일치시킵니다. AI가 생성한 JSON에서 Spring이 위 기준으로 공개/비공개를 분리 저장하며, 비공개 필드가 공개 측에 남아 있으면 저장을 거부합니다.
- 유형별 답안 형식(submit의 `answers[].answer`): MCQ = `optionId` 문자열, OX = `"true"`/`"false"`, SHORT/ESSAY = 자유 텍스트. 문항 누락·알 수 없는 questionId는 `INVALID_QUIZ_ANSWER`(400).
- 이 확정안은 BE·AI·FE 3자 리뷰 대상이며, 승인 후 AI 생성 JSON Schema(구조 검증용)의 기준이 됩니다.

### POST `/api/quizzes/{quizId}/submit`

```json
{
  "requestId": "client-generated-id",
  "answers": [
    {
      "questionId": "q1",
      "answer": "2"
    }
  ]
}
```

`data` 공통 초안:

```json
{
  "submissionId": 200,
  "quizId": 50,
  "quizType": "MCQ",
  "score": 80,
  "maxScore": 100,
  "passed": true,
  "gradingResult": {
    "items": [
      {
        "questionId": "q1",
        "score": 20,
        "maxScore": 20,
        "verdict": "CORRECT",
        "feedback": "핵심 개념을 정확히 이해했습니다."
      }
    ]
  },
  "uiActions": [
    {
      "type": "BINARY_DECISION",
      "content": "다음 페이지로 이동할까요?",
      "yesEvent": "MOVE_NEXT_PAGE",
      "noEvent": "WAIT"
    }
  ]
}
```

`passed`는 `score/maxScore >= 0.6`(설정 `EDUPILOT_QUIZ_PASS_RATIO` — DEC-010)로 계산합니다. 재제출은 1회 제한이며 재요청은 `QUIZ_ALREADY_SUBMITTED`로 거부합니다(DEC-009).

MVP의 제출 후 파이프라인은 동기 방식입니다. Spring은 제출·채점·기본 UI 액션을 먼저 커밋한 다음, 같은 HTTP 요청 안에서 `quiz-assessment`를 항상 호출하고 기준 미달일 때만 `diagnosis`를 호출합니다. 외부 AI 호출 중에는 DB 트랜잭션을 유지하지 않습니다. 파이프라인 실패와 무관하게 저장된 제출·채점은 유지하고 HTTP 200과 기본 `MOVE_NEXT_PAGE` 액션을 반환합니다. assessment 실패 시 diagnosis는 호출하지 않으며, diagnosis 실패 시 이미 저장된 assessment는 유지합니다. AI 호출 뒤 저장 시점에 세션이 `COMPLETED` 또는 `DELETED`로 전이되었다면 늦게 도착한 assessment·diagnosis와 pending 상태·UI 액션을 폐기합니다.

기준 미달 진단 응답의 UI 액션 계약은 다음과 같습니다. `yesEvent`·`noEvent` 같은 다른 액션용 nullable 필드는 노출하지 않습니다.

```json
{
  "type": "DIAGNOSIS_QUESTION",
  "content": "왜 역수를 곱하는지가 막혔나요?",
  "diagnosisId": 30
}
```

`uiActions`의 `MOVE_NEXT_PAGE`는 turns 이벤트가 아닙니다. FE는 이 액션 선택 시 `PATCH /api/sessions/{sessionId}/page`를 호출합니다(화면-API 매핑 §3 확정 규칙).

## 7. 학습자 메모리 API

### GET `/api/users/me/memory?materialId={materialId}`

학습자 메모리는 자료(material) 단위로 저장되므로(`learner_memories` `UK(user_id, material_id)`) `materialId` 쿼리 파라미터가 필수입니다. 해당 자료 스코프의 메모리 요약을 반환하며, 자료별 메모리가 없으면 빈 요약을 반환합니다.

`data` (확정안 — 공개 가능 요약만):

```json
{
  "materialId": 10,
  "strengths": ["평균 개념을 정확히 사용함"],
  "weaknesses": ["수식 전개 과정 설명"],
  "explanationPreferences": ["쉬운 예시 중심 설명 선호"],
  "preferredQuizTypes": ["MCQ"],
  "memoryDigest": "수식 전개를 어려워하고 쉬운 예시를 선호함",
  "updatedAt": "2026-07-10T09:00:00Z"
}
```

- 메모리가 없으면 각 배열은 빈 배열, `memoryDigest`·`updatedAt`은 `null`인 빈 요약을 반환합니다(404 아님).
- **비노출 필드(확정)**: `misconceptions`, `target_difficulty`, `next_coaching_goals`, confidence·`evidence_refs` 등 내부 근거와 시스템 판단 원문은 응답에 포함하지 않습니다 — 학습자 관점 요약(강점·약점·선호)과 digest만 공개합니다.
- 자료 범위를 넘어선 전역 프로필 제공 여부는 별도 결정 사항입니다(DEC-023 대안 검토 연계).

## 8. Spring → FastAPI 내부 API

### 호출 주체 원칙 (하이브리드)

- **자유 학습 턴**(질문, 설명 요청, 퀴즈 유형 선택, 진단 답변, 교정 후 질문): Spring은 어떤 AI 에이전트를 쓸지 판단하지 않고 `/internal/ai/turn` 단일 진입점으로 이벤트와 스냅샷을 전달합니다. 에이전트 선택은 FastAPI Orchestrator의 책임입니다. 오개념 교정(RepairAgent)과 메모리 후보 생성·승격도 turn 내부 도구로 실행합니다.
- **퀴즈 제출 후 결정적 파이프라인**: `QUIZ_SUBMITTED` 처리에서 Spring이 전용 내부 API를 순차 호출합니다 — [SHORT/ESSAY만] `grade` → `quiz-assessment` → [기준 점수 미달 시] `diagnosis`. 트리거가 이벤트 타입과 점수 기준으로 완전히 결정되므로 이는 판단이 아니라 규칙 실행이며, README의 "Spring은 에이전트를 판단하지 않는다" 원칙과 충돌하지 않습니다.
- 파이프라인 구간의 교수 정책(예: 통과한 퀴즈에 진단을 실행하지 않음)은 Orchestrator Policy가 아니라 Spring의 점수 기준 규칙이 구조적으로 보장합니다.

| Method | URL | 목적 | 호출 시점 |
| --- | --- | --- | --- |
| POST | `/internal/ai/extract` | PDF 페이지 텍스트 추출 (LLM 판단 없는 결정적 전처리 — DEC-006) | 자료 업로드 후 비동기 처리 |
| POST | `/internal/ai/turn` | 자유 학습 턴 계획·실행 (설명, QA, 퀴즈 생성, 교정, 메모리 후보·승격 포함) | turns 이벤트 수신 시 |
| POST | `/internal/ai/grade` | SHORT/ESSAY 채점 — 결정성 설정(temperature 최저 등)으로 동일 답안 재채점 편차를 최소화 | 퀴즈 제출 파이프라인 1단계 (SHORT/ESSAY만) |
| POST | `/internal/ai/quiz-assessment` | 퀴즈 내부 평가 생성 | 퀴즈 제출 파이프라인 2단계 (채점 완료 후 항상) |
| POST | `/internal/ai/diagnosis` | 진단 질문 생성 | 퀴즈 제출 파이프라인 3단계 (기준 점수 미달 시) |

`extract`는 멀티파트로 PDF 바이트를 받아 페이지별 텍스트 배열(`pages: [{ pageNumber, text }]`, `pageCount`)을 반환하며, 저장과 상태 전이는 Spring이 수행합니다. `diagnosis` 요청에는 직전 단계에서 생성된 `quizAssessment`, 오답 문항, 학생 답안, 강의 문맥을 포함합니다. 오개념 교정과 메모리 후보·승격의 전용 엔드포인트는 두지 않습니다 — 교정은 `DIAGNOSIS_ANSWER_SUBMITTED` 턴에서, 메모리는 Orchestrator의 `memoryWrite` 판단으로 turn 내부에서 실행합니다.

일반 턴 요청 최소 구조:

```json
{
  "schemaVersion": "1.0",
  "turnId": "turn-123",
  "session": {
    "sessionId": 100,
    "userId": 1,
    "materialId": 10,
    "currentPage": 3,
    "pageStatus": "NOT_EXPLAINED"
  },
  "event": {
    "eventType": "USER_QUESTION",
    "payload": {
      "message": "편차가 뭔지 모르겠어"
    }
  },
  "context": {
    "currentPageText": "...",
    "previousPageText": "...",
    "nextPageText": "...",
    "recentMessages": [],
    "qaThreadDigest": null,
    "quizAssessments": [],
    "learnerMemoryDigest": null,
    "learnerLevel": null,
    "learnerConfidence": null,
    "pendingDiagnosis": null,
    "latestRepair": null
  }
}
```

`learnerLevel`과 `learnerConfidence`는 별도 원천 컬럼 없이 Spring이 `learner_memories`(`target_difficulty`, 약점·강점)와 최근 `quiz_assessments`에서 파생해 전달하는 요약값입니다. 데이터가 없으면 `null`이며 에이전트는 기본 수준으로 동작합니다. 파생 규칙 초안은 [에이전트 시스템 명세](agent-system-spec.md) §4 입력 정의와 함께 확정합니다.

`quizAssessments`는 현재 세션 기준 최근 5개의 평가 요약입니다(DEC-011 — DB는 전량 보존, 스냅샷은 세션 스코프 윈도우. 메모리 승격 판단용 user×material 교차 세션 최근 20개 조회는 별도 경로).

`pendingDiagnosis`와 `latestRepair`는 진단·교정 흐름이 진행 중일 때 Spring이 채워 전달합니다. `latestRepair`에는 직전 교정 답변 원문(또는 원문을 보존한 요약)을 포함해, 교정 후 추가 질문(`USER_QUESTION`)에서 Orchestrator가 QaAgent에 교정 문맥을 넘길 수 있게 합니다.

응답 최소 구조:

```json
{
  "schemaVersion": "1.0",
  "turnId": "turn-123",
  "turnGoal": "ANSWER_USER_QUESTION",
  "actionsExecuted": [
    {
      "actionId": "action-1",
      "agent": "QaAgent",
      "status": "SUCCESS"
    }
  ],
  "messages": [
    {
      "messageType": "QA",
      "content": "편차는 평균에서 떨어진 정도입니다."
    }
  ],
  "statePatch": {},
  "uiActions": [],
  "memoryCandidates": []
}
```

`statePatch` 허용 목록 초안 — Spring은 아래 필드·값 외의 패치를 거부합니다(`domain-model.md` 상태 전이 표와 함께 검증):

| 필드 | 허용 값 | 비고 |
| --- | --- | --- |
| `pageStatus` | `EXPLAINING`, `EXPLAINED`, `QUIZ_READY`, `DIAGNOSIS_PENDING`, `REPAIR_COMPLETED` | `NOT_EXPLAINED`로의 역전이는 페이지 이동(StateReducer)만 가능 |
| `activeQuizId` | 생성된 퀴즈 ID 또는 `null` | 퀴즈 생성 턴에서 설정, 제출 완료 시 Spring이 해제 |
| `pendingDiagnosis` | 진단 참조 또는 `null` | 해제는 교정 완료 턴에서만 |
| `qaThread` | `{ "mode": "START_NEW" \| "FOLLOW_UP", "threadRef": ... }` | Orchestrator의 스레드 결정 반영 |

세션 `status` 전이(`ACTIVE`/`COMPLETED`/`DELETED`)는 statePatch로 허용하지 않으며 Spring 외부 API(complete/delete)로만 변경합니다. 목록의 세부 값은 구현 시 domain-model과 함께 확정합니다.

DTO 상세·타임아웃·재시도·`usage` 필드는 [docs/ai-integration-contract.md](ai-integration-contract.md) v0.4가 기준입니다(turn 요청/응답 구조, grade/quiz-assessment/diagnosis/extract DTO, 오류 category 5종 AUTH/TIMEOUT/SCHEMA/POLICY/INTERNAL과 Spring 매핑 포함).

내부 API 필수 정책:

- 외부에 공개하지 않습니다. FastAPI는 Docker 내부 네트워크에만 바인딩합니다.
- 서비스 간 인증: Spring이 모든 내부 호출에 `X-Internal-Token`(환경 변수 주입 시크릿) 헤더를 첨부하고 FastAPI가 검증합니다(DEC-014 Accepted).
- `schemaVersion`, `turnId`, timeout, 최대 payload 크기를 합의합니다.
- 알 수 없는 상태 패치나 UI 액션은 Spring이 거부합니다.
- FastAPI 오류 코드는 Spring 외부 오류로 안전하게 매핑합니다.

## 9. SSE 스트리밍 계약 (확정)

AI 응답 스트리밍은 SSE를 기본 전송 방식으로 사용합니다. 이벤트는 `status`, `thought_summary`, `content_delta`, `ui_action`, `completed`, `error`이며, `completed` 또는 `error`는 정확히 1회, 스트림의 마지막 이벤트입니다.

- 스트림 URL: `GET /api/sessions/{sessionId}/stream` (세션 단위 단일 스트림, 진행 중 턴의 이벤트를 전달)
- 인증: fetch + `Authorization: Bearer` 헤더 + ReadableStream 파싱(DEC-021). 브라우저 `EventSource`는 `Authorization` 헤더를 지원하지 않으므로 사용하지 않습니다.
- heartbeat: 10초 간격 SSE comment 라인(이벤트 아님, FE는 무시)
- 취소: 별도 취소 API 없음 — FE fetch abort → Spring 연결 종료 감지 → FastAPI 상류 요청 취소
- 재연결(MVP): `Last-Event-ID` 재전송 미지원 — 재연결 시 FE가 세션 상태/메시지 재조회로 재동기화하고, 진행 중 턴은 완료 후 확정 메시지로 수신합니다(중간 청크는 비확정이므로 유실 무해).
- 최종 저장: `completed` 수신·검증 후 1회만 확정 저장합니다. 스트림 중단 시 불완전 메시지는 확정 메시지로 취급하지 않습니다.
