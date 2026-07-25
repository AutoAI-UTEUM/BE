# API 명세서

| 항목 | 내용 |
| --- | --- |
| 상태 | 계약 초안 |
| 마지막 갱신 | 2026-07-23 |
| 외부 호출자 | Frontend |
| 내부 호출자 | Spring → FastAPI |

> 구현 이후에는 생성된 OpenAPI가 필드 수준 계약의 실행 가능한 기준입니다. 이 문서와 Swagger는 같은 PR에서 갱신합니다.

## 1. 공통 규칙

- 외부 API base path는 현재 `/api` 초안입니다. `/api/v1` 도입 여부는 TBD입니다.
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
| POST | `/api/auth/refresh` | 토큰 갱신 (refresh 쿠키 — DEC-004) | 쿠키 | 본인 |
| GET | `/api/users/me` | 내 정보 조회 | Y | 본인 |
| DELETE | `/api/users/me` | 회원 탈퇴 (비밀번호 재확인 — DEC-028) | Y | 본인 |
| POST | `/api/materials` | PDF 업로드 | Y | USER, ADMIN 초안 |
| GET | `/api/materials` | 자료 목록 | Y | 본인 소유 자료 (DEC-026) |
| GET | `/api/materials/{materialId}` | 자료 상세 | Y | 본인 소유 자료 |
| DELETE | `/api/materials/{materialId}` | 자료 논리 삭제 (활성 세션 시 409 — DEC-028) | Y | 본인 소유 자료 |
| GET | `/api/materials/{materialId}/pages/{pageNumber}` | 페이지 텍스트 | Y | 본인 소유 자료, 운영 노출 TBD(DEC-025) |
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

refresh token 필드는 정책 확정 후 추가합니다. 주요 오류: `INVALID_CREDENTIALS`, `USER_INACTIVE`.

## 4. 자료 API

### POST `/api/materials`

`Content-Type: multipart/form-data`

| part | 타입 | 필수 | 설명 |
| --- | --- | :---: | --- |
| `file` | PDF binary | Y | 제한값 TBD |
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

비동기 추출을 사용하면 최초 응답의 `pageCount`가 null이고 `processingStatus=PROCESSING`일 수 있습니다. `processingStatus`는 `PROCESSING`, `READY`, `FAILED` 최소 3값을 사용합니다. 이 동작은 구현 전 확정합니다.

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

자료 제목, 페이지 수, 처리 상태, 학습 가능 여부를 반환합니다. 원본 파일 접근 URL은 인증된 다운로드 방식 결정 후 스키마에 추가합니다.

### GET `/api/materials/{materialId}/pages/{pageNumber}`

페이지 번호와 추출 텍스트를 반환하는 디버깅/동기화 초안입니다. FE가 PDF 자체를 렌더링하는 데 이 API가 꼭 필요한지는 재검토합니다.

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
  "conversationSummary": "1~2페이지에서 평균과 편차를 설명함",
  "learnerMemoryDigest": "수식 전개를 어려워하고 쉬운 예시를 선호함",
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

`uiActions`는 마지막 턴/페이지 이동/퀴즈 제출 응답에서 내려간 최신 UI 액션을 그대로 반환해, 새로고침·재진입 후에도 진행 중이던 선택 UI를 복원할 수 있게 합니다. `activeQuizId`가 있으면 FE는 `GET /api/quizzes/{quizId}`로 풀이 화면을 복원합니다. `conversationSummary`·`learnerMemoryDigest`는 내부 AI 문맥 성격이 있어 FE 노출 필요성과 공개 범위를 구현 전에 재검토합니다(메모리 API의 "공개 가능한 요약만" 원칙과 정합 필요).

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

동일 `requestId` 재전송의 멱등성 보장 범위는 구현 전에 확정합니다.

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

커서 또는 페이지 기반 페이지네이션 중 하나를 구현 전에 선택합니다. 채팅에는 커서 방식이 권장되지만 현재 계약은 TBD입니다.

### GET `/api/sessions/{sessionId}/quizzes`

퀴즈 제목, 유형, 범위, 제출 상태, 점수 요약을 반환합니다. 정답/루브릭은 포함하지 않습니다.

### POST `/api/sessions/{sessionId}/complete`

활성 세션을 완료 처리하고 최종 상태를 반환합니다. 재개 정책은 TBD입니다.

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

저득점이면 `uiActions`에 `DIAGNOSIS_QUESTION`과 `diagnosisId`가 포함될 수 있습니다. 채점/Assessment/Diagnosis를 한 동기 요청에서 모두 완료할지 비동기 상태로 분리할지는 성능 검증 후 확정합니다.

`uiActions`의 `MOVE_NEXT_PAGE`는 turns 이벤트가 아닙니다. FE는 이 액션 선택 시 `PATCH /api/sessions/{sessionId}/page`를 호출합니다(화면-API 매핑 §3 확정 규칙).

## 7. 학습자 메모리 API

### GET `/api/users/me/memory?materialId={materialId}`

학습자 메모리는 자료(material) 단위로 저장되므로(`learner_memories` `UK(user_id, material_id)`) `materialId` 쿼리 파라미터가 필수입니다. 해당 자료 스코프의 메모리 요약을 반환하며, 자료별 메모리가 없으면 빈 요약을 반환합니다.

학습자에게 공개 가능한 요약만 반환합니다. 내부 근거 점수, 프롬프트, 시스템 판단 원문은 노출하지 않습니다. MVP 화면 필요성을 확인한 뒤 Must/Could 우선순위를 최종 결정합니다. 자료 범위를 넘어선 전역 프로필 제공 여부는 별도 결정 사항입니다.

## 8. Spring → FastAPI 내부 API

### 호출 주체 원칙 (하이브리드 — DEC-022)

- **자유 학습 턴**(질문, 설명 요청, 퀴즈 유형 선택, 진단 답변, 교정 후 질문): Spring은 어떤 AI 에이전트를 쓸지 판단하지 않고 `/internal/ai/turn` 단일 진입점으로 이벤트와 스냅샷을 전달합니다. 에이전트 선택은 FastAPI Orchestrator의 책임입니다. 오개념 교정(RepairAgent)과 메모리 후보 생성·승격도 turn 내부 도구로 실행합니다.
- **퀴즈 제출 후 결정적 파이프라인**: `QUIZ_SUBMITTED` 처리에서 Spring이 전용 내부 API를 순차 호출합니다 — [SHORT/ESSAY만] `grade` → `quiz-assessment` → [기준 점수 미달 시] `diagnosis`. 트리거가 이벤트 타입과 점수 기준으로 완전히 결정되므로 이는 판단이 아니라 규칙 실행이며, README의 "Spring은 에이전트를 판단하지 않는다" 원칙과 충돌하지 않습니다.
- 파이프라인 구간의 교수 정책(예: 통과한 퀴즈에 진단을 실행하지 않음)은 Orchestrator Policy가 아니라 Spring의 점수 기준 규칙이 구조적으로 보장합니다.

| Method | URL | 목적 | 호출 시점 |
| --- | --- | --- | --- |
| POST | `/internal/ai/extract` | PDF 페이지 텍스트 추출 (결정적 전처리 — DEC-006) | 자료 업로드 후 비동기 |
| POST | `/internal/ai/turn` | 자유 학습 턴 계획·실행 (설명, QA, 퀴즈 생성, 교정, 메모리 후보·승격 포함) | turns 이벤트 수신 시 |
| POST | `/internal/ai/grade` | SHORT/ESSAY 채점 | 퀴즈 제출 파이프라인 1단계 (SHORT/ESSAY만) |
| POST | `/internal/ai/quiz-assessment` | 퀴즈 내부 평가 생성 | 퀴즈 제출 파이프라인 2단계 (채점 완료 후 항상) |
| POST | `/internal/ai/diagnosis` | 진단 질문 생성 | 퀴즈 제출 파이프라인 3단계 (기준 점수 미달 시) |

`diagnosis` 요청에는 직전 단계에서 생성된 `quizAssessment`, 오답 문항, 학생 답안, 강의 문맥을 포함합니다. 오개념 교정과 메모리 후보·승격의 전용 엔드포인트는 두지 않습니다 — 교정은 `DIAGNOSIS_ANSWER_SUBMITTED` 턴에서, 메모리는 Orchestrator의 `memoryWrite` 판단으로 turn 내부에서 실행합니다.

`extract`는 multipart PDF(≤45MB)를 받아 `pageCount`와 `pages[]{pageNumber, text}`를 반환하는 결정적 전처리입니다(DEC-006). 결과 저장과 자료 상태 전이(`PROCESSING → READY | FAILED`)는 Spring 책임입니다.

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

`statePatch` 허용 목록 — Spring은 아래 필드·값 외의 패치를 거부합니다(`domain-model.md` 상태 전이 표와 함께 검증):

| 필드 | 허용 값 | 비고 |
| --- | --- | --- |
| `pageStatus` | `EXPLAINING`, `EXPLAINED`, `QUIZ_READY`, `DIAGNOSIS_PENDING`, `REPAIR_COMPLETED` | `NOT_EXPLAINED`로의 역전이는 페이지 이동(StateReducer)만 가능 |
| `activeQuizId` | 생성된 퀴즈 ID 또는 `null` | 퀴즈 생성 턴에서 설정, 제출 완료 시 Spring이 해제 |
| `pendingDiagnosis` | 진단 참조 또는 `null` | 해제는 교정 완료 턴에서만 |
| `qaThread` | `{ "mode": "START_NEW" \| "FOLLOW_UP", "threadRef": ... }` | Orchestrator의 스레드 결정 반영 |

세션 `status` 전이(`ACTIVE`/`COMPLETED`/`DELETED`)는 statePatch로 허용하지 않으며 Spring 외부 API(complete/delete)로만 변경합니다.

DTO 상세·타임아웃·재시도·`usage` 필드는 [docs/ai-integration-contract.md](ai-integration-contract.md) v0.4가 기준입니다(turn 요청/응답 구조, grade/quiz-assessment/diagnosis/extract DTO, 오류 category 5종 AUTH/TIMEOUT/SCHEMA/POLICY/INTERNAL과 Spring 매핑 포함).

내부 API 필수 정책:

- 외부에 공개하지 않습니다.
- 서비스 간 인증 방식은 TBD입니다.
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
