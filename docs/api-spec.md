# API 명세서

| 항목 | 내용 |
| --- | --- |
| 상태 | 계약 초안 |
| 마지막 갱신 | 2026-08-29 |
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
- 외부 `X-Trace-Id`는 영문·숫자로 시작하는 영문·숫자·`.`·`_`·`-` 조합의 최대 64자입니다. 없거나 형식·길이가 유효하지 않으면 서버가 UUID를 생성해 응답 헤더와 로그에 사용합니다.

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
| GET | `/api/auth/email-availability?email={email}` | 회원가입 이메일 중복 확인 | N | 전체 |
| POST | `/api/auth/login` | 로그인 | N | 전체 |
| POST | `/api/auth/google` | Google ID 토큰 로그인·가입 | N | 전체 |
| POST | `/api/auth/refresh` | access 재발급 (refresh 쿠키 회전) | 쿠키 | refresh 쿠키 보유자 |
| POST | `/api/auth/logout` | 로그아웃 (refresh 폐기·쿠키 만료) | 쿠키 | refresh 쿠키 보유자 (멱등) |
| GET | `/api/health/ready` | DB·AI Service readiness ([응답 계약](issues/11-observability.md)) | N | 전체 |
| GET | `/api/users/me` | 내 정보 조회 | Y | 본인 |
| PATCH | `/api/users/me` | 내 프로필 수정 | Y | 본인 |
| POST | `/api/users/me/avatar` | 내 아바타 업로드·교체 | Y | 본인 |
| GET | `/api/users/me/avatar` | 내 아바타 스트리밍 | Y | 본인 |
| DELETE | `/api/users/me/avatar` | 내 아바타 삭제 | Y | 본인 |
| GET | `/api/users/me/preferences` | 내 학습 환경설정 조회 | Y | 본인 |
| PATCH | `/api/users/me/preferences` | 내 학습 환경설정 수정 | Y | 본인 |
| GET | `/api/users/me/notifications` | 내 인앱 알림 목록 조회 | Y | 본인 |
| PATCH | `/api/users/me/notifications/{notificationId}/read` | 내 인앱 알림 읽음 처리 | Y | 본인 |
| DELETE | `/api/users/me/notifications/{notificationId}` | 내 인앱 알림 삭제 | Y | 본인 |
| DELETE | `/api/users/me` | 회원 탈퇴(논리 삭제+익명화 — DEC-028) | Y | 본인 (비밀번호 재확인) |
| POST | `/api/materials` | 개인 PDF 업로드 또는 강의실 주차 업로드 | Y | LEARNER, INSTRUCTOR, ADMIN; 강의실 part는 소유 INSTRUCTOR만 |
| GET | `/api/materials` | 자료 목록 | Y | 본인 소유 자료 (DEC-026) |
| GET | `/api/materials/{materialId}` | 자료 상세 | Y | 소유자 또는 승인 멤버의 강의실 연결 자료 |
| GET | `/api/materials/{materialId}/overview` | 자료 개요 조회 | Y | 소유자 또는 승인 멤버의 강의실 연결 자료 |
| POST | `/api/materials/{materialId}/doc-chat` | 자료 뷰어 문서 질문 | Y | 소유자 또는 승인 멤버의 READY 자료 |
| POST | `/api/materials/{materialId}/quiz-chat` | 제출 퀴즈 복습 질문 | Y | 소유자 또는 승인 멤버의 READY 자료이며 본인 제출 퀴즈가 존재해야 함 |
| GET | `/api/materials/{materialId}/file` | PDF 원본 스트리밍 | Y | 소유자 또는 승인 멤버의 강의실 연결 자료 |
| DELETE | `/api/materials/{materialId}` | 자료 논리 삭제 (DEC-028) | Y | 본인 소유 자료 |
| GET | `/api/materials/{materialId}/pages/{pageNumber}` | 페이지 텍스트 | Y | 접근 가능한 자료 — 운영 비노출, dev/디버깅 한정(DEC-025) |
| POST | `/api/sessions` | 학습 세션 생성 | Y | 본인 소유 또는 승인 멤버의 강의실 연결 자료 |
| GET | `/api/sessions` | 내 세션 목록 조회 | Y | 본인 |
| GET | `/api/sessions/{sessionId}` | 세션 상태 조회 | Y | 세션 소유자 |
| DELETE | `/api/sessions/{sessionId}` | 세션 논리 삭제 | Y | 세션 소유자 |
| PATCH | `/api/sessions/{sessionId}/page` | 페이지 이동 | Y | 세션 소유자 |
| POST | `/api/sessions/{sessionId}/quiz-decline` | 퀴즈 제안 거절 후 다음 학습 제안 | Y | ACTIVE 세션 소유자 |
| POST | `/api/sessions/{sessionId}/turns` | 학습 턴 처리 | Y | 세션 소유자 |
| POST | `/api/sessions/{sessionId}/conversations` | LLM 호출 없는 새 대화 시작 | Y | ACTIVE 세션 소유자 |
| GET | `/api/sessions/{sessionId}/stream` | SSE 스트림 (fetch + Bearer — DEC-021) | Y | 세션 소유자 |
| GET | `/api/sessions/{sessionId}/messages` | 메시지 조회 | Y | 세션 소유자 |
| GET | `/api/sessions/{sessionId}/quizzes` | 퀴즈 기록 조회 | Y | 세션 소유자 |
| GET | `/api/quizzes/{quizId}` | 퀴즈 공개 문항 조회 | Y | 세션 소유자 |
| GET | `/api/quizzes/{quizId}/submission` | 내 퀴즈 제출 결과 조회 | Y | 제출한 세션 소유자 |
| POST | `/api/quizzes/{quizId}/submit` | 퀴즈 제출 | Y | 세션 소유자 |
| POST | `/api/classrooms/{classroomId}/exams` | 별도 시험 DRAFT 생성 | Y | 소유 INSTRUCTOR |
| POST | `/api/classrooms/{classroomId}/exams/{examId}/draft-questions` | 자료 기반 AI 문항 초안 생성(무저장) | Y | 소유 INSTRUCTOR |
| GET | `/api/classrooms/{classroomId}/exams` | 역할별 시험 목록 조회 | Y | 소유 INSTRUCTOR 또는 승인 멤버 |
| GET | `/api/exams/{examId}` | 역할별 시험 상세 조회 | Y | 소유 INSTRUCTOR 또는 공개 시험의 승인 멤버 |
| PATCH | `/api/exams/{examId}` | DRAFT 시험 수정 | Y | 소유 INSTRUCTOR |
| POST | `/api/exams/{examId}/publish` | 시험 공개 | Y | 소유 INSTRUCTOR |
| POST | `/api/exams/{examId}/close` | 시험 마감 | Y | 소유 INSTRUCTOR |
| DELETE | `/api/exams/{examId}` | DRAFT 시험 삭제 | Y | 소유 INSTRUCTOR |
| POST | `/api/exams/{examId}/submissions` | 별도 시험 제출 | Y | 승인 멤버 |
| GET | `/api/exams/{examId}/submissions` | 시험별 최신 대표 제출 목록 | Y | 소유 INSTRUCTOR |
| GET | `/api/exams/{examId}/submissions/{submissionId}` | 특정 시험 제출 상세 | Y | 소유 INSTRUCTOR |
| POST | `/api/exams/{examId}/submissions/{submissionId}/regrade` | 실패한 시험 제출 재채점 | Y | 소유 INSTRUCTOR |
| GET | `/api/exams/{examId}/submissions/me` | 본인 시험 제출 결과 조회 | Y | 제출한 승인 멤버 |
| GET | `/api/users/me/memory?materialId={materialId}` | 학습자 메모리 조회(자료별) | Y | 본인 |
| POST | `/api/sessions/{sessionId}/complete` | 세션 종료 | Y | 세션 소유자 |
| POST | `/api/classrooms` | 강의실 개설 | Y | INSTRUCTOR |
| GET | `/api/classrooms` | 내 강의실 목록 | Y | 소유 또는 승인 멤버 관계 |
| GET | `/api/classrooms/{id}` | 강의실 상세 | Y | 소유 INSTRUCTOR 또는 승인 멤버 |
| GET | `/api/admin/users` | 관리자 회원 목록 조회 | Y | ADMIN + DB role/status 재검증 |
| GET | `/api/admin/users/{id}` | 관리자 회원 상세 조회 | Y | ADMIN + DB role/status 재검증 |
| GET | `/api/admin/classrooms` | 관리자 강의실 목록 조회 | Y | ADMIN + DB role/status 재검증 |
| GET | `/api/admin/classrooms/{id}` | 관리자 강의실 상세 조회 | Y | ADMIN + DB role/status 재검증 |
| GET | `/api/admin/ai-usage/summary` | 관리자 AI 사용량 일별·기능별 집계 | Y | ADMIN + DB role/status 재검증 |
| GET | `/api/admin/ai-usage/users` | 관리자 사용자별 AI 사용량 상위 집계 | Y | ADMIN + DB role/status 재검증 |
| GET | `/api/classrooms/{id}/analytics` | 강의자 학습 현황 집계 | Y | 소유 INSTRUCTOR |
| GET | `/api/classrooms/{classroomId}/students/{studentId}/learning-analytics` | 학습자별 상세 학습 현황 | Y | 소유 INSTRUCTOR |
| PATCH | `/api/classrooms/{id}` | 강의실 수정 | Y | 소유 INSTRUCTOR |
| DELETE | `/api/classrooms/{id}` | 강의실 COMPLETED 전환 | Y | 소유 INSTRUCTOR |
| DELETE | `/api/classrooms/{id}/permanent` | 강의실과 소속 운영 데이터 영구 삭제 | Y | 소유 INSTRUCTOR |
| GET | `/api/classrooms/{id}/invite-code` | 초대 코드 조회 | Y | 소유 INSTRUCTOR |
| POST | `/api/classrooms/{id}/invite-code/regenerate` | 초대 코드 재발급 | Y | 소유 INSTRUCTOR |
| POST | `/api/classroom-join-requests` | 초대 코드 참여 요청 | Y | LEARNER, INSTRUCTOR(타인 강의실) |
| GET | `/api/classroom-join-requests/me` | 내 참여 요청 목록 | Y | LEARNER, INSTRUCTOR |
| GET | `/api/classrooms/{id}/join-requests` | 강의실 참여 요청 목록 | Y | 소유 INSTRUCTOR |
| POST | `/api/classrooms/{id}/join-requests/{requestId}/approve` | 참여 요청 승인 | Y | 소유 INSTRUCTOR |
| POST | `/api/classrooms/{id}/join-requests/{requestId}/reject` | 참여 요청 거절 | Y | 소유 INSTRUCTOR |
| GET | `/api/classrooms/{id}/weeks` | 강의실 주차·자료 목록 | Y | 소유 INSTRUCTOR 또는 승인 멤버 |
| POST | `/api/classrooms/{id}/weeks` | 주차 생성 | Y | 소유 INSTRUCTOR |
| PATCH | `/api/classrooms/{id}/weeks/{weekNumber}` | 주차 수정 | Y | 소유 INSTRUCTOR |
| DELETE | `/api/classrooms/{id}/weeks/{weekNumber}` | 주차·자료 연결 삭제 | Y | 소유 INSTRUCTOR |
| POST | `/api/classrooms/{id}/weeks/{weekNumber}/materials/{materialId}` | 기존 자료 연결 | Y | 소유 INSTRUCTOR, 본인 소유 자료 |
| DELETE | `/api/classrooms/{id}/weeks/{weekNumber}/materials/{materialId}` | 자료 연결 해제 | Y | 소유 INSTRUCTOR |
| GET | `/api/classrooms/{id}/resources` | 강의실 일반 자료 목록 | Y | 소유 INSTRUCTOR 또는 승인 멤버 |
| POST | `/api/classrooms/{id}/resources` | 강의실 파일 자료 등록 (`multipart/form-data`) | Y | 소유 INSTRUCTOR |
| POST | `/api/classrooms/{id}/resources` | 강의실 링크 자료 등록 (`application/json`) | Y | 소유 INSTRUCTOR |
| PATCH | `/api/resources/{resourceId}` | 강의실 일반 자료 제목·주차 수정 | Y | 소유 INSTRUCTOR |
| GET | `/api/resources/{resourceId}/file` | 강의실 파일 자료 열기·다운로드 | Y | 소유 INSTRUCTOR 또는 승인 멤버 |
| DELETE | `/api/resources/{resourceId}` | 강의실 일반 자료 삭제 | Y | 소유 INSTRUCTOR |
| GET | `/api/classrooms/{id}/notices` | 공지 목록 | Y | 소유 INSTRUCTOR 또는 승인 멤버 |
| POST | `/api/classrooms/{id}/notices` | 공지 즉시·예약 게시 | Y | 소유 INSTRUCTOR |
| PATCH | `/api/classrooms/{id}/notices/{noticeId}` | 공지 수정 | Y | 소유 INSTRUCTOR |
| DELETE | `/api/classrooms/{id}/notices/{noticeId}` | 공지 삭제 | Y | 소유 INSTRUCTOR |
| GET | `/api/users/me/schedule` | 주차 공개·공지·개인 일정 통합 조회 | Y | 본인 개인 일정 + 소유·참여 강의실 범위 |
| POST | `/api/users/me/schedule` | 개인 일정 생성 | Y | 본인 |
| PATCH | `/api/users/me/schedule/{scheduleId}` | 개인 일정 부분 수정 | Y | 본인 소유, 타인·부재 404 |
| DELETE | `/api/users/me/schedule/{scheduleId}` | 개인 일정 삭제 | Y | 본인 소유, 타인·부재 404 |

## 3. 인증 API

### POST `/api/auth/signup`

```json
{
  "email": "user@example.com",
  "password": "password123",
  "name": "홍길동",
  "role": "LEARNER",
  "affiliation": "EduPilot University",
  "learningEmailOptIn": true,
  "termsVersion": "2026-07-01",
  "privacyVersion": "2026-07-01"
}
```

`data`:

```json
{
  "userId": 1,
  "email": "user@example.com",
  "name": "홍길동",
  "role": "LEARNER",
  "affiliation": "EduPilot University",
  "avatarUrl": null,
  "learningEmailOptIn": true
}
```

`role`은 필수이며 공개 가입에서는 `LEARNER | INSTRUCTOR`만 허용합니다. `ADMIN`, 기존 `USER`, 알 수 없는 enum 값은 요청 오류로 거부합니다. `ADMIN` 계정은 운영상 필요한 경우에만 DB에서 수동 설정합니다(DEC-017, DEC-029 Accepted).

`affiliation`은 선택이며 공백을 제거한 뒤 최대 100자입니다. `learningEmailOptIn`은 생략 시 `false`입니다. `termsVersion`과 `privacyVersion`은 하위 호환을 위해 둘 다 생략할 수 있지만 하나만 보낼 수는 없습니다. 현재 서버 허용값은 두 필드 모두 `2026-07-01`이며, 함께 전송하면 서버가 동의 시각을 기록합니다. 알 수 없는 버전과 부분 전송은 `VALIDATION_FAILED`입니다. FE와 운영 약관의 실제 버전 문자열은 배포 전 다시 확정해야 합니다.

비밀번호 정책(확정): **8~64자, 영문·숫자 각 1자 이상 포함**(특수문자 허용). 위반 시 `VALIDATION_FAILED` + `details: [{ "field": "password", "reason": "..." }]`.

주요 오류: `VALIDATION_FAILED`, `EMAIL_ALREADY_EXISTS`.

### GET `/api/auth/email-availability?email={email}`

회원가입과 동일하게 email을 trim·소문자 정규화하고 같은 중복 판정을 사용합니다.

`data`:

```json
{
  "available": true
}
```

- 활성 사용자가 해당 이메일을 점유하면 `available=false`입니다.
- 탈퇴 시 email이 `deleted_{id}`로 익명화되므로 원래 이메일은 `available=true`이며 재가입할 수 있습니다.
- email 누락·공백·형식 오류·255자 초과는 `VALIDATION_FAILED`(400)입니다.
- 계정 존재 여부 노출은 MVP에서 수용하며 rate limit은 후속 개선안으로 관리합니다.

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
    "role": "LEARNER",
    "affiliation": "EduPilot University",
    "avatarUrl": "/api/users/me/avatar",
    "learningEmailOptIn": true
  }
}
```

응답과 JWT `role` claim은 `LEARNER | INSTRUCTOR | ADMIN` 중 저장된 계정 역할을 반환합니다. `LEARNER`와 `INSTRUCTOR`는 현재 동일한 인증·소유권 규칙을 적용합니다.

refresh token은 응답 body에 포함하지 않고 쿠키로 발급합니다(DEC-004 Accepted). 쿠키 계약(확정): 이름 `edupilot_refresh`, `HttpOnly`, `Secure`, `SameSite=Lax`, **`Path=/api/auth`**(refresh·logout 요청에만 전송되도록 최소화), `Domain` 미설정(host-only), Max-Age 14일. 서버는 refresh 해시를 DB에 저장하고 회전·재사용 감지·강제 폐기를 지원합니다. access token 만료는 1시간이며 FE는 메모리에 보관합니다(localStorage 금지). 주요 오류: `INVALID_CREDENTIALS`, `USER_INACTIVE`.

### POST `/api/auth/google`

Google ID 토큰을 검증해 기존 계정으로 로그인하거나 신규 계정을 생성합니다. 인증과 refresh 쿠키는 `POST /api/auth/login`과 동일한 `LoginResponse`·쿠키 계약을 사용합니다.

기존 Google 계정 또는 검증된 이메일과 같은 로컬 계정은 추가 정보 없이 로그인할 수 있습니다.

```json
{
  "idToken": "google-id-token"
}
```

같은 이메일의 로컬 계정이 있으면 Google의 검증된 이메일 소유권을 근거로 `googleSub`를 자동 연동합니다. 이때 로컬 비밀번호 로그인은 계속 사용할 수 있으며 계정의 최초 생성 제공자는 `LOCAL`로 유지합니다. 이미 같은 `googleSub`가 연결돼 있으면 동일 사용자를 로그인 처리하며 중복 가입하지 않습니다.

검증된 이메일로 가입된 계정이 없고 다음 필수 추가 정보가 빠졌으면 `SIGNUP_REQUIRED`(409)를 반환합니다. FE는 추가 정보 폼을 표시하고 같은 `idToken`과 함께 다시 요청합니다.

```json
{
  "success": false,
  "error": {
    "code": "SIGNUP_REQUIRED",
    "message": "추가 정보 입력이 필요합니다.",
    "details": []
  },
  "traceId": "trace-id"
}
```

```json
{
  "idToken": "google-id-token",
  "role": "LEARNER",
  "termsVersion": "2026-07-01",
  "privacyVersion": "2026-07-01",
  "learningEmailOptIn": true,
  "affiliation": "EduPilot University"
}
```

- 신규 가입의 `role`은 `LEARNER | INSTRUCTOR`이며 `termsVersion`과 `privacyVersion`은 모두 필수입니다. 약관 검증·소속 정규화·이메일 수신 동의는 일반 회원가입과 같은 규칙을 사용합니다.
- Google ID 토큰은 서버가 Google tokeninfo 응답의 audience, issuer, 이메일 검증 여부를 확인합니다. 검증 실패·Google 통신 실패는 `TOKEN_INVALID`(401)로 통일합니다.
- 서버에 Google Client ID가 설정되지 않은 경우 기동은 허용하지만 요청은 `VALIDATION_FAILED`(400)로 거부하고 설정 오류만 서버 로그에 기록합니다.
- Google 최초 가입 계정의 비밀번호 sentinel은 일반 비밀번호 검증을 통과하지 않으므로 비밀번호 로그인은 `INVALID_CREDENTIALS`입니다.
- 주요 오류: `SIGNUP_REQUIRED`, `TOKEN_INVALID`, `USER_INACTIVE`, `VALIDATION_FAILED`.

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

### GET `/api/users/me`

login의 `user`와 같은 사용자 필드를 반환합니다. 기존 계정은 `affiliation`, `avatarUrl`이 `null`이고 `learningEmailOptIn`은 `false`입니다.

### PATCH `/api/users/me`

```json
{
  "name": "새 이름",
  "affiliation": "EduPilot University"
}
```

두 필드는 부분 수정입니다. `name`은 공백 제거 후 1~100자이고, `affiliation`은 생략하면 유지하며 빈 문자열이면 `null`로 해제합니다. 변경 필드가 없으면 `VALIDATION_FAILED`입니다. 성공 시 GET users/me와 같은 사용자 응답을 반환합니다.

### POST `/api/users/me/avatar`

`multipart/form-data`의 `file` part로 JPEG, PNG, WEBP 파일을 업로드합니다. Content-Type과 매직바이트가 일치해야 하며 최대 크기는 2MiB입니다. 성공 시 기존 파일을 교체하고 다음을 반환합니다.

```json
{
  "avatarUrl": "/api/users/me/avatar"
}
```

형식 위반은 `VALIDATION_FAILED`, 크기 초과는 기존 `FILE_TOO_LARGE`(413)를 사용합니다.

### GET `/api/users/me/avatar`

Bearer 인증 후 저장된 이미지의 실제 Media-Type으로 private/no-store inline 스트리밍합니다. 아바타가 없으면 `RESOURCE_NOT_FOUND`입니다. `avatarUrl`은 인증형 상대 경로이므로 일반 `<img src>`로 직접 호출할 수 없습니다. FE는 access token을 포함해 fetch한 Blob을 object URL로 표시하고 교체·화면 해제 시 `URL.revokeObjectURL`로 정리합니다.

### DELETE `/api/users/me/avatar`

저장 파일과 `avatar_key`를 함께 제거하며, 이미 없는 경우에도 성공하는 멱등 API입니다.

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
| `classroomId` | long | N | 강의실 주차 업로드 대상. `weekNumber`와 함께 있을 때만 유효 |
| `weekNumber` | integer | N | 연결할 주차. `classroomId`와 함께 있을 때만 유효 |

`data` 초안:

```json
{
  "materialId": 10,
  "title": "선형회귀 기초",
  "pageCount": 25,
  "processingStatus": "READY",
  "failureReason": null,
  "traceId": null,
  "createdAt": "2026-07-10T09:00:00Z"
}
```

업로드 직후 응답은 `processingStatus=PROCESSING`, `pageCount=null`, `failureReason=null`, `traceId=null`입니다. Spring이 백그라운드에서 내부 API `POST /internal/ai/extract`로 추출을 요청하고, 결과 저장 후 `READY`(실패 시 `FAILED`)로 전이합니다(DEC-006). `processingStatus`는 `PROCESSING`, `READY`, `FAILED` 3값을 사용합니다. FE는 자료 상세 재조회로 상태를 확인합니다. `PROCESSING`이 마지막 갱신 후 30분을 초과하면 자동으로 `FAILED`와 `failureReason=EXTRACTION_FAILED`로 전이하며 자동 재추출은 하지 않습니다.

`FAILED` 자료는 `failureReason`과 실패한 업로드 요청의 `traceId`를 목록·상세 응답에 반환합니다. `failureReason`은 `EXTRACTION_FAILED | PAGE_LIMIT_EXCEEDED | SCHEDULING_FAILED | UNSUPPORTED_FORMAT | ENCRYPTED_PDF | NO_TEXT_CONTENT | FILE_TOO_LARGE` 중 하나이며 자유 텍스트를 반환하지 않습니다. V23 이전에 실패한 자료는 원인을 복원할 수 없어 두 필드가 `null`일 수 있습니다. FE는 `failureReason=null`이면 일반 실패 문구를 표시합니다. `PROCESSING | READY` 자료에서는 두 필드가 항상 `null`입니다.

개인 업로드는 `LEARNER | INSTRUCTOR | ADMIN`이 사용할 수 있습니다. `ADMIN`은 기존 예약 역할 계약만 유지하며 강의실 기능은 제공하지 않습니다. `classroomId`와 `weekNumber`는 둘 다 생략하거나 둘 다 제공해야 하며, 강의실 업로드는 해당 강의실 소유 `INSTRUCTOR`만 가능합니다. 자료 행과 주차 연결은 한 DB 트랜잭션으로 저장하고 DB 저장 실패 시 이미 저장된 파일을 보상 삭제합니다.

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
      "failureReason": null,
      "traceId": null,
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

자료 제목, 페이지 수, 처리 상태, 학습 가능 여부와 실패 시 `failureReason`·`traceId`를 반환합니다. 실패 필드의 nullable·enum 규칙은 위 목록 응답과 같습니다. 소유자 또는 자료가 연결된 강의실의 승인 멤버에게 주차 상태와 관계없이 허용합니다. 강의실 자료는 전역 `GET /api/materials` 목록에는 포함하지 않고 강의실 주차 API에서 발견합니다.

### GET `/api/materials/{materialId}/overview`

자료 소유자 또는 자료가 연결된 강의실의 승인 멤버가 저장된 자료 개요를 조회합니다. 비접근·삭제·미존재 자료는 `MATERIAL_NOT_FOUND`(404)로 은닉합니다. `material_overviews` 행이 아직 없으면 404 대신 `PENDING` 합성 응답을 반환하며 `content`와 `updatedAt`은 `null`입니다. 행이 있으면 저장된 `status`와 `updatedAt`을 반환하되 `content`는 `READY`일 때만 반환하고 `PENDING | FAILED`에서는 `null`입니다. 조회 요청 자체는 AI를 호출하지 않습니다. 개요는 자료 추출 완료 후 비동기로 생성하며, 기존 READY 자료 중 개요 행이 없는 자료는 오래된 순으로 분당 최대 3건씩 백필합니다. 생성 실패는 자료 처리 상태와 무관하게 개요만 `FAILED`로 전환합니다.

```json
{
  "materialId": 10,
  "content": null,
  "status": "PENDING",
  "updatedAt": null
}
```

`status`는 `PENDING | READY | FAILED` 중 하나입니다.

### POST `/api/materials/{materialId}/doc-chat`

접근 가능한 READY 자료의 전체 페이지 텍스트와 캡션 병합본을 최대 10개 연속 페이지 문서로 조립해 문서 질문을 한 번 동기 처리합니다. 처리 중 자료는 `MATERIAL_PROCESSING`(409), 처리 실패 자료는 `MATERIAL_PROCESSING_FAILED`(409), 비접근·삭제·미존재 자료는 `MATERIAL_NOT_FOUND`(404)로 응답합니다. 요청 `history`는 최대 50개를 받되 AI에는 최근 10개만 전달하며 역할은 `USER | ASSISTANT`만 허용합니다. 질문은 공백이 아닌 최대 2,000자입니다.

### POST `/api/materials/{materialId}/quiz-chat`

접근 가능한 READY 자료 아래에서 요청 사용자가 제출한 퀴즈만 복습 문맥에 포함합니다. 문항·선지·정답·본인 답·해설과 해당 퀴즈 범위의 페이지 텍스트·캡션을 조립하며, 본인 제출이 없으면 `QUIZ_NOT_FOUND`(404)입니다. 요청과 응답 스키마는 자료 뷰어 문서 질문과 같습니다.

```json
{
  "question": "이 부분을 다시 설명해 주세요.",
  "history": [
    {"role": "USER", "content": "앞 질문"},
    {"role": "ASSISTANT", "content": "앞 답변"}
  ]
}
```

```json
{
  "answer": "자료 문맥을 바탕으로 한 답변입니다.",
  "warnings": [
    {"type": "CONTEXT_TRUNCATED", "message": "일부 문맥이 잘렸습니다."}
  ]
}
```

`CONTEXT_TRUNCATED` 경고는 그대로 전달하며 서버는 원문 없이 구조화된 INFO 로그만 남깁니다. 이 API는 스트리밍하지 않고 응답 완료까지 연결을 유지합니다.

문서·퀴즈 복습 질문은 사용자 직결 AI 쿼터를 호출 직전에 검사합니다. KST 기준 당일 호출 횟수가 역할별 한도에 도달하면 AI를 호출하지 않고 `AI_QUOTA_EXCEEDED`(429)를 반환하며, `ADMIN`은 면제됩니다.

### PATCH `/api/materials/{materialId}`

자료 소유자만 제목을 수정할 수 있습니다. 강의실 멤버의 열람 권한은 제목 수정 권한을 포함하지 않으며, 비소유자·삭제 자료·존재하지 않는 자료는 모두 `MATERIAL_NOT_FOUND`(404)로 은닉합니다. 제목은 앞뒤 공백을 제거한 뒤 비어 있지 않아야 하며 최대 255자입니다.

```json
{
  "title": "수정된 학습 자료 제목"
}
```

성공 응답의 `data`는 `GET /api/materials/{materialId}`와 같은 자료 상세 스키마입니다.

### GET `/api/materials/{materialId}/file`

Spring이 인증된 PDF 스트림을 반환합니다. 자료 상세와 같은 소유자·강의실 승인 멤버 권한을 적용하며, S3 전환 시 presigned URL 방식으로 변경합니다(DEC-005).

### DELETE `/api/materials/{materialId}`

자료를 논리 삭제(`status=DELETED`)합니다(DEC-028). 삭제된 자료는 목록·상세·세션 생성에서 제외합니다. 해당 자료의 ACTIVE 세션이 있으면 `MATERIAL_HAS_ACTIVE_SESSION`(409), 강의실 주차에 연결돼 있으면 `MATERIAL_LINKED_TO_CLASSROOM`(409)으로 거부합니다. 세션을 완료·삭제하고 모든 강의실 연결을 해제한 뒤 재시도합니다. 완료된 세션·퀴즈·평가 기록은 보존하며, storage 파일은 즉시 삭제하지 않습니다(물리 삭제 배치는 이후 개선안).

### GET `/api/materials/{materialId}/pages/{pageNumber}`

페이지 번호와 추출 텍스트를 반환합니다. 운영 FE에는 노출하지 않고 dev/디버깅 프로파일에서만 활성화합니다(DEC-025). 활성화된 환경에서는 자료 상세와 같은 접근 권한을 적용합니다. 추출 텍스트는 AI 문맥 전용이며 FE는 PDF 원본 뷰어를 사용합니다.

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

자료 소유자 또는 자료가 연결된 강의실의 승인 멤버만 세션을 생성할 수 있습니다. 주차 상태와 `releaseAt`은 접근 판정에 사용하지 않습니다. 세션은 `classroomId`를 저장하지 않으므로 동일 사용자의 동일 자료 ACTIVE 세션은 개인 학습과 여러 강의실에서 공유됩니다. 모든 강의실 연결 해제로 접근권을 잃으면 신규 세션 생성과 기존 세션의 추가 학습 턴은 차단하되 기존 세션·메시지·퀴즈 기록은 보존합니다.

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

`conversationSummary`는 비동기로 생성하는 내부 AI 스냅샷 전용 보조 문맥이며 세션 상세 응답에는 포함하지 않습니다. 최근 원문 메시지는 별도로 유지하고, 요약 생성 실패가 사용자 턴을 실패시키지 않습니다. `learnerMemoryDigest`도 **내부 AI 스냅샷 전용이며 세션 상세 응답에 포함하지 않습니다**(확정 — DEC-025의 내부 텍스트 비노출 원칙, 메모리 API의 "공개 가능한 요약만" 원칙과 정합). 학습자에게 보여줄 개인화 요약은 `GET /api/users/me/memory`가 담당합니다.

#### uiActions 위젯

AI Service의 `uiActions`는 기본적으로 빈 배열입니다. PDF가 첨부된
`EXPLAIN_CURRENT_PAGE`에서는 전체 자료 흐름을 본 Orchestrator가 exact
`BINARY_DECISION/SHOW_QUIZ_TYPE_SELECT/WAIT` 퀴즈 제안을 반환할 수 있고, Spring은
실제 설명 완료 전이에서만 `UiAction.quizProposal()` 정본으로 치환합니다. 예외적으로
`USER_QUESTION`의 `BINARY_DECISION/MOVE_NEXT_PAGE/WAIT` 제안은 Spring resolver
산출이 비어 있고 현재 페이지가 마지막이 아닐 때만 수용하며, AI 객체 대신
Spring `moveNextPage` 정본으로 치환해 저장·응답합니다. 그 외 제안은 무시합니다.
같은 턴의 `BINARY_DECISION/NOTE_REQUESTED/WAIT` 제안도 resolver 산출이 비어 있을
때만 수용합니다. 이때 Spring `UiAction` 정본으로 치환하되 표시용 `content`는 AI가
보낸 문구를 유지합니다. FE가 yes를 선택하면 빈 payload의 `NOTE_REQUESTED` 턴을
호출하고, 반환된 `noteDraft`를 편집한 뒤 기존 노트 생성 API로 저장합니다.
위젯은 Spring이 마지막 상태 전이에 따라 생성해 외부 응답에 포함합니다.

서버가 발급하는 위젯 스키마는 다음 2종입니다.

```json
{
  "type": "BINARY_DECISION",
  "content": "퀴즈를 진행할까요?",
  "yesEvent": "SHOW_QUIZ_TYPE_SELECT",
  "noEvent": "WAIT"
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
| W3 | xAI 첨부 자료의 현재 페이지 설명 완료 + Orchestrator의 exact 퀴즈 제안. file ID 없는 구자료는 기존 checkpoint·200자·section 규칙 fallback | `BINARY_DECISION("퀴즈를 진행할까요?", SHOW_QUIZ_TYPE_SELECT, WAIT)` |
| W4 | W3의 yes 선택 | FE가 로컬 `QUIZ_TYPE_SELECT`를 표시하고 선택값으로 `QUIZ_TYPE_SELECTED` 턴 호출 |
| W5 | 퀴즈 제출 파이프라인 완료 후 다음 학습 가능 | 마지막 페이지가 아니면 `BINARY_DECISION("다음 페이지로 이동할까요?", MOVE_NEXT_PAGE, WAIT)`. 마지막 페이지면 `BINARY_DECISION("학습을 완료할까요?", COMPLETE_SESSION, WAIT)`이며 yes 선택 시 FE가 `POST /api/sessions/{sessionId}/complete` 호출 |
| W6 | 기준 미달이고 진단 생성 성공 | `DIAGNOSIS_QUESTION(content, diagnosisId)` |
| W7 | 진단 답변의 교정 완료 | W5와 같은 다음 페이지/마지막 페이지 완료 분기 |

W3은 xAI file ID가 있는 자료에서 런타임 Orchestrator 판단을 정본으로 사용합니다.
현재 페이지가 큰 section의 중간이어도 독립적으로 점검 가능한 핵심 개념·가정·모델·
공식 해석·예제 단위를 도입하거나 완성하면 제안할 수 있으며 페이지 글자 수나 정적
checkpoint가 이를 차단하지 않습니다. 제안이 없으면 다음
학습 위젯을 생성합니다. file ID가 없는 구자료에 한해 기존 규칙으로 fallback합니다.
fallback의 페이지 텍스트 길이 임계값은
`edupilot.quiz.proposal-min-page-text-length`(기본 200자)이며, READY 완전 개요는
`sections[].endPage`에서만 제안하고 그 밖의 개요는 기존 200자 규칙을 사용합니다.

checkpoint 제안에서 사용자가 퀴즈 유형을 선택하면 Spring은 해당 coverage의 모든
페이지 캡션 병합 텍스트를 오름차순 `quizContext.pages`로 전달하고 전체 텍스트 합계를
앞에서부터 12,000자로 제한합니다. AI가 반환하는 coverage는 checkpoint 범위와
일치해야 하며, 저장되는 퀴즈의 `pageNumber`와 세션 `activeQuizId` 흐름은 기존처럼
현재 triggerPage에 귀속됩니다. checkpoint가 아닌 페이지는 현재 페이지 단일 출제
경로를 유지합니다(DEC-037).

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

### POST `/api/sessions/{sessionId}/quiz-decline`

요청 바디 없이 현재 저장된 퀴즈 제안을 거절하고 다음 학습 제안으로 교체합니다. AI turn은 호출하지 않습니다.

`data`:

```json
[
  {
    "type": "BINARY_DECISION",
    "content": "다음 페이지로 이동할까요?",
    "yesEvent": "MOVE_NEXT_PAGE",
    "noEvent": "WAIT"
  }
]
```

현재 페이지가 마지막 페이지이면 `MOVE_NEXT_PAGE` 대신 `COMPLETE_SESSION`을 제안합니다. 저장된 `uiActions`에 퀴즈 제안이 없으면 상태를 변경하지 않고 현행 배열을 그대로 반환하는 멱등 요청입니다. `pageStatus`와 `activeQuizId`는 변경하지 않습니다.

거절 후 `GET /api/sessions/{sessionId}`로 세션을 복원하면 교체 저장된 다음 학습 `uiActions`가 반환되며, 기존 `quizProposal`은 다시 노출되지 않습니다.

### POST `/api/sessions/{sessionId}/turns`

공통 요청:

```json
{
  "requestId": "client-generated-id",
  "eventType": "USER_QUESTION",
  "payload": {
    "message": "편차가 정확히 무슨 뜻이야?",
    "includeCurrentPage": true
  }
}
```

이벤트별 payload 초안:

| eventType | payload |
| --- | --- |
| `EXPLAIN_CURRENT_PAGE` | `{ "detailLevel": "NORMAL" }` |
| `USER_QUESTION` | `{ "message": "...", "includeCurrentPage": true\|false }` — `includeCurrentPage` 선택, 생략 시 `true` |
| `QUIZ_TYPE_SELECTED` | `{ "quizType": "MCQ" }` |
| `DIAGNOSIS_ANSWER_SUBMITTED` | `{ "diagnosisId": 30, "answer": "..." }` |
| `NOTE_REQUESTED` | `{}` — 필드가 없는 빈 객체만 허용 |

요청 payload는 위 표에 정의된 이벤트별 필드의 부분집합만 허용하며, 알 수 없는 필드가 있으면 `VALIDATION_FAILED`입니다. 생략된 선택 필드는 Spring이 기본값과 사용자 설정을 적용해 해석한 뒤 내부 AI 계약 형식으로 정규화해 전달합니다.

`NOTE_REQUESTED`는 노트 제안 위젯을 수락할 때 사용하는 무인자 이벤트입니다. 공개 요청의 `payload`는 빈 객체 `{}`로 보내며 Spring도 내부 AI 요청에 빈 payload 객체를 전달합니다. AI가 반환한 선택 `noteDraft`는 Spring에 저장하지 않고 응답으로만 전달하므로, FE가 사용자가 확정한 내용을 기존 노트 생성 API로 저장합니다.

교정 후 추가 질문은 별도 이벤트 없이 `USER_QUESTION`을 재사용합니다. 직전 교정(repair)이 존재하면 Spring이 내부 턴 스냅샷의 `latestRepair`에 교정 답변 원문(또는 원문을 보존한 요약)을 포함해 전달하고, Orchestrator가 교정 후속 여부를 판단해 QaAgent를 선택합니다([에이전트 시스템 명세](agent-system-spec.md) §9.9 참고).

`USER_QUESTION.payload.includeCurrentPage`는 boolean만 허용합니다. 생략하거나 `true`이면 현재·이전·다음 페이지 텍스트와 nullable `xaiFileId`를 내부 context에 포함합니다. `false`이면 Spring은 `xaiFileId`, `currentPageText`, `previousPageText`, `nextPageText` 네 필드의 값을 모두 null로 전달하되 그 외 context 필드는 유지합니다. 선택 필드인 `conversationSummary`도 페이지 첨부 여부와 독립적으로 전달할 수 있습니다. 이때 QaAgent는 일반 학습 지식으로 답변할 수 있지만 업로드 자료 내용을 추측하지 않고 학습과 무관한 요청에는 기존 한계 안내를 적용합니다. QA thread와 `latestRepair` 문맥은 플래그와 무관하게 승계합니다. 다른 eventType에 `includeCurrentPage`를 보내거나 boolean 외 값을 보내면 `VALIDATION_FAILED`입니다.

동일 `requestId` 재전송 처리(확정): 기존 사용자 메시지의 `status=FAILED`이면 해당 메시지를 `COMPLETED`로 복귀시켜 재사용하고 턴을 다시 수행합니다. 질문 행은 추가하지 않습니다. 기존 메시지가 성공 또는 진행 상태이면 **`TURN_ALREADY_PROCESSED`(409)**를 유지합니다. FE는 실패 턴의 통신 재시도에 새 ID를 만들지 않고 같은 `requestId`를 다시 사용합니다.

실제 AI turn 호출 직전에 사용자별 일일 쿼터를 검사합니다. KST 기준 당일 성공·실패 AI 호출을 모두 세며 역할별 한도에 도달하면 `AI_QUOTA_EXCEEDED`(429)를 반환하고 AI 요청은 전송하지 않습니다. `ADMIN`은 쿼터에서 면제됩니다.

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
      "status": "COMPLETED",
      "createdAt": "2026-07-10T09:00:00Z"
    }
  ],
  "uiActions": [],
  "state": {
    "currentPage": 3,
    "pageStatus": "EXPLAINED",
    "activeQuizId": null
  },
  "noteDraft": {
    "title": "분산과 편차 복습",
    "content": "## 핵심\n편차는 각 값과 평균의 차이입니다."
  }
}
```

`noteDraft`는 선택 필드입니다. 존재할 때 `title`은 공백이 아닌 60자 이하 문자열이고 `content`는 공백이 아닌 문자열입니다. `noteDraft`가 없는 기존 턴 응답에서는 필드가 생략됩니다. 초안 내용은 메시지·대화 요약·QA thread digest·서버 로그에 포함하지 않습니다.

퀴즈 생성 턴(`QUIZ_TYPE_SELECTED`)의 응답에는 문항 본문을 싣지 않습니다. 대신 `state.activeQuizId`에 생성된 퀴즈의 `quizId`를 포함하고, FE는 `GET /api/quizzes/{quizId}`로 공개 문항을 조회해 풀이 UI를 엽니다. 저득점 진단에서 `uiActions`에 `diagnosisId`를 싣는 방식과 같은 참조 전달 원칙입니다.

### POST `/api/sessions/{sessionId}/turns/cancel`

요청 body 없이 현재 SSE 연결에서 실행 중인 스트리밍 턴을 사용자 요청으로
취소합니다. 세션 소유자와 일치하는 실행 중 턴이 없으면 오류 대신 멱등하게
`cancelled:false`를 반환합니다. 비스트리밍 턴에는 영향을 주지 않습니다.

`data`:

```json
{
  "cancelled": true
}
```

- `cancelled:true`는 취소 신호가 현재 스트리밍 턴에 전달됐다는 뜻이며, 턴의
  저장·종료 결과는 SSE terminal 이벤트로 확인합니다.
- 이미 전달된 `content_delta`가 있으면 누적 텍스트만 `TEXT` 유형의 AI 메시지로
  저장합니다. state·`uiActions`·quiz·diagnosis·`noteDraft`·memory는 반영하지 않고,
  저장된 메시지와 현재 세션 상태로 `completed` 이벤트를 전송합니다. 이 경우
  사용자 메시지는 `COMPLETED` 상태를 유지하고 같은 `requestId` 재전송은
  `TURN_ALREADY_PROCESSED`입니다.
- 전달된 content가 없으면 AI 메시지를 저장하지 않고 사용자 메시지를 기존 실패
  경로의 `FAILED`로 표시한 뒤 `TURN_CANCELLED`(`retryable=false`) error 이벤트로
  종료합니다. 이 경우 같은 `requestId` 재시도는 실패 메시지를 재사용합니다.
- 브라우저 연결 해제·fetch abort 등 클라이언트 이탈 취소는 기존처럼 부분 답변을
  저장하지 않습니다.
- 취소된 `QUIZ_TYPE_SELECTED` 등 부가 산출물이 필요한 턴도 텍스트만 보존하며,
  퀴즈 생성이나 상태 전이는 수행하지 않습니다.

### POST `/api/sessions/{sessionId}/conversations`

현재 ACTIVE 세션에서 LLM 호출 없이 새 대화 경계를 시작합니다. 요청 body는 없습니다. 세션 행을 잠그고 5분 이내의 진행 중 턴이 있으면 `SESSION_STATE_CONFLICT`, 완료 세션이면 `SESSION_NOT_ACTIVE`, 타인·삭제·존재하지 않는 세션이면 `SESSION_NOT_FOUND`로 처리합니다. 5분이 지난 stale turn은 정리한 뒤 새 대화를 시작합니다.

`data`:

```json
{
  "conversationId": "conversation-1",
  "startedAt": "2026-08-02T05:30:00Z"
}
```

`conversationId`의 숫자는 세션별 새 대화 호출 횟수이며 첫 호출은 1부터 시작합니다. 호출 시각보다 **늦게 생성된** 메시지만 다음 내부 턴의 `recentMessages`에 포함하고, 마커 이전에 생성된 활성 QA thread의 `qaThreadDigest`와 교정 결과의 `latestRepair`는 null로 전달합니다. `pendingDiagnosis`, `memory.temporaryCandidates`, `quizAssessments`, `learnerMemoryDigest`, `xaiFileId`는 유지하고 `conversationSummary`는 null로 초기화합니다. 이 마커는 AI 문맥에만 적용되므로 아래 메시지 조회 API는 새 대화 전후의 전체 이력을 계속 반환합니다.

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
      "senderType": "USER",
      "messageType": "TEXT",
      "content": "편차가 정확히 무슨 뜻이야?",
      "pageNumber": 3,
      "status": "FAILED",
      "createdAt": "2026-07-10T09:00:00Z"
    }
  ],
  "nextCursor": "471",
  "hasMore": true
}
```

- 서버는 커서 기준 **더 과거 방향**으로 `size`개를 조회하고, `items`는 시간 오름차순으로 반환합니다(FE는 리스트 앞에 prepend). 첫 호출(커서 없음)은 최신 `size`개를 반환합니다.
- `status`는 `PENDING | COMPLETED | FAILED`입니다. 현재 턴 저장 경로의 정상 메시지는 `COMPLETED`이며 실패 턴의 사용자 메시지는 이력에는 `FAILED`로 남지만 다음 AI 스냅샷의 `recentMessages`와 `qaThreadDigest`에서는 제외합니다. FE는 `FAILED`를 전송 실패로 표시하고 같은 `requestId`로 재시도할 수 있습니다.
- `nextCursor`는 다음(더 과거) 조회에 그대로 전달하는 불투명 문자열이며, 더 없으면 `null`·`hasMore=false`입니다. 구현은 `(created_at, id)` 복합 정렬 커서를 권장하되 커서 값의 내부 구조에 FE가 의존하지 않습니다.
- Base64 형식·내부 필드·시간·메시지 ID가 유효하지 않은 커서는 `VALIDATION_FAILED`(400)로 거부합니다.
- 삭제·완료된 세션도 소유자는 메시지를 조회할 수 있는지: 완료(COMPLETED)는 조회 허용, 삭제(DELETED)는 목록·조회와 동일하게 차단합니다.

### GET `/api/sessions/{sessionId}/quizzes`

퀴즈 제목, 유형, 범위, 제출 상태, 점수 요약을 반환합니다. 정답/루브릭은 포함하지 않습니다.

### POST `/api/sessions/{sessionId}/complete`

활성 세션을 완료 처리하고 최종 상태를 반환합니다. `COMPLETED → ACTIVE` 재개는 MVP에서 지원하지 않으며, 재학습은 새 세션 생성으로 처리합니다(DEC-024 부가 확정).

## 5.1 학습 노트 API

노트는 사용자와 자료에 귀속하고 세션·페이지·채팅 메시지는 선택 참조입니다. 자료 소유자뿐 아니라 해당 자료가 연결된 강의실의 멤버도 노트를 생성하고 조회할 수 있으며, 목록에는 항상 현재 사용자가 작성한 노트만 포함됩니다. 모든 API는 Bearer 인증이 필요합니다. 목록은 `createdAt DESC, noteId DESC`로 정렬하며 기본 `page=0`, `size=50`, 최대 `size=100`을 사용합니다. 논리 삭제된 자료의 노트는 목록에서 제외합니다.

공통 노트 응답:

```json
{
  "noteId": 1000,
  "sessionId": 100,
  "materialId": 10,
  "content": "인과관계와 상관관계의 차이",
  "pageNumber": 3,
  "sourceMessageId": 501,
  "createdAt": "2026-08-02T00:00:00Z",
  "updatedAt": "2026-08-02T00:00:00Z"
}
```

### POST `/api/sessions/{sessionId}/notes`

```json
{
  "content": "인과관계와 상관관계의 차이",
  "pageNumber": 3,
  "sourceMessageId": 501
}
```

`content`는 비공백·10,000자 이하입니다. `pageNumber`는 해당 자료의 페이지 범위여야 하고, `sourceMessageId`는 요청 경로의 세션에 속한 채팅 메시지여야 합니다. `pageNumber`와 `sourceMessageId`는 생략할 수 있습니다. 다른 사용자의 세션 또는 삭제된 세션은 `SESSION_NOT_FOUND`, 삭제된 자료는 `MATERIAL_NOT_FOUND`로 처리합니다.

### GET `/api/materials/{materialId}/notes?page=0&size=50`

자료에 속한 현재 사용자의 노트를 페이지 응답으로 반환합니다.

```json
{
  "items": [
    {
      "noteId": 1000,
      "sessionId": 100,
      "materialId": 10,
      "content": "인과관계와 상관관계의 차이",
      "pageNumber": 3,
      "sourceMessageId": 501,
      "createdAt": "2026-08-02T00:00:00Z",
      "updatedAt": "2026-08-02T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 1,
  "totalPages": 1
}
```

### GET `/api/sessions/{sessionId}/notes?page=0&size=50`

세션의 자료 ID를 해석한 뒤 위 자료 목록과 같은 서비스 메서드를 사용합니다. 따라서 같은 `page`·`size` 조건이면 두 경로의 결과는 동일한 자료 스코프입니다. 세션에서 직접 생성한 노트만으로 제한하지 않습니다.

### PATCH `/api/notes/{noteId}`

```json
{
  "content": "수정된 노트 내용"
}
```

본인 노트만 수정할 수 있습니다. 존재하지 않거나 다른 사용자의 노트는 모두 `NOTE_NOT_FOUND`(404)로 은닉합니다.

### DELETE `/api/notes/{noteId}`

본인 노트를 물리 삭제합니다. 존재하지 않거나 다른 사용자의 노트는 모두 `NOTE_NOT_FOUND`(404)로 은닉합니다.

## 6. 퀴즈·시험 API

### 6.1 통합 학습 퀴즈

#### GET `/api/quizzes/{quizId}`

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

#### 유형별 문항 스키마 (공개/비공개 분리 확정안)

외부 `questions[]`의 문항 공통 필드(공개)는 `questionId`(퀴즈 내 유일,
예: "q1"), `questionText`, `maxScore`입니다. 내부 turn 응답과
`public_question_json`의 정본 필드 `points`는 이 DTO 경계에서 `maxScore`로
매핑합니다.

| 유형 | 외부 공개 필드 (`public_question_json`에서 DTO 변환) | 비공개 저장 필드 (`private_answer_json` — 채점·해설 전용) |
| --- | --- | --- |
| MCQ | 공통 + `options: [{ "optionId": "a", "text": "..." }]` (`public_question_json`의 `choices[].choiceId`를 매핑, 4지 기본) | `answerChoiceId`, `explanation` |
| OX | 공통 (questionText가 진위 판별 문장) | `answerValue: boolean`, `explanation` |
| SHORT | 공통 | `referenceAnswer`, `gradingCriteria: []` |
| ESSAY | 공통 | `modelAnswer`, `rubric: [{ "criterion": "...", "weight": 0.5 }]` — **weight 합계 = 1.0 검증**(DEC-002 D4, 위반 시 생성 실패 처리) |

- 내부 생성·저장 필드명은 [AI 연동 계약](ai-integration-contract.md) v0.6
  §6.2를 따릅니다. Spring은 AI가 생성한 JSON을 공개/비공개로 분리 저장하고,
  외부 GET DTO에서만 `points/choices/choiceId`를
  `maxScore/options/optionId`로 변환합니다. 비공개 필드는 외부 DTO에 매핑하지
  않습니다.
- 유형별 답안 형식(submit의 `answers[].answer`): MCQ = `optionId` 문자열, OX = `"true"`/`"false"`, SHORT/ESSAY = 자유 텍스트. 문항 누락·알 수 없는 questionId는 `INVALID_QUIZ_ANSWER`(400).
- 이 확정안은 BE·AI·FE 3자 리뷰 대상이며, 승인 후 AI 생성 JSON Schema(구조 검증용)의 기준이 됩니다.

#### GET `/api/quizzes/{quizId}/submission`

본인이 제출한 퀴즈 결과를 조회합니다. 제출 완료 후에만 정답과 해설을
노출하며, 미제출 퀴즈·존재하지 않는 퀴즈·다른 사용자의 퀴즈는 모두
`QUIZ_NOT_FOUND`(404)로 은닉합니다.

```json
{
  "quizId": 50,
  "submissionId": 200,
  "submittedAt": "2026-08-12T10:00:00Z",
  "score": 10,
  "maxScore": 20,
  "passed": false,
  "items": [
    {
      "questionId": "q1",
      "submittedAnswer": "a",
      "correctAnswer": "b",
      "verdict": "PARTIAL",
      "score": 10,
      "maxScore": 20,
      "feedback": "개념은 맞지만 선택이 부정확합니다.",
      "explanation": "b가 정의에 맞습니다."
    }
  ]
}
```

`correctAnswer`는 유형과 관계없이 문자열이며 MCQ의 `answerChoiceId`, OX의
`"true"`/`"false"`, SHORT의 `referenceAnswer`, ESSAY의 `modelAnswer`를
퀴즈 문항 정본에서 조립합니다. `explanation`은 정본에 해설이 있는 MCQ/OX만
문자열이고 SHORT/ESSAY는 `null`입니다. `verdict`는
`CORRECT | PARTIAL | WRONG` 중 하나입니다.

#### POST `/api/quizzes/{quizId}/submit`

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

`passed`는 `score/maxScore >= 0.6`(설정 `EDUPILOT_QUIZ_PASS_RATIO` — DEC-010)로 계산합니다. 동일한 `(quizId, userId, requestId)` 재전송은 저장된 제출·채점 결과와 현재 복원 가능한 UI 액션을 재구성해 HTTP 200으로 반환합니다. 다른 `requestId`로 재제출하면 `QUIZ_ALREADY_SUBMITTED`로 거부합니다(DEC-009).

제출하려는 `quizId`는 세션의 현재 `activeQuizId`와 일치해야 하며, 불일치하거나 활성 퀴즈가 없으면 `SESSION_STATE_CONFLICT`(409)로 거부합니다. 페이지 이동 후에도 활성 퀴즈는 제출할 수 있지만 퀴즈의 생성 페이지가 현재 페이지와 다르면 제출·채점 결과만 저장하고 현재 페이지의 `pageStatus`·`uiActions`와 진단 흐름은 변경하지 않습니다.

제출은 기존 세션 turn claim을 획득한 뒤 채점·평가·진단 파이프라인을 수행하고 성공·실패와 무관하게 마지막에 claim을 해제합니다. claim이 유지되는 동안 페이지 이동·turn·중복 제출은 `SESSION_STATE_CONFLICT`(409)로 차단되며, 동시 제출 중 claim을 획득한 한 요청만 AI 채점을 호출합니다.

MVP의 제출 후 파이프라인은 동기 방식입니다. Spring은 제출·채점·기본 UI 액션을 먼저 커밋한 다음, 같은 HTTP 요청 안에서 `quiz-assessment`를 항상 호출하고 기준 미달일 때만 `diagnosis`를 호출합니다. 두 사용자 직결 AI 호출은 각각 호출 직전에 일일 쿼터를 검사하며 한도 도달 시 `AI_QUOTA_EXCEEDED`(429)를 반환합니다. 외부 AI 호출 중에는 DB 트랜잭션을 유지하지 않습니다. 쿼터 초과를 제외한 파이프라인 실패와 무관하게 저장된 제출·채점은 유지하고 HTTP 200과 기본 `MOVE_NEXT_PAGE` 액션을 반환합니다. assessment 실패 시 diagnosis는 호출하지 않으며, diagnosis 실패 시 이미 저장된 assessment는 유지합니다. 기준 미달이지만 assessment의 `wrongItems`가 비어 있으면 SCHEMA 422를 피하기 위해 diagnosis 호출을 생략하고 기본 UI 액션을 반환하며 서버에 warn 로그를 남깁니다. AI 호출 뒤 저장 시점에 세션이 `COMPLETED` 또는 `DELETED`로 전이되었다면 늦게 도착한 assessment·diagnosis와 pending 상태·UI 액션을 폐기합니다.

`pageStatus=DIAGNOSIS_PENDING`인 세션은 새 `QUIZ_TYPE_SELECTED` turn을 `SESSION_STATE_CONFLICT`(409)로 거부합니다. 진단 답변은 진단이 생성된 퀴즈 페이지와 현재 페이지가 달라도 교정 결과 저장과 진단 완료 처리를 유지하지만, 현재 페이지의 `pageStatus`와 `uiActions`는 변경하지 않습니다.

기준 미달 진단 응답의 UI 액션 계약은 다음과 같습니다. `yesEvent`·`noEvent` 같은 다른 액션용 nullable 필드는 노출하지 않습니다.

```json
{
  "type": "DIAGNOSIS_QUESTION",
  "content": "왜 역수를 곱하는지가 막혔나요?",
  "diagnosisId": 30
}
```

`uiActions`의 `MOVE_NEXT_PAGE`는 turns 이벤트가 아닙니다. FE는 이 액션 선택 시 `PATCH /api/sessions/{sessionId}/page`를 호출합니다(화면-API 매핑 §3 확정 규칙).

### 6.2 별도 시험

별도 시험 계약은 DEC-031을 따릅니다. 시험은 강의실에 귀속하며 강사가 직접 출제합니다. 페이지네이션은 `page=0`, `size=20`, 최대 100입니다.

#### 권한·노출 기준

- 소유 `INSTRUCTOR`는 본인 강의실의 DRAFT·PUBLISHED·CLOSED 시험을 관리하고 정답·모범 답안·rubric을 포함한 강사 뷰를 조회합니다. 역할 부족은 `ACCESS_DENIED`(403), 다른 강사 소유권은 `CLASSROOM_NOT_FOUND`(404)로 처리합니다.
- 승인 멤버는 PUBLISHED·CLOSED 시험만 목록·상세 조회할 수 있습니다. DRAFT 시험은 상세와 제출 경로에서도 `EXAM_NOT_FOUND`(404)로 은닉합니다.
- 시험 노출은 `exams.status`만으로 결정합니다. `weekNumber`는 표시·집계 라벨이며 주차 공개 상태에 종속되지 않습니다.
- 완료 강의실은 시험 생성·수정·공개·학생 제출을 `CLASSROOM_COMPLETED`(409)로 차단합니다. 기존 PUBLISHED 시험 close와 DRAFT 시험 삭제는 정리 작업으로 허용합니다.

#### 강사 API

| Method | URL | 계약 |
| --- | --- | --- |
| POST | `/api/classrooms/{classroomId}/exams` | DRAFT 생성. 문항 배열은 비어 있을 수 있습니다. |
| POST | `/api/classrooms/{classroomId}/exams/{examId}/draft-questions` | DRAFT 시험의 AI 문항 초안을 생성해 응답으로만 반환합니다. |
| GET | `/api/classrooms/{classroomId}/exams?status&page&size` | 소유 강사는 전 상태와 제출자 수를 조회합니다. |
| GET | `/api/exams/{examId}` | 소유 강사 뷰는 정답·모범 답안·rubric을 포함합니다. |
| PATCH | `/api/exams/{examId}` | DRAFT만 수정. 필드 생략은 유지, `questions` 전달 시 전체 교체입니다. |
| POST | `/api/exams/{examId}/publish` | DRAFT→PUBLISHED. PUBLISHED에서는 멱등입니다. |
| POST | `/api/exams/{examId}/close` | PUBLISHED→CLOSED. CLOSED에서는 멱등입니다. |
| DELETE | `/api/exams/{examId}` | DRAFT만 물리 삭제합니다. |
| GET | `/api/exams/{examId}/submissions?page&size` | 학생별 최신 대표 제출과 attempt 수를 조회합니다. |
| GET | `/api/exams/{examId}/submissions/{submissionId}` | 특정 제출의 답안·점수·판정·피드백을 조회합니다. |
| POST | `/api/exams/{examId}/submissions/{submissionId}/regrade` | `GRADING_FAILED` 제출의 저장 답안을 재채점 큐에 등록하고 `SUBMITTED`/202를 반환합니다. |

생성·수정 요청의 공통 형태:

```json
{
  "title": "중간 점검 시험",
  "description": "1~4주차 핵심 개념",
  "weekNumber": 4,
  "allowRetake": false,
  "questions": [
    {
      "questionType": "SHORT",
      "questionText": "표준편차의 의미를 설명하세요.",
      "points": 20,
      "referenceAnswer": "자료가 평균에서 퍼진 정도를 나타낸다.",
      "rubric": []
    }
  ]
}
```

- `title`은 공백이 아닌 최대 200자, `description`은 nullable 최대 500자입니다. `weekNumber`는 nullable이며 값이 있으면 `1 <= weekNumber <= weekCount`입니다.
- DRAFT 저장에서는 문항 0개와 불완전한 rubric weight 합을 허용합니다. publish 시 문항 1개 이상, `totalScore > 0`, 유형별 정답·모범 답안 비공백, 입력된 rubric의 weight 합 1.0을 검증합니다.
- rubric 키 생략, null, 빈 배열은 모두 미입력입니다. 미입력 SHORT/ESSAY는 grade 호출 시 서버가 `[{"criterion":"모범 답안 부합도","weight":1.0}]`을 주입합니다.
- publish를 CLOSED에서 호출하거나 공개 이후 수정·삭제하면 `EXAM_NOT_EDITABLE`(409)입니다. close를 DRAFT에서 호출하면 `EXAM_NOT_PUBLISHED`(409)입니다.
- 재채점은 소유 강사만 호출할 수 있습니다. 다른 강사 소유·존재하지 않는 제출은 404로 은닉하고, `GRADING_FAILED`가 아닌 제출은 `EXAM_ALREADY_SUBMITTED`(409)로 거부합니다. 요청 body는 없으며 저장된 답안을 그대로 사용하고 `gradingRetryCount`를 0으로 초기화합니다. executor 포화는 429로 바꾸지 않고 202를 유지하며 scheduler가 회수합니다.

#### AI 문항 초안 (강사 보조 동선)

`POST /api/classrooms/{classroomId}/exams/{examId}/draft-questions`는 소유 강사의 DRAFT 시험에서만 사용할 수 있습니다. 초안은 `exam_questions`에 저장되지 않으며, 강사가 검토·수정한 뒤 기존 시험 수정 API의 `questions`로 저장합니다.

```json
{
  "weekNumber": 4,
  "materialIds": [101, 102],
  "questionPlan": [
    { "questionType": "MCQ", "count": 3 },
    { "questionType": "SHORT", "count": 2 }
  ]
}
```

- `weekNumber`와 `materialIds`가 모두 null이면 강의실 전체의 `ACTIVE`·`READY` 연결 자료를 사용합니다. 둘 다 값이 있으면 해당 주차와 명시 자료의 교집합을 사용합니다.
- `materialIds`는 해당 강의실 연결 자료만 허용하며 다른 강의실 자료는 `MATERIAL_NOT_FOUND`(404)로 은닉합니다.
- `questionPlan`은 `MCQ | OX | SHORT | ESSAY`별 항목을 최대 한 번만 포함하며 총 문항 수는 1~20입니다.
- 자료 ID, 원본 페이지 번호 순으로 비어 있지 않은 텍스트를 수집합니다. 여러 자료의 원본 페이지 번호가 중복될 수 있으므로 AI 계약의 `pageNumber`는 선별된 컨텍스트 순번 1..N으로 재부여합니다. 30페이지를 초과하면 앞의 30개만 보내고 `truncated=true`를 반환합니다. 텍스트가 한 페이지도 없으면 400입니다.

```json
{
  "success": true,
  "data": {
    "schemaVersion": "1.0",
    "examId": 77,
    "questions": [
      {
        "questionType": "MCQ",
        "sourcePageNumber": 1,
        "questionId": "mcq-1",
        "questionText": "다음 중 핵심 개념에 맞는 것은?",
        "points": 5,
        "choices": [
          { "choiceId": "a", "text": "선택지 A" },
          { "choiceId": "b", "text": "선택지 B" }
        ],
        "answerChoiceId": "a",
        "explanation": "자료의 정의와 일치합니다."
      }
    ],
    "usage": {
      "model": "grok-4",
      "inputTokens": 1200,
      "outputTokens": 350,
      "reasoningTokens": null
    },
    "truncated": false
  },
  "error": null
}
```

응답 문항은 `questionType` discriminator에 따라 기존 QuizAgent의 MCQ·OX·SHORT·ESSAY 정답/해설 스키마를 그대로 사용합니다. Spring은 계획별 개수, MCQ 정답 choice, ESSAY rubric 합 1.0, `sourcePageNumber` 범위를 재검증하며 위반 시 `AI_RESPONSE_INVALID`(502)입니다. 비소유 강사는 `CLASSROOM_NOT_FOUND`(404), DRAFT가 아닌 시험은 `EXAM_NOT_EDITABLE`(409), 역할 부족은 `ACCESS_DENIED`(403)입니다.

#### 학생 API

| Method | URL | 계약 |
| --- | --- | --- |
| GET | `/api/classrooms/{classroomId}/exams?page&size` | PUBLISHED·CLOSED 목록과 본인 최신 제출 요약·`submittable`을 반환합니다. GRADING_FAILED 최신 시도는 재제출 가능으로 계산합니다. |
| GET | `/api/exams/{examId}` | 공개 문항과 `submittable`만 반환합니다. |
| POST | `/api/exams/{examId}/submissions` | PUBLISHED 시험을 제출합니다. 주관식 AI 채점이 필요하면 `SUBMITTED`/202, 아니면 `GRADED`/200입니다. 두 응답은 같은 봉투와 `ExamSubmissionResponse` 스키마입니다. |
| GET | `/api/exams/{examId}/submissions/me?attemptNo=` | 본인 결과를 조회하며 attemptNo 생략 시 최신 시도입니다. |

학생 문항 DTO는 `questionId`, `questionText`, `maxScore`, `questionType`, `options`만 포함합니다. 정답·해설·모범 답안·rubric은 DEC-031 D4 확정 전까지 제출 후에도 반환하지 않습니다.

제출 요청:

```json
{
  "requestId": "01K2...",
  "answers": [
    {"questionId": "q1", "answer": "a"},
    {"questionId": "q2", "answer": "자유 서술 답안"}
  ]
}
```

- 답안 형식은 MCQ=`optionId`, OX=`"true"|"false"`, SHORT/ESSAY=자유 텍스트입니다.
- 누락 questionId는 미응답으로 처리합니다. 알 수 없거나 중복된 questionId, 유형에 맞지 않는 답안은 `INVALID_EXAM_ANSWER`(400)입니다.
- 같은 제출의 통신 재시도는 동일 requestId를 사용하며 기존 제출을 반환합니다. 새로운 재응시는 반드시 새 requestId를 사용합니다.
- 최신 제출이 `SUBMITTED`인 동안 새 requestId는 `EXAM_ALREADY_SUBMITTED`(409)입니다. 이 오류는 채점 중일 수 있으므로 기존 결과·polling 화면으로 유도합니다.
- 최신 제출이 `GRADED`이면 `allowRetake=false`에서 새 requestId를 거부하고, `allowRetake=true`이면 다음 attemptNo를 생성합니다. `GRADING_FAILED`는 응시권을 소모하지 않아 allowRetake와 무관하게 새 requestId로 다음 attempt를 생성할 수 있습니다.
- DRAFT 제출은 `EXAM_NOT_FOUND`(404)로 은닉하고 CLOSED 제출은 `EXAM_NOT_PUBLISHED`(409)로 거부합니다.

제출·본인 결과 응답의 공통 형태:

```json
{
  "submissionId": 300,
  "attemptNo": 1,
  "status": "GRADED",
  "score": 80.00,
  "maxScore": 100.00,
  "normalizedScore": 80.00,
  "submittedAt": "2026-08-02T12:00:00Z",
  "gradedAt": "2026-08-02T12:00:01Z",
  "items": [
    {
      "questionId": "q1",
      "answer": "a",
      "score": 20.00,
      "maxScore": 20.00,
      "verdict": "CORRECT",
      "feedback": null
    }
  ]
}
```

- HTTP 202 응답 본문은 200과 동일한 API envelope 및 `ExamSubmissionResponse` 스키마입니다. FE는 HTTP 상태코드가 아니라 응답의 `status` 필드로 화면과 polling 여부를 분기합니다.
- `SUBMITTED`에서는 `score`, `normalizedScore`, `gradedAt`과 모든 문항의 `score`, `verdict`, `feedback`을 null로 반환합니다. MCQ/OX 결과가 내부에서 이미 계산됐어도 terminal 상태 전에는 마스킹합니다. `answer`, `maxScore`, `questionId=q{questionNo}`는 유지합니다.
- 학생 목록·상세·제출 결과에는 `answerChoiceId`, `answerValue`, `explanation`, `referenceAnswer`, `modelAnswer`, `rubric`, `privateAnswer`, `isCorrect` 키를 포함하지 않습니다.
- POST가 `SUBMITTED`를 반환하면 기존 `GET /api/exams/{examId}/submissions/me`를 2초 간격으로 polling하고, 30초 뒤 5초 간격으로 전환합니다. `GRADED | GRADING_FAILED`에서 즉시 중단합니다. 31분을 넘기면 채점 지연 안내를 표시하되 polling은 유지하고, 세 번의 30분 채점 창과 scheduler 지연을 포함한 91분을 넘겨도 `SUBMITTED`이면 마지막 조회 후 중단하고 문의 안내를 표시합니다.

#### 채점·실패 계약

- MCQ/OX는 Spring이 결정적으로 채점합니다. 미응답은 `answer=null`, `score=0`, `verdict=WRONG`, `feedback=null`이며 AI 요청에 포함하지 않습니다.
- 응답이 있는 SHORT가 하나 이상이면 SHORT grade를 1회, 응답이 있는 ESSAY가 하나 이상이면 ESSAY grade를 1회 호출합니다. 해당 문항이 없으면 그 유형을 호출하지 않습니다.
- MCQ/OX만 있는 시험, SHORT/ESSAY가 모두 미응답인 시험과 전 문항 미응답 시험은 AI 호출 없이 `GRADED`로 완료합니다.
- 한 AI 유형 호출이 일반 채점 오류로 실패해도 다른 유형 호출은 계속합니다. 성공한 AI·결정적 결과와 미응답 결과는 보존하며, 실제 AI 호출이 하나 이상 실패하면 제출을 `GRADING_FAILED`로 둡니다.
- `GRADING_FAILED`에서는 제출 `score`, `normalizedScore`, `gradedAt`이 null입니다. 실패한 AI 문항의 `score`, `verdict`, `feedback`도 null이며 성공한 결정적·AI 결과와 미응답 결과는 보존합니다.
- 내부 AI가 `AI_REQUEST_INVALID`을 반환하면 Spring 계약 결함입니다. 비동기 worker는 HTTP 400·422를 동일하게 재시도하지 않고 제출을 `GRADING_FAILED`로 종결하며 `submissionId`, `examId`, 오류 code만 ERROR 로그에 남깁니다.
- 채점 worker는 5분 lease를 사용하고 scheduler는 30초마다 최대 100건을 회수합니다. `SUBMITTED.updatedAt`은 마지막 채점 시도 시작 시각이며 최초 제출·lease claim·컷오프 재큐잉·강사 재채점에서 현재 시각으로 갱신합니다. 마지막 시도 시작 후 30분이 지나면 active lease보다 우선해 첫 두 번은 `gradingRetryCount`를 증가시키고 재큐잉하며, 세 번째 30분 컷오프에서는 `gradingRetryCount=3`, `GRADING_FAILED`로 종결합니다. 강사 재채점은 카운트를 0으로 초기화합니다.
- AI 응답 점수는 문항 범위·소수 자릿수·questionId·verdict를 Spring이 재검증합니다. 총점과 `ROUND(score/maxScore*100,2)` 정규화 점수는 Spring이 계산합니다. 시험 결과는 quiz-assessment·diagnosis 파이프라인을 호출하지 않습니다.
- 운영용 최신 제출과 학생 결과 조회는 상태와 무관한 `MAX(attemptNo)`를 사용합니다. 성적·리포트 대표값은 `MAX(attemptNo WHERE status=GRADED)`이며, `GRADED 80점 → GRADING_FAILED` 순서라면 이전 80점 제출이 대표 성적입니다.

## 7. 학습 환경설정·학습자 메모리 API

### GET `/api/users/me/preferences`

`data`:

```json
{
  "newMaterialNotification": true,
  "studyReminder": true,
  "aiAnswerStyle": "NORMAL"
}
```

기존 사용자와 미설정 사용자의 기본값은 `true`, `true`, `NORMAL`입니다. 이메일·푸시와 학습 리마인더 발송은 범위 밖이며, 인앱 알림은 아래 네 가지 트리거에 한해 제공합니다.

### PATCH `/api/users/me/preferences`

```json
{
  "studyReminder": false,
  "aiAnswerStyle": "DETAILED"
}
```

세 필드는 부분 수정이며 하나 이상 필요합니다. `aiAnswerStyle`은 `CONCISE | NORMAL | DETAILED`입니다. 성공 시 GET과 같은 전체 환경설정 응답을 반환합니다.

`EXPLAIN_CURRENT_PAGE`의 설명 상세도 우선순위:

| 조건 | 실제 `detailLevel` |
| --- | --- |
| payload에 `NORMAL` | `NORMAL` — 요청값 우선 |
| payload에 `DETAILED` | `DETAILED` — 요청값 우선 |
| payload 생략 + `CONCISE` 설정 | `NORMAL` |
| payload 생략 + `NORMAL` 설정 | `NORMAL` |
| payload 생략 + `DETAILED` 설정 | `DETAILED` |

### GET `/api/users/me/notifications?page=0&size=20`

본인 인앱 알림을 `createdAt DESC, notificationId DESC`로 조회합니다. `size`는 최대 100입니다.

`data`:

```json
{
  "items": [
    {
      "notificationId": 100,
      "type": "NOTICE_PUBLISHED",
      "title": "중간고사 안내",
      "body": "3주차 공지를 확인해 주세요.",
      "link": {
        "classroomId": 30,
        "noticeId": 70
      },
      "readAt": null,
      "createdAt": "2026-08-14T03:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`type`과 `link`의 리소스 참조는 다음 네 종류입니다.

| type | 수신자·생성 시점 | link |
| --- | --- | --- |
| `MATERIAL_UPLOADED` | 강의실 자료 업로드 시 승인 멤버 전원 | `{classroomId, materialId}` |
| `NOTICE_PUBLISHED` | 즉시 공지는 생성·게시 시, 예약 공지는 `publishAt` 도래 후 승인 멤버 전원 | `{classroomId, noticeId}` |
| `JOIN_REQUEST_RECEIVED` | 입장 요청·재요청 시 강의실 소유 강사 | `{classroomId, joinRequestId}` |
| `JOIN_REQUEST_PROCESSED` | 입장 요청 승인·거절 시 요청 학생 | `{classroomId, joinRequestId}` |

예약 공지는 30초 주기 스캔에서 수신자 bulk insert와 공지의 발송 표식을 한 트랜잭션으로 처리해 한 번만 생성합니다. 알림은 생성 후 30일이 지나면 배치로 물리 삭제합니다.

### PATCH `/api/users/me/notifications/{notificationId}/read`

본인 알림의 `readAt`을 최초 읽음 시각으로 기록하고 전체 알림 항목을 반환합니다. 이미 읽은 알림은 기존 `readAt`을 유지하는 멱등 요청입니다. 타인 알림과 존재하지 않는 알림은 `RESOURCE_NOT_FOUND`(404)로 동일하게 은닉합니다.

### DELETE `/api/users/me/notifications/{notificationId}`

본인 알림을 물리 삭제하고 `data:null`을 반환합니다. 타인 알림과 존재하지 않는 알림은 `RESOURCE_NOT_FOUND`(404)입니다.

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

## 7.1 피드백 API

### POST `/api/feedback`

Bearer 인증이 필요하며, 인증 사용자를 작성자로 기록하고 피드백을 저장합니다.

```json
{
  "category": "BUG",
  "message": "채팅 화면이 멈춥니다.",
  "pageUrl": "https://app.example/sessions/10",
  "clientVersion": "web-1.2.3"
}
```

- `category`는 `BUG | FEATURE_REQUEST | GENERAL` 중 하나입니다.
- `message`는 공백이 아닌 문자열이며 최대 2,000자입니다.
- `pageUrl`, `clientVersion`은 선택 필드입니다.

`data`:

```json
{
  "feedbackId": 100,
  "createdAt": "2026-08-02T00:00:00Z"
}
```

알 수 없는 category는 `MALFORMED_REQUEST`(400), message 누락·공백·길이 초과는 `VALIDATION_FAILED`(400), 비인증 요청은 `AUTHENTICATION_REQUIRED`(401)입니다. 운영자는 DB에서 직접 확인하며 피드백 조회·상태 관리 API는 제공하지 않습니다.

## 7.2 강의실 API

강의실 계약은 DEC-030을 따릅니다. `INSTRUCTOR`는 본인 소유 강의실을 관리하고, `LEARNER`와 타 강의실에 참여한 `INSTRUCTOR`는 승인 멤버 권한으로 접근합니다. 강의실 존재·소유권·멤버십을 숨겨야 하는 경우 `CLASSROOM_NOT_FOUND`(404), 소유 강사 전용 API를 멤버가 호출하면 `ACCESS_DENIED`(403)를 반환합니다. `ADMIN`은 일반 강의실 관리 기능을 사용하지 않으며, 별도의 `/api/admin/classrooms` 읽기 전용 조회만 사용합니다.

색상 enum과 FE 표시값:

| API 값 | FE 색상 |
| --- | --- |
| `BLUE` | `#3B82F6` |
| `GREEN` | `#22C55E` |
| `PURPLE` | `#8B5CF6` |
| `ORANGE` | `#F97316` |
| `RED` | `#EF4444` |
| `GRAY` | `#64748B` |

### POST `/api/classrooms`

`INSTRUCTOR` 전용입니다.

```json
{
  "name": "AI 기초",
  "startDate": "2026-09-01",
  "endDate": "2026-12-15",
  "color": "BLUE",
  "description": "화요일 3교시"
}
```

- `name`: 비공백, 최대 100자
- `endDate >= startDate`
- `color`: 위 6개 enum 중 하나
- `description`: 선택, 최대 255자
- `weekCount`: `ceil((endDate-startDate+1일)/7)`로 서버 계산
- 초대 코드: 혼동 문자를 제외한 대문자·숫자 `XXXX-XXXX` 형식으로 서버 생성

성공 시 아래 강의실 상세 응답을 반환합니다.

### GET `/api/classrooms`

Query:

| 파라미터 | 필수 | 설명 |
| --- | :---: | --- |
| `status` | N | `ACTIVE | COMPLETED`; 생략 시 둘 다 |
| `q` | N | 강의실 이름 부분 검색 |
| `sort` | N | `RECENT` 기본 또는 `NAME` |
| `page` | N | 기본 0 |
| `size` | N | 기본 20 |

`RECENT`는 `createdAt DESC, classroomId DESC`, `NAME`은 이름 오름차순 후 `classroomId ASC`입니다. 역할과 무관하게 본인 소유 또는 승인 멤버인 강의실의 합집합을 반환합니다.

```json
{
  "items": [
    {
      "classroomId": 30,
      "name": "AI 기초",
      "instructorName": "홍길동",
      "startDate": "2026-09-01",
      "endDate": "2026-12-15",
      "weekCount": 16,
      "color": "BLUE",
      "status": "ACTIVE",
      "currentWeek": 3,
      "learnerCount": 24,
      "materialCount": 3,
      "progressRate": 18,
      "lastStudied": {
        "sessionId": 100,
        "materialId": 10,
        "materialTitle": "선형회귀 기초",
        "pageNumber": 3,
        "updatedAt": "2026-09-16T03:00:00Z"
      },
      "pendingRequestCount": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

- 멤버 관계의 항목은 `progressRate`, `lastStudied`를 포함하고 `pendingRequestCount`는 `null`입니다.
- 소유자 관계의 항목은 `pendingRequestCount`를 포함하고 `progressRate`, `lastStudied`는 `null`입니다.
- `materialCount`는 강의실 전체 주차에 연결된 고유 자료 수입니다.
- `lastStudied`는 상태와 관계없이 모든 주차에 연결된 고유 자료 중 사용자의 가장 최근 ACTIVE·COMPLETED 세션이며 없으면 `null`입니다. 세션을 어느 화면에서 시작했는지는 구분하지 않습니다.
- `progressRate`는 상태와 관계없이 모든 주차의 고유 READY 자료에 대해 `고유 (materialId,pageNumber) 설명 완료 수 ÷ 고유 자료 pageCount 합 × 100`을 정수 반올림합니다. 이력 또는 유효 분모가 없으면 0입니다.
- `currentWeek`은 `Asia/Seoul`의 오늘을 기준으로 계산하고 시작 전은 1, 종료 후는 `weekCount`입니다.
- `PROGRESS_ASC`, `NEW_MATERIAL`, `newMaterialCount`는 Phase C입니다.

### GET `/api/classrooms/{id}`

목록 공통 필드와 `description`을 반환합니다. 소유 강사 응답에는 `inviteCode`가 포함되고 멤버 응답에서는 해당 필드를 `null`로 반환합니다.

### GET `/api/classrooms/{id}/analytics`

소유 `INSTRUCTOR` 전용이며 다른 강사의 강의실은 `CLASSROOM_NOT_FOUND`로 은닉합니다. 모든 비율은 0~100 정수로 반올림하고, 멤버 또는 유효한 자료가 없으면 0입니다. 자료 집계 대상은 주차 상태와 관계없이 연결된 모든 고유 ACTIVE·READY 자료입니다.

```json
{
  "learnerCount": 5,
  "averageProgressRate": 28,
  "aiQuestionCountLast7Days": 4,
  "inactiveLearnerCountLast7Days": 2,
  "lastUpdatedAt": "2026-08-04T03:00:00Z",
  "materials": [
    {
      "materialId": 10,
      "title": "선형회귀 기초",
      "viewerCount": 2,
      "viewRate": 40,
      "averageProgressRate": 25
    }
  ],
  "questionsByPage": [
    {
      "materialId": 10,
      "pageNumber": 2,
      "questionCount": 4
    }
  ]
}
```

- `averageProgressRate`는 각 멤버의 강의실 진도를 평균한 값이며 `session_page_records`의 고유 `(materialId,pageNumber)`만 사용합니다.
- `viewerCount`는 해당 자료에 ACTIVE·COMPLETED 세션이 있는 고유 멤버 수이고, `viewRate = viewerCount / learnerCount × 100`입니다.
- `aiQuestionCountLast7Days`는 응답 계산 시각을 기준으로 정확히 7일 전을 포함해 이후 생성된 사용자 QA 메시지 수입니다.
- `inactiveLearnerCountLast7Days`는 같은 기간에 강의실 연결 자료의 세션 `updatedAt` 활동이 없는 멤버 수입니다.
- `questionsByPage`는 QA 스레드 생성 시 저장된 `qa_threads.page_number`를 사용하며 현재 세션 페이지를 소급 추정하지 않습니다.
- `lastUpdatedAt`은 캐시 시각이 아니라 이번 응답을 계산한 시각입니다.

### GET `/api/classrooms/{classroomId}/students/{studentId}/learning-analytics`

소유 `INSTRUCTOR`가 강의실 멤버 한 명의 자료별 학습 현황, 페이지별 질문 수, 통합학습 퀴즈 결과를 조회합니다. 비소유 강의실과 해당 강의실 멤버가 아닌 `studentId`는 모두 `CLASSROOM_NOT_FOUND`(404)로 은닉합니다.

Query:

| 파라미터 | 필수 | 설명 |
| --- | :---: | --- |
| `questionPeriod` | N | `LAST_7_DAYS`(기본) 또는 `ALL` |

```json
{
  "materials": [
    {
      "materialId": 10,
      "title": "선형회귀 기초",
      "weekNumber": 2,
      "progressRate": 30,
      "viewed": true,
      "lastViewedPage": 4,
      "lastViewedAt": "2026-08-25T02:00:00Z"
    },
    {
      "materialId": 11,
      "title": "분류 기초",
      "weekNumber": 3,
      "progressRate": 0,
      "viewed": false,
      "lastViewedPage": null,
      "lastViewedAt": null
    }
  ],
  "questionsByPage": [
    {
      "materialId": 10,
      "materialTitle": "선형회귀 기초",
      "weekNumber": 2,
      "pageNumber": 4,
      "questionCount": 2
    }
  ],
  "quizzes": [
    {
      "quizId": 700,
      "materialId": 10,
      "materialTitle": "선형회귀 기초",
      "weekNumber": 2,
      "title": "4페이지 확인 퀴즈",
      "quizType": "MCQ",
      "pageNumber": 4,
      "submitted": true,
      "score": 8.00,
      "maxScore": 10.00,
      "passed": true,
      "submittedAt": "2026-08-25T02:10:00Z"
    }
  ],
  "lastUpdatedAt": "2026-08-25T03:00:00Z"
}
```

- `materials`는 주차 상태와 관계없이 강의실에 연결된 모든 고유 ACTIVE·READY PDF를 포함합니다. 한 자료가 여러 주차에 연결되면 가장 작은 `weekNumber`를 사용합니다.
- `progressRate`는 기존 학습 진도와 동일하게 학생·자료의 ACTIVE·COMPLETED 세션에 기록된 고유 설명 완료 페이지 수를 자료 `pageCount`로 나눈 뒤 정수 반올림합니다.
- `viewed`는 ACTIVE·COMPLETED 세션 존재 여부입니다. 최신 세션은 `updatedAt DESC, sessionId DESC`로 결정하며 미열람 자료는 `progressRate=0`, `lastViewedPage=null`, `lastViewedAt=null`입니다.
- `questionsByPage`는 사용자 QA 메시지가 1개 이상인 페이지만 반환합니다. `LAST_7_DAYS`는 응답 계산 시각의 정확히 7일 전을 포함하며 `ALL`은 기간 제한이 없습니다.
- `quizzes`는 학생의 강의실 자료 세션에서 생성된 전체 퀴즈를 포함합니다. 제출이 여러 번이면 가장 큰 `attemptNo`를 사용하고, 미제출은 `submitted=false`이며 점수·통과·제출 시각이 모두 `null`입니다. 비공개 정답 데이터는 포함하지 않습니다.
- `lastUpdatedAt`은 이번 응답을 계산한 서버 UTC 시각입니다.

### PATCH `/api/classrooms/{id}`

```json
{
  "name": "AI 기초 심화",
  "startDate": "2026-09-08",
  "endDate": "2026-12-22",
  "shiftWeekReleaseDates": true,
  "color": "PURPLE",
  "description": null
}
```

모든 필드는 선택이지만 하나 이상 필요합니다. 필드 생략은 변경 없음, `description:null`은 설명 삭제입니다. `startDate`와 `endDate`를 함께 또는 각각 변경할 수 있으며 변경 후 `startDate <= endDate`여야 합니다.

`shiftWeekReleaseDates`는 `startDate` 변경 시 기존 주차 표시 일정을 함께 이동할지 선택하며 생략하거나 `false`이면 이동하지 않습니다. `true`이면 `새 startDate - 기존 startDate`의 일수만큼 해당 강의실 모든 주차의 `releaseAt`을 같은 트랜잭션에서 이동합니다. `releaseAt=null`은 일정 없음 의미를 유지하기 위해 변경하지 않습니다. 주차의 `status`는 변경하지 않으며 이동된 `releaseAt`과 상태는 학습자 접근을 제한하지 않습니다.

날짜 변경으로 계산한 새 `weekCount`보다 기존 최대 `weekNumber`가 크면 주차를 암묵적으로 삭제하지 않고 `CLASSROOM_WEEK_RANGE_CONFLICT`(409)를 반환합니다. 완료 강의실은 `CLASSROOM_COMPLETED`(409), 비소유 강의실은 `CLASSROOM_NOT_FOUND`(404)입니다. 성공 시 갱신된 상세를 반환합니다.

### DELETE `/api/classrooms/{id}`

물리 삭제하지 않고 `status=COMPLETED`로 전환하며 멱등입니다. 완료 강의실은 기존 소유자·멤버의 공개 자료 조회와 본인 통합학습을 유지하고, 초대·참여 처리·주차·자료 연결·공지 쓰기는 `CLASSROOM_COMPLETED`(409)로 거부합니다. 성공 시 갱신된 상세를 반환합니다.

### DELETE `/api/classrooms/{id}/permanent`

소유 `INSTRUCTOR`가 강의실 이름을 다시 입력해 강의실을 물리 삭제합니다. `ACTIVE`와 `COMPLETED` 상태 모두 허용하며 기존 완료 전환 API와는 별개입니다.

```json
{
  "confirmName": "AI 기초"
}
```

`confirmName`은 앞뒤 공백을 제거한 뒤 현재 강의실 이름과 대소문자·내부 공백을 포함해 정확히 일치해야 합니다. 불일치는 `VALIDATION_FAILED`(400)입니다. 역할 확인과 잠금 기반 소유권 확인을 먼저 수행하며, 학생·비소유 강사·존재하지 않는 강의실은 모두 `CLASSROOM_NOT_FOUND`(404)로 은닉합니다. 삭제 후 같은 요청을 다시 보내도 `CLASSROOM_NOT_FOUND`입니다.

한 트랜잭션에서 다음 강의실 소속 데이터를 FK 역순으로 일괄 삭제합니다.

- 별도 시험: `exam_answers`, `exam_submissions`, `exam_questions`, `exams`
- 리포트: `report_criterion_results`, `student_reports`, `report_evidence_snapshots`, `report_generations`, `report_criteria`
- 강의실 운영: `classroom_resource`, `classroom_notices`, `classroom_week_materials`, `classroom_weeks`, `classroom_join_requests`, `classroom_members`, `classrooms`

`student_reports.previous_report_id`는 삭제 전에 참조를 해제합니다. 강사 개인 소유 `learning_materials`와 학생-자료 관계의 `learning_sessions`, `session_page_records`, 채팅·QA, 통합학습 퀴즈·제출·평가, 진단, 학습자 메모리 및 `user_schedules`는 삭제하지 않습니다. 성공 응답의 `data`는 `null`입니다.

### GET `/api/classrooms/{id}/invite-code`

### POST `/api/classrooms/{id}/invite-code/regenerate`

소유 `INSTRUCTOR` 전용이며 두 API 모두 다음 데이터를 반환합니다. 재발급 성공 즉시 기존 코드는 무효입니다.

```json
{
  "classroomId": 30,
  "inviteCode": "7KMX-9QTR"
}
```

### POST `/api/classroom-join-requests`

`LEARNER`와 `INSTRUCTOR`가 사용할 수 있습니다. `INSTRUCTOR`는 본인이 소유하지 않은 강의실에만 요청할 수 있으며 자기 강의실 요청은 `ACCESS_DENIED`입니다. 입력 코드는 trim 후 대문자로 정규화합니다.

```json
{
  "inviteCode": "7KMX-9QTR"
}
```

```json
{
  "requestId": 50,
  "classroomId": 30,
  "classroomName": "AI 기초",
  "status": "PENDING",
  "requestedAt": "2026-09-01T00:00:00Z"
}
```

- 존재하지 않거나 완료 강의실의 코드: `INVALID_INVITE_CODE`
- 이미 멤버: `ALREADY_CLASSROOM_MEMBER`
- 기존 PENDING 요청: `JOIN_REQUEST_ALREADY_PENDING`
- 기존 REJECTED 요청: 같은 행을 PENDING으로 바꾸고 `requestedAt`을 갱신하며 `processedAt=null`

### GET `/api/classroom-join-requests/me?page&size`

내 요청을 `requestedAt DESC, requestId DESC`로 반환합니다. 항목은 참여 요청 응답 필드와 `processedAt`을 포함합니다.

### GET `/api/classrooms/{id}/join-requests?status&page&size`

소유 `INSTRUCTOR` 전용입니다. 기본 status는 `PENDING`이며 최신 요청순입니다.

```json
{
  "items": [
    {
      "requestId": 50,
      "status": "PENDING",
      "requestedAt": "2026-09-01T00:00:00Z",
      "processedAt": null,
      "learner": {
        "userId": 20,
        "name": "김학습",
        "email": "learner@example.com",
        "affiliation": "컴퓨터공학과"
      }
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### POST `/api/classrooms/{id}/join-requests/{requestId}/approve`

### POST `/api/classrooms/{id}/join-requests/{requestId}/reject`

PENDING 요청만 처리합니다. 승인은 같은 트랜잭션에서 `classroom_members`를 생성합니다. 이미 처리된 요청은 `JOIN_REQUEST_ALREADY_PROCESSED`입니다. 성공 응답은 `{requestId, classroomId, status, processedAt}`입니다.

### GET `/api/classrooms/{id}/weeks`

강사와 학습자 모두 전체 주차 메타데이터와 연결 자료를 조회합니다. `PRIVATE | SCHEDULED | PUBLISHED | BREAK` 상태와 `releaseAt`은 표시 전용이며 학습자 자료 접근을 제한하지 않습니다. 목록은 역할과 관계없이 `displayOrder ASC`, `id ASC`로 정렬합니다. 자료 상세·파일·세션·퀴즈 등 학습 API는 주차 상태와 관계없이 강의실 승인 멤버에게 허용하고, 비멤버에게는 기존 404 은닉 규칙을 그대로 적용합니다.

```json
{
  "items": [
    {
      "weekId": 101,
      "weekNumber": 1,
      "title": "회귀분석 개요",
      "status": "PUBLISHED",
      "displayOrder": 1,
      "releaseAt": null,
      "averageProgressRate": 20,
      "materials": [
        {
          "materialId": 10,
          "title": "선형회귀 기초",
          "pageCount": 25,
          "processingStatus": "READY",
          "uploadedAt": "2026-09-01T00:00:00Z",
          "viewerCount": 12,
          "viewRate": 50
        }
      ]
    },
    {
      "weekId": 102,
      "weekNumber": 2,
      "title": "다중회귀 예고",
      "status": "SCHEDULED",
      "displayOrder": 2,
      "releaseAt": "2026-09-08T00:00:00Z",
      "averageProgressRate": 0,
      "materials": [
        {
          "materialId": 11,
          "title": "다중회귀 예고.pdf",
          "pageCount": 18,
          "processingStatus": "READY",
          "uploadedAt": "2026-09-02T00:00:00Z",
          "viewerCount": 0,
          "viewRate": 0
        }
      ]
    }
  ]
}
```

`averageProgressRate`는 응답에 포함된 해당 주차의 고유 자료에 대한 멤버 평균 진도율입니다. `viewerCount`와 `viewRate`는 강의실 멤버의 ACTIVE·COMPLETED 세션을 기준으로 계산하며 멤버가 없으면 0입니다.

### POST `/api/classrooms/{id}/weeks`

```json
{
  "weekNumber": 1,
  "title": "회귀분석 개요",
  "releaseAt": null
}
```

`1 <= weekNumber <= weekCount`이며 중복 번호는 `WEEK_ALREADY_EXISTS`입니다. 생성 시 `releaseAt`이 없거나 이미 경과했으면 `PUBLISHED`, 미래이면 `SCHEDULED`로 초기화하고 기존 마지막 `displayOrder` 뒤에 추가합니다. 성공 시 위 주차 항목을 반환합니다.

### PATCH `/api/classrooms/{id}/weeks/{weekNumber}`

```json
{
  "title": "회귀분석 기초",
  "releaseAt": "2026-09-08T00:00:00Z"
}
```

필드 생략은 변경 없음입니다. `releaseAt` 변경은 정본 `status`를 변경하지 않으며 상태는 별도 status API로 전환합니다. `releaseAt`과 주차 상태 변경은 표시 정보만 바꾸며 자료·파일 조회, 세션 생성과 기존 세션의 추가 턴 접근권에는 영향을 주지 않습니다.

### PATCH `/api/classrooms/{id}/weeks/{weekId}/status`

```json
{
  "status": "BREAK"
}
```

강사 본인 강의실의 주차만 변경할 수 있고 상태 간 전이 제한은 없습니다. 타 강의실 또는 없는 주차는 `WEEK_NOT_FOUND`/`CLASSROOM_NOT_FOUND`로 은닉하며 완료 강의실은 `CLASSROOM_COMPLETED`입니다. 성공 시 갱신된 주차 항목을 반환합니다.

### PATCH `/api/classrooms/{id}/weeks/reorder`

```json
{
  "orderedWeekIds": [103, 101, 102]
}
```

`orderedWeekIds`는 해당 강의실 전체 `weekId` 집합과 누락·중복 없이 정확히 일치해야 합니다. 검증 성공 후 한 트랜잭션에서 `displayOrder`를 1부터 다시 부여하며 `weekNumber`는 변경하지 않습니다. 불일치·중복·타 강의실 ID는 `VALIDATION_FAILED`이고 부분 적용하지 않습니다. 성공 시 새 순서의 주차 목록을 반환합니다.

### DELETE `/api/classrooms/{id}/weeks/{weekNumber}`

주차와 모든 자료 연결을 삭제하되 자료 자체는 유지합니다. 성공 시 `data:null`을 반환합니다.

### POST `/api/classrooms/{id}/weeks/{weekNumber}/materials/{materialId}`

강사 본인 소유 ACTIVE 자료만 연결합니다. 동일 연결은 `MATERIAL_ALREADY_LINKED`입니다. 성공 시 갱신된 주차 항목을 반환합니다.

### DELETE `/api/classrooms/{id}/weeks/{weekNumber}/materials/{materialId}`

연결만 제거하고 자료와 기존 학습 기록은 유지합니다. 연결이 이미 없으면 멱등 성공합니다. 연결 해제 후 다른 소유권·강의실 연결 접근 경로가 없는 사용자는 신규 자료·파일 조회, 세션 생성과 기존 세션의 추가 턴이 차단됩니다.

### GET `/api/classrooms/{id}/resources?weekNumber&page&size`

AI 추출·통합학습 대상이 아닌 강의실 일반 파일·링크 자료를 `createdAt DESC, resourceId DESC`로 반환합니다. `weekNumber`를 생략하면 전체 자료, 지정하면 해당 주차 자료만 반환합니다. 기본 `page=0`, `size=20`, 최대 100이며 `weekNumber`는 null 또는 `1 <= weekNumber <= weekCount`여야 합니다. 소유 강사와 승인 멤버가 조회할 수 있고 비접근 강의실은 `CLASSROOM_NOT_FOUND`(404)로 은닉합니다.

```json
{
  "items": [
    {
      "resourceId": 70,
      "type": "FILE",
      "title": "2주차 발표 자료",
      "weekNumber": 2,
      "fileName": "week2-slides.pptx",
      "contentType": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "sizeBytes": 1048576,
      "url": null,
      "createdAt": "2026-08-25T01:00:00Z"
    },
    {
      "resourceId": 71,
      "type": "LINK",
      "title": "참고 사이트",
      "weekNumber": null,
      "fileName": null,
      "contentType": null,
      "sizeBytes": null,
      "url": "https://example.com/reference",
      "createdAt": "2026-08-25T01:01:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1
}
```

### POST `/api/classrooms/{id}/resources` — FILE

`multipart/form-data`의 `file`, `title`, 선택 `weekNumber`를 받습니다. `title`은 trim 후 비공백·최대 200자, 파일은 비어 있지 않고 기존 자료 업로드 상한 이하여야 합니다. 확장자는 대소문자를 무시하고 `jpg | jpeg | png | gif | webp | pdf | doc | docx | ppt | pptx | xls | xlsx | hwp | hwpx | txt | csv | zip`만 허용하며 확장자가 없으면 `VALIDATION_FAILED`(400)입니다. 파일은 `EDUPILOT_STORAGE_DIR/classroom-resources/{UUID}`에 저장하고 원본 파일명은 응답·DB 메타데이터로만 보존합니다.

Swagger/OpenAPI에는 이 FILE 요청을 `multipart/form-data` binary operation으로, 아래 LINK 요청을 `application/json` operation으로 같은 경로에 구분해 게시합니다.

### POST `/api/classrooms/{id}/resources` — LINK

```json
{
  "url": "https://example.com/reference",
  "title": "참고 사이트",
  "weekNumber": null
}
```

URL은 trim 후 최대 2048자이며 `http` 또는 `https` 프로토콜과 호스트가 있어야 합니다. FILE·LINK 생성은 소유 강사의 ACTIVE 강의실에서만 가능하고 완료 강의실은 `CLASSROOM_COMPLETED`(409), 비소유·부재 강의실은 `CLASSROOM_NOT_FOUND`(404)입니다. 성공 응답은 위 목록 항목과 같습니다.

### PATCH `/api/resources/{resourceId}`

```json
{
  "title": "수정된 발표 자료",
  "weekNumber": 3
}
```

`title`, `weekNumber` 부분 수정이며 하나 이상 필요합니다. `weekNumber:null`은 전체 자료로 변경하고 FILE 내용·원본명과 LINK URL은 이 API에서 변경하지 않습니다. 생성과 같은 소유권·ACTIVE·제목·주차 규칙을 적용합니다.

### GET `/api/resources/{resourceId}/file`

FILE 원본을 인증 응답으로 제공합니다. 이미지·PDF는 `Content-Disposition:inline`, 나머지는 `attachment`이고 원본 파일명은 RFC 5987 UTF-8 형식으로 인코딩합니다. `Cache-Control: private, max-age=3600, immutable`을 사용하며 공유 캐시는 금지합니다. LINK에 파일 요청을 보내거나 자료가 없으면 `RESOURCE_NOT_FOUND`(404)입니다.

### DELETE `/api/resources/{resourceId}`

소유 강사의 ACTIVE 강의실 자료만 물리 삭제합니다. FILE은 DB 행 삭제 커밋 후 storage 파일을 best-effort로 삭제하며 실패는 경고 로그만 남깁니다. 성공 응답의 `data`는 `null`입니다.

### GET `/api/classrooms/{id}/notices?page&size`

소유 강사와 승인 멤버가 접근하며 기존과 같이 `publishedAt DESC, noticeId DESC`로 반환합니다. 소유 강사는 예약 공지를 포함한 전체를 조회하고, 학습자는 `publishAt`이 없거나 현재 UTC 시각 이하인 게시 공지만 조회합니다. 예약 게시 판정은 스케줄러 없이 조회 시점에 수행합니다.

```json
{
  "items": [
    {
      "noticeId": 70,
      "classroomId": 30,
      "weekNumber": 2,
      "title": "첫 수업 안내",
      "content": "교재를 준비해 주세요.",
      "publishedAt": "2026-09-01T00:00:00Z",
      "publishAt": "2026-09-02T00:00:00Z",
      "published": false,
      "createdAt": "2026-09-01T00:00:00Z",
      "updatedAt": "2026-09-01T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### POST `/api/classrooms/{id}/notices`

```json
{
  "title": "첫 수업 안내",
  "content": "교재를 준비해 주세요.",
  "weekNumber": 2,
  "publishAt": "2026-09-02T00:00:00Z"
}
```

제목은 비공백·최대 200자이고 내용은 비공백이어야 합니다. `weekNumber`는 nullable이며 값이 있으면 `1 <= weekNumber <= weekCount`여야 합니다. `publishAt`은 nullable UTC 시각이고 null 또는 과거 값은 즉시 게시, 미래 값은 예약 게시입니다. `publishedAt`은 기존 생성 시각 의미를 유지하고 `published`는 조회 시각 기준 게시 여부입니다. 기존 공지는 `weekNumber=null`, `publishAt=null`로 전체 공지·즉시 게시를 유지합니다. 성공 시 공지 항목을 반환합니다.

### PATCH `/api/classrooms/{id}/notices/{noticeId}`

`title`, `content`, `weekNumber`, `publishAt` 부분 수정이며 하나 이상 필요합니다. `weekNumber:null`은 전체 공지, `publishAt:null`은 즉시 게시로 변경합니다. 주차 범위와 예약 게시 판정 규칙은 생성과 같고 `publishedAt`은 변경하지 않습니다. 성공 시 공지 항목을 반환합니다.

### DELETE `/api/classrooms/{id}/notices/{noticeId}`

MVP에서는 공지를 물리 삭제하고 `data:null`을 반환합니다.

### GET `/api/users/me/schedule?from&to&classroomId`

`from`, `to`는 `YYYY-MM-DD` 형식의 필수 값이고 양 끝 날짜를 포함하며 `from <= to`여야 합니다. 선택 `classroomId`는 사용자가 소유하거나 참여한 강의실이어야 합니다. `classroomId`가 없으면 본인 개인 일정과 공개 주차·공지를 병합하고, 지정하면 해당 강의실의 파생 일정만 반환합니다. 모든 항목은 `dateTime ASC, scheduleId ASC`로 정렬합니다. 예약 주차는 `releaseAt`, 즉시 공개 주차(`releaseAt=null`)는 주차 `createdAt`, 공지는 `publishedAt`, 개인 일정은 `startsAt`을 `dateTime`으로 사용합니다. 예약 공지는 소유 강사의 일정에는 포함하지만 학습자 일정에는 `publishAt` 도래 후 포함합니다.

기존 `type` 필드는 유지하고 `kind`를 일정 구분자로 함께 반환합니다. 파생 일정의 `kind`는 기존 `type` 값과 같고 개인 일정은 두 필드 모두 `PERSONAL`입니다. 개인 일정의 `scheduleId`는 숫자의 string 표현이며 PATCH·DELETE 경로에 그대로 사용할 수 있습니다. 파생 일정에는 개인 일정 전용 필드가 `null`이고, 개인 일정에는 강의실 전용 필드가 `null`입니다.

```json
{
  "items": [
    {
      "scheduleId": "WEEK-40",
      "dateTime": "2026-09-08T00:00:00Z",
      "type": "WEEK_RELEASE",
      "kind": "WEEK_RELEASE",
      "title": "2주차 공개: 선형회귀",
      "classroomId": 30,
      "classroomName": "AI 기초",
      "color": "BLUE",
      "startsAt": null,
      "endsAt": null,
      "hasTime": null
    },
    {
      "scheduleId": "91",
      "dateTime": "2026-09-08T01:00:00Z",
      "type": "PERSONAL",
      "kind": "PERSONAL",
      "title": "복습",
      "classroomId": null,
      "classroomName": null,
      "color": null,
      "startsAt": "2026-09-08T01:00:00Z",
      "endsAt": "2026-09-08T02:00:00Z",
      "hasTime": true
    }
  ]
}
```

### POST `/api/users/me/schedule`

```json
{
  "title": "복습",
  "startsAt": "2026-09-08T01:00:00Z",
  "endsAt": "2026-09-08T02:00:00Z",
  "hasTime": true
}
```

제목은 공백이 아닌 200자 이하이고 모든 필드는 필수입니다. `endsAt >= startsAt`이어야 하며 같은 시각의 단일 시점 일정을 허용합니다. `hasTime=false`인 종일 일정도 UTC 자정 기준 `Instant`를 `DATETIME(6)`에 그대로 저장합니다.

```json
{
  "scheduleId": "91",
  "kind": "PERSONAL",
  "title": "복습",
  "startsAt": "2026-09-08T01:00:00Z",
  "endsAt": "2026-09-08T02:00:00Z",
  "hasTime": true
}
```

### PATCH `/api/users/me/schedule/{scheduleId}`

`title`, `startsAt`, `endsAt`, `hasTime` 중 하나 이상을 보내는 부분 수정입니다. 수정 후 전체 범위가 `endsAt >= startsAt`이어야 합니다. 성공 응답은 POST와 같고, 일정이 없거나 타인 소유이면 모두 `SCHEDULE_NOT_FOUND`(404)로 은닉합니다.

### DELETE `/api/users/me/schedule/{scheduleId}`

본인 개인 일정을 물리 삭제하고 `data:null`을 반환합니다. 일정이 없거나 타인 소유이면 모두 `SCHEDULE_NOT_FOUND`(404)입니다. 반복 일정·알림·공유와 공지 예약 게시는 이 범위에 포함하지 않습니다.

## 7.3 리포트·수강생 관리 API

이 절의 API는 강의실 소유 `INSTRUCTOR` 전용입니다. 타 강의실·타 학생·타 강사의
리포트와 기준은 404로 은닉하며, 강의실 멤버인 학습자가 관리 API를 호출하면
`ACCESS_DENIED`(403)를 반환합니다. 외부 `reportId`는 generation ID의 string 표현이며
생성 접수부터 완료 상세까지 바뀌지 않습니다. StudentReport의 DB PK는 노출하지 않습니다.

### GET `/api/classrooms/{classroomId}/students?page=0&size=20&q=&sort=`

수강생을 페이지네이션합니다. `q`는 이름 부분 일치 검색이며 앞뒤 공백을 제거하고,
생략하거나 공백만 보내면 전체를 조회합니다. `sort`를 생략하면 기존과 같이
`joinedAt DESC`를 유지합니다. 지원 정렬은 다음과 같고, 같은 정렬값은 기존
`joinedAt DESC` 순서를 유지합니다.

- `NAME`: 이름 오름차순
- `LOW_PROGRESS`: `averageProgressRate` 오름차순
- `RECENT_ACTIVITY`: `lastActiveAt` 내림차순, 활동이 없는 학생은 마지막

`lastActiveAt`은 해당 강의실 자료의 삭제되지 않은 학습 세션 중 가장 최근
`updatedAt`이며, 세션이 없으면 `null`입니다. 현재 멤버십 모델은 활성 행만
저장하므로 조회 항목의 `status`는 `ACTIVE`입니다.

```json
{
  "items": [
    {
      "studentId": 40,
      "name": "김학습",
      "email": "learner@example.com",
      "affiliation": null,
      "joinedAt": "2026-08-01T00:00:00Z",
      "status": "ACTIVE",
      "lastActiveAt": "2026-08-03T05:00:00Z",
      "averageProgressRate": 25,
      "aiQuestionCountLast7Days": 4
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`averageProgressRate`는 #170 분석과 동일하게 학습자에게 공개된 READY 자료의 고유
설명 완료 페이지 합을 해당 자료 전체 페이지 합으로 나눈 뒤 정수 반올림합니다.
`aiQuestionCountLast7Days`는 같은 공개 자료 범위에서 `qa_threads`를 경유한 USER
QA 메시지 중 응답 계산 시각 기준 정확히 7일 전을 포함해 이후 생성된 수입니다.
진도·최근 활동·질문 수는 페이지의 학생마다 개별 조회하지 않고 강의실 범위 배치
쿼리로 계산합니다.

### DELETE `/api/classrooms/{classroomId}/students/{studentId}`

멤버십 행만 삭제하고 기존 세션·제출·리포트 데이터는 보존합니다. 이후 목록에서
제외되며 새 리포트 생성은 `CLASSROOM_NOT_FOUND`(404)로 거부됩니다. 성공 응답은
`data:null`입니다.

### GET `/api/classrooms/{classroomId}/report-criteria`

기본 9종(`builtin:true`)과 활성 커스텀 기준(`builtin:false`)을 병합해 반환합니다.
항목은 `criterionId`(기본 기준은 null), `criterionKey`, `name`, `description`, `rubric`,
`allowedSources`, `minEvidence`, `weight`, `version`, `active`, `builtin`을 포함합니다.

### POST `/api/classrooms/{classroomId}/report-criteria`

```json
{
  "criterionKey": "weekly_consistency",
  "name": "주간 학습 일관성",
  "description": "주차별 학습 지속성을 평가합니다.",
  "rubric": {"summary": "주차별 활동 간격과 완료 기록을 평가"},
  "allowedSources": ["SESSION", "QUIZ_SUBMISSION"],
  "minEvidence": 2,
  "weight": 1.0
}
```

key는 trim·소문자화하고 공백과 `-`를 `_`로 정규화한 뒤 기본 9종·기존 커스텀 key와
중복을 검사합니다. 이름은 trim 후 연속 공백과 대소문자를 정규화해 기본·활성 커스텀
기준과 정확 중복을 검사합니다. 기본 key와 충돌하는 과거 커스텀 데이터는 리포트 생성 시
기본 기준을 우선하고 커스텀 기준을 무시합니다. 기본 9종과 활성
커스텀의 합은 20개 이하입니다. 중복은 `REPORT_CRITERION_DUPLICATE`(409), 상한 초과는
`REPORT_CRITERION_LIMIT_EXCEEDED`(400)입니다.

### PATCH `/api/classrooms/{classroomId}/report-criteria/{criterionId}`

`name`, `description`, `rubric`, `allowedSources`, `minEvidence`, `weight`, `active`를 부분
변경합니다. 내용 변경은 기존 행을 비활성화하고 다음 version 행을 생성합니다.
`active:false`만 전송한 비활성화는 현재 행을 직접 갱신합니다. 재활성화에도 이름 중복과
활성 20개 상한을 동일하게 검증합니다. 이미 생성된 generation의 기준 snapshot은 바뀌지
않고 다음 생성부터 반영됩니다.

### DELETE `/api/classrooms/{classroomId}/report-criteria/{criterionId}`

소유 강사가 커스텀 기준을 물리 삭제합니다. 해당 key의 최신 version ID만 요청할 수 있으며,
과거 version ID·다른 강의실의 기준·존재하지 않는 기준은 `REPORT_NOT_FOUND`(404)로
은닉합니다. 성공하면 해당 `(classroomId, criterionKey)`의 전 version을 삭제하고
`data:null`을 반환합니다. 삭제된 key·이름은 새 커스텀 기준으로 다시 사용할 수 있고 활성
기준 상한에서도 즉시 제외됩니다.

리포트 생성은 요청 시작 시점에 `criterion_catalog_json` 스냅샷을 동결해 worker가 이후
live 기준 테이블을 다시 읽지 않으므로, 삭제는 진행 중 generation에 영향을 주지 않습니다.
과거 `report_criterion_results`도 key·version 값을 독립적으로 보존하며 삭제되지 않습니다.

### POST `/api/classrooms/{classroomId}/report-criteria/generate`

소유 강사가 강의실 자료 개요를 바탕으로 커스텀 평가 기준 자동 생성을 요청합니다.
요청 본문은 없으며 정상 접수 시 HTTP 202를 반환합니다.

```json
{"status":"RUNNING"}
```

AI 호출 전 강의실에 `READY` 개요가 하나 이상 있어야 하고, 기본 9종과 활성 커스텀
기준을 제외한 여유 슬롯이 3개 이상이어야 합니다. 개요가 없으면
`REPORT_CRITERIA_GENERATION_NOT_READY`(400), 슬롯이 부족하면
`REPORT_CRITERION_LIMIT_EXCEEDED`(400)입니다. 같은 강의실에서 이미 실행 중이면
`REPORT_CRITERION_DUPLICATE`(409)를 반환합니다.

입력에는 기본 9종과 비활성을 포함한 모든 커스텀 key, `READY` 개요의 자료 제목·요약·
목차를 전달합니다. AI가 반환한 기준은 수동 생성과 같은 key·이름 중복 및 20개 상한
검증을 거쳐 한 트랜잭션으로 등록합니다. 하나라도 중복이거나 응답 수가 여유 슬롯보다
많으면 전부 등록하지 않습니다. `rubric` 문자열은 `{"summary":"..."}`로 저장하고
description은 최대 500자로 제한합니다.

### GET `/api/classrooms/{classroomId}/report-criteria/generation`

소유 강사가 현재 프로세스의 인메모리 생성 상태를 조회합니다. 서버 재시작 시 상태는
`IDLE`로 초기화되며, 이전 `RUNNING` 작업은 다시 생성 요청할 수 있습니다.

```json
{"status":"IDLE"}
```

```json
{"status":"COMPLETED","registeredCount":3,"message":"QUALITY_WARNING: 일부 개요가 짧습니다."}
```

```json
{"status":"FAILED","message":"기존 지표 정리 후 재시도"}
```

`registeredCount`는 `COMPLETED`에서만, `message`는 AI warning이 있거나 실패한 경우에만
포함됩니다. AI timeout·`INSUFFICIENT_TEXT`를 포함한 호출 실패는 DB를 변경하지 않고
`FAILED`로 수렴하며, 강사는 생성 버튼으로 재시도합니다.

### POST `/api/classrooms/{classroomId}/students/{studentId}/reports`

```json
{
  "requestId": "report-request-20260804-1",
  "scope": "WEEK",
  "weekNumber": 3
}
```

scope는 `FULL | WEEK`이며 FULL에는 `weekNumber`를 보내지 않고 WEEK에는 1 이상의
`weekNumber`가 필수입니다. 신규·동일 requestId 재요청·같은 학생과 scope의 활성 생성
재사용 모두 HTTP 202를 반환합니다.

```json
{
  "reportId": "901",
  "status": "PENDING",
  "pollAfterSeconds": 5
}
```

FE는 `pollAfterSeconds` 뒤 `GET /api/reports/{reportId}`를 호출하며 terminal 상태인
`COMPLETED | FAILED`에서 polling을 중단합니다.

### GET `/api/classrooms/{classroomId}/students/{studentId}/reports`

완료 리포트의 `reportId`, `version`, `overallScore`, `overallStage`, `createdAt`을 version
내림차순 `items`로 반환합니다. PENDING 또는 PROCESSING generation이 있으면
`activeGeneration:{reportId,status,pollAfterSeconds}`를 함께 반환하고 없으면 null입니다.

### GET `/api/reports/{reportId}`

PENDING·PROCESSING 응답:

```json
{"reportId":"901","status":"PROCESSING","pollAfterSeconds":5}
```

FAILED 응답은 generation에 동결된 envelope를 그대로 사용하며 최신 데이터를 다시
집계하지 않습니다. criterion 결과는 포함하지 않습니다.

```json
{
  "reportId": "901",
  "status": "FAILED",
  "failureCode": "AI_SERVICE_TIMEOUT",
  "fallback": {
    "metrics": {"sessionCount": 4},
    "dataQuality": {"progressDataAvailable": true}
  }
}
```

COMPLETED 응답은 `version`, `previousVersion`, `overallScore`, `overallStage`, `summary`,
`criteria`, `evidence`, `createdAt`을 포함합니다. criterion 항목은 `criterionKey`,
`criterionVersion`, nullable `score`, nullable `trend`, `status`, `narrative`, `evidenceIds`를
포함합니다. `INSUFFICIENT_DATA`의 `score:null`은 0점과 구분해 명시적으로 직렬화합니다.
`version`, `previousVersion`, criterion `trend`는 같은 scope 체인에서만 계산합니다.
FULL은 FULL끼리, WEEK는 같은 `weekNumber`끼리 독립된 체인을 사용합니다. V25 이전에
생성된 리포트의 `previousVersion`과 `trend`는 구 혼합 체인 기준일 수 있으며 역사적
값을 보정하지 않습니다.
evidence는 결과가 참조한 항목만 `evidenceId`, `sourceType`, `publicLabel`, `occurredAt`으로
노출합니다. 알려진 수치 근거가 있으면 선택 `metrics:[{label,value}]`를 함께 노출하며,
점수·환산 점수·통과 여부·시도 회차·강점/보완/오개념 문항 수·집중 개념 수만 사람이 읽을 수
있는 문자열로 변환합니다. 매핑 가능한 값이 없으면 `metrics` 필드 자체를 생략합니다. 기존 동결
스냅샷은 소급 변경하지 않으므로 보유한 문항 수 계열만 노출될 수 있습니다. `sourceRef`,
`minimalFact`, hash와 generation lease 정보는 외부 응답에 포함하지 않습니다. 없는 리포트는
`REPORT_NOT_FOUND`(404)입니다.

## 7.4 관리자 조회 API

모든 `/api/admin/**` 요청은 JWT의 `ROLE_ADMIN` URL 규칙, 컨트롤러의
`@PreAuthorize("hasRole('ADMIN')")`, 요청 시점 DB의 `ADMIN/ACTIVE` 재검증을 모두
통과해야 합니다. 이 API 묶음은 읽기 전용이며 역할·상태 변경, 회원 탈퇴, 강의실 조작 같은
쓰기 API는 제공하지 않습니다.

### GET `/api/admin/users?q=&role=&status=&sort=&page=&size=`

- `q`: 이메일 또는 이름 부분일치, 대소문자 무시
- `role`: 선택 `ADMIN | INSTRUCTOR | LEARNER`
- `status`: 선택 `ACTIVE | DELETED`; 생략하면 탈퇴 사용자를 포함한 전체
- `sort`: `RECENT` 기본(`createdAt DESC, id DESC`) 또는 `NAME`
- `page`/`size`: 기본 0/20, size 최대 100

목록은 `items`, `page`, `size`, `totalElements`, `totalPages`를 반환합니다. 각 item은
`id`, `email`, `name`, `role`, `status`, `authProvider`, `createdAt`만 포함합니다.
`passwordHash`, `googleSub`, refresh token 등 크리덴셜 필드는 관리자 DTO에 정의하지 않아
직렬화 경로 자체에서 차단합니다.

### GET `/api/admin/users/{id}`

목록 필드에 `affiliation`, `consentedAt`을 추가한 상세를 반환합니다. 없는 사용자는
`USER_NOT_FOUND`(404)입니다.

### GET `/api/admin/classrooms?sort=&page=&size=`

`sort`는 `RECENT` 기본(`createdAt DESC, id DESC`) 또는 `NAME`이고, page/size 기본과
상한은 회원 목록과 같습니다. 각 item은 `id`, `name`, `instructor:{id,name}`,
`memberCount`, `status`, `createdAt`을 포함합니다. 멤버 수는 현재 페이지의 강의실 ID를
한 번의 GROUP BY 쿼리로 집계하므로 페이지 크기에 비례하는 쿼리를 실행하지 않습니다.

### GET `/api/admin/classrooms/{id}`

목록 필드와 `members:[{userId,name,role,joinedAt}]`을 반환합니다. 없는 강의실은
`CLASSROOM_NOT_FOUND`(404)입니다.

### GET `/api/admin/ai-usage/summary?from=&to=`

`from`, `to`는 KST `yyyy-MM-dd` 일자이며 양끝을 포함합니다. 둘 다 생략하면 오늘을
포함한 최근 7일이고, `from > to` 또는 92일 초과 범위는 `VALIDATION_FAILED`(400)입니다.
DB에서 `DATE(CONVERT_TZ(created_at, '+00:00', '+09:00'))`로 일자 버킷을 만들며 MySQL
타임존 테이블에는 의존하지 않습니다.

- `daily`: `date`, `callCount`, `successCount`, `failCount`, `inputTokens`,
  `outputTokens`, `reasoningTokens`
- `features`: `feature`, `callCount`, `inputTokens`, `outputTokens`, `reasoningTokens`

집계는 DB의 GROUP BY로 수행합니다. 토큰 `SUM`은 SQL 의미를 유지해 개별 null 값을
합계에서 제외하고, 그룹의 모든 값이 null이면 응답 합계도 null입니다.

### GET `/api/admin/ai-usage/users?from=&to=&limit=`

기간 규칙은 summary와 같고 `limit`은 기본 20, 최대 100입니다. `callCount DESC,
userId ASC` 순으로 `items:[{userId,email,name,status,callCount,inputTokens,outputTokens,
reasoningTokens}]`을 반환합니다. users 테이블과 DB에서 조인하며 `DELETED` 사용자의 로그도
포함합니다.

## 8. Spring → FastAPI 내부 API

### 호출 주체 원칙 (하이브리드)

- **자유 학습 턴**(질문, 설명 요청, 퀴즈 유형 선택, 진단 답변, 교정 후 질문): Spring은 어떤 AI 에이전트를 쓸지 판단하지 않고 `/internal/ai/turn` 단일 진입점으로 이벤트와 스냅샷을 전달합니다. 에이전트 선택은 FastAPI Orchestrator의 책임입니다. 오개념 교정(RepairAgent)과 메모리 후보 생성·승격도 turn 내부 도구로 실행합니다.
- **퀴즈 제출 후 결정적 파이프라인**: `QUIZ_SUBMITTED` 처리에서 Spring이 전용 내부 API를 순차 호출합니다 — [SHORT/ESSAY만] `grade` → `quiz-assessment` → [기준 점수 미달 시] `diagnosis`. 트리거가 이벤트 타입과 점수 기준으로 완전히 결정되므로 이는 판단이 아니라 규칙 실행이며, README의 "Spring은 에이전트를 판단하지 않는다" 원칙과 충돌하지 않습니다.
- 파이프라인 구간의 교수 정책(예: 통과한 퀴즈에 진단을 실행하지 않음)은 Orchestrator Policy가 아니라 Spring의 점수 기준 규칙이 구조적으로 보장합니다.

| Method | URL | 목적 | 호출 시점 |
| --- | --- | --- | --- |
| POST | `/internal/ai/extract` | PDF 페이지 텍스트 추출 (LLM 판단 없는 결정적 전처리 — DEC-006) | 자료 업로드 후 비동기 처리 |
| POST | `/internal/ai/files` | 기존 PDF를 xAI Files에 업로드(추출 없음) | 기본 OFF bounded backfill |
| DELETE | `/internal/ai/files/{fileId}` | xAI Files 원본 삭제 (404 포함 멱등) | 자료 삭제·file ID 교체 시 DB 커밋 후 베스트에포트 |
| POST | `/internal/ai/outline` | 저장된 전 페이지 텍스트 기반 자료 요약·목차 생성 | 추출 저장 완료 후 비동기 처리 |
| POST | `/internal/ai/captions` | PDF 페이지 이미지 기반 시각 정보 캡션 생성 | 추출 저장 완료 후 10페이지 단위 비동기 처리 |
| POST | `/internal/ai/doc-chat` | 자료·퀴즈 복습 문맥 기반 단일 질문 응답 | 자료 뷰어 또는 퀴즈 복습 질문 시 동기 처리 |
| POST | `/internal/ai/criteria/suggest` | READY 자료 개요 기반 강의실 평가 기준 제안 | 소유 강사의 자동 생성 요청 후 비동기 처리 |
| POST | `/internal/ai/turn` | 자유 학습 턴 계획·실행 (설명, QA, 퀴즈 생성, 교정, 메모리 후보·승격 포함) | turns 이벤트 수신 시 |
| POST | `/internal/ai/grade` | SHORT/ESSAY 채점 — 결정성 설정(temperature 최저 등)으로 동일 답안 재채점 편차를 최소화 | 통합 퀴즈 또는 별도 시험에서 응답이 있는 SHORT/ESSAY 유형별 1회 |
| POST | `/internal/ai/quiz-assessment` | 퀴즈 내부 평가 생성 | 퀴즈 제출 파이프라인 2단계 (채점 완료 후 항상) |
| POST | `/internal/ai/diagnosis` | 진단 질문 생성 | 퀴즈 제출 파이프라인 3단계 (기준 점수 미달 시) |
| POST | `/internal/ai/exams/draft` | 시험 문항 AI 초안 생성 | 소유 강사의 DRAFT 시험 초안 요청 시 동기 호출 |

body가 없는 204를 제외한 내부 API 성공 응답은 최상위 optional `usage`를 사용합니다. wire 키는 `model`, `inputTokens`, `outputTokens`, `reasoningTokens`이고 `usage`와 각 하위 값은 nullable입니다. LLM을 여러 번 호출하면 재생성을 포함해 합산하되, 한 호출이라도 토큰 수를 확인할 수 없으면 불완전한 합계를 기록하지 않고 `usage=null`로 반환합니다. Spring은 확인 가능한 usage를 사용자별 `ai_usage_log`에 기록합니다.

`extract`는 멀티파트로 PDF 바이트를 받아 페이지별 텍스트 배열(`pages: [{ pageNumber, text }]`, `pageCount`)과 nullable `xaiFileId`, 기본 빈 배열 `warnings: [{type,message}]`를 반환하며, 저장과 상태 전이는 Spring이 수행합니다. `EDUPILOT_XAI_FILES_ENABLED=true`일 때만 추출 성공 원본을 xAI Files에 업로드합니다. 업로드 실패 또는 48MiB 초과는 `xaiFileId=null`과 `FILE_UPLOAD_FAILED` warning으로 강등하며 추출 응답은 200을 유지합니다. `FILE_UPLOAD_FAILED` 및 알 수 없는 warning type은 경고로만 기록하고 추출이 성공했다면 자료는 기존대로 READY가 됩니다. Spring은 non-blank `xaiFileId`만 내부 DB에 저장하고 외부 자료 응답에는 노출하지 않습니다. 기존 ACTIVE·READY 자료는 기본 OFF인 Spring bounded backfill이 `POST /internal/ai/files`로 원본만 업로드하며, 이 명시적 API는 `/extract` 자동 업로드 kill switch와 독립적으로 동작합니다. backfill은 claim과 file ID 반영을 각각 짧은 row-lock 트랜잭션으로 처리하고 외부 호출 중에는 트랜잭션을 유지하지 않으며, 실패 시 READY 유지·6시간 기본 backoff·경합 file ID 베스트에포트 삭제를 적용합니다. 자유 학습 턴 context에는 nullable `xaiFileId`를 포함하며 `includeCurrentPage=false`이면 null을 보냅니다. AI Service는 설명 Plan과 설명·QA·퀴즈의 실제 LLM 호출, 개요 생성에 파일을 첨부하고 그 밖의 Plan·결정적 안내·Repair·Note에는 첨부하지 않습니다. 퀴즈는 checkpoint가 있으면 `quizContext`의 coverage 페이지 범위, 없으면 현재 페이지 단일을 앵커로 사용하며 개요는 전달된 pages 범위를 유지합니다. file ID가 없으면 기존 텍스트 경로를 사용합니다(DEC-035·037·039). `DELETE /internal/ai/files/{fileId}`는 kill switch와 무관하게 동작하며 삭제 성공·xAI 404는 모두 204, 그 밖의 xAI 오류는 502 `FILE_DELETE_FAILED`(`INTERNAL`, `retryable=true`)입니다. 자료 삭제나 file ID 교체 시 Spring은 트랜잭션 커밋 후 DELETE를 호출하며 실패해도 자료 삭제·READY 결과를 유지합니다. `captions`는 `{schemaVersion:"1.0", pages:[{pageNumber,imageBase64,extractedText}]}`를 최대 10페이지씩 받고 페이지별 nullable 캡션을 반환합니다. Spring은 캡션이 있으면 모든 페이지 텍스트 기반 AI 입력에 `\n\n[그림 설명] {caption}`을 읽기 시점에 병합하며 `material_pages.text_content` 원문은 유지합니다. 일부 청크 실패는 자료·개요 상태에 영향을 주지 않고 다음 청크 처리를 계속합니다. `diagnosis` 요청에는 직전 단계에서 생성된 `quizAssessment`, 오답 문항, 학생 답안, 강의 문맥을 포함합니다. 오개념 교정과 메모리 후보·승격의 전용 엔드포인트는 두지 않습니다 — 교정은 `DIAGNOSIS_ANSWER_SUBMITTED` 턴에서, 메모리는 Orchestrator의 `memoryWrite` 판단으로 turn 내부에서 실행합니다.

별도 시험 grade는 숫자 `examId`를 `quizId`로 사용합니다. `pageContext`와 `learnerMemoryDigest`는 생략·null을 허용하고 나머지 grade 요청 필드는 필수·non-null입니다. 응답이 있는 SHORT와 ESSAY는 각각 묶어 호출하며 한 유형이 실패해도 나머지 유형은 계속 호출합니다. 실제 호출의 `items`와 `studentAnswers`는 비어 있지 않아야 합니다. 상세 필드 강제력과 표준 오류 봉투는 `ai-integration-contract.md` v0.6 §6.2를 따릅니다.

별도 시험의 비동기 grade 호출에서 `AI_REQUEST_INVALID`을 받으면 Spring 요청 계약 결함으로 ERROR 로그를 남기고 재시도 없이 해당 제출을 `GRADING_FAILED`로 종결합니다. 이미 커밋된 제출을 보상 삭제하거나 원 POST에 500을 반환하지 않습니다(DEC-032). 통합 학습 퀴즈의 동기 파이프라인 오류 변환은 기존 계약을 유지합니다.

시험 문항 초안 내부 계약은 `ai-integration-contract.md` v0.6 §6.5와 `docs/contracts/exam-draft.schema.json`을 따릅니다. Spring은 120초 전용 read timeout으로 동기 호출하며 초안은 저장하지 않고 usage는 공통 `ai_usage_log`에 기록합니다.

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
      "message": "편차가 뭔지 모르겠어",
      "includeCurrentPage": true
    }
  },
  "context": {
    "xaiFileId": "file-abc123",
    "conversationSummary": null,
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
    "latestRepair": null,
    "memory": {
      "temporaryCandidates": []
    }
  }
}
```

`learnerLevel`은 `learner_memories.target_difficulty`이며 데이터가 없으면 `null`입니다. `learnerConfidence`는 같은 사용자×자료의 최근 assessment 5개 통과 비율로 파생합니다. 비율이 0.4 미만이면 `LOW`, 0.4 이상 0.7 이하면 `MEDIUM`, 0.7 초과면 `HIGH`이며 평가가 없으면 `null`입니다. `conversationSummary`는 선택 nullable 내부 필드입니다. 이전 대화의 압축 보조 문맥으로 Plan과 QA에 전달하며 최근 대화와 모순되면 최근 대화를 우선합니다. 생략 또는 null이면 기존 동작과 같습니다(`ai-integration-contract.md` §3.1·§6.10).

`xaiFileId`는 `string | null`이며 외부 응답에는 노출하지 않습니다. `currentPageText`는 `string | null`이며 null은 `USER_QUESTION`의 `includeCurrentPage=false`일 때만 허용합니다. 이 경우 `xaiFileId`, `previousPageText`, `nextPageText`도 null이며 `conversationSummary`는 유지할 수 있습니다. `EXPLAIN_CURRENT_PAGE`와 `QUIZ_TYPE_SELECTED`에서는 `currentPageText`가 필수이며 AI Service가 eventType과 context를 교차 검증합니다. `includeCurrentPage=false`인데 페이지 텍스트가 전달되면 AI Service는 전달된 context를 사용하고 Spring이 정합 책임을 집니다. 같은 조건에서 file ID가 전달돼도 AI Service는 첨부하지 않습니다.

`quizAssessments`는 현재 세션 기준 최근 5개의 평가 요약입니다(DEC-011 — DB는 전량 보존, 스냅샷은 세션 스코프 윈도우. 메모리 승격 판단용 user×material 교차 세션 최근 20개 조회는 별도 경로).

`memory.temporaryCandidates`는 현재 세션에서 저장된 `CANDIDATE` 상태 후보를 최신순 최대 10개 전달합니다. 각 항목은 `candidateId`, `type`, `content`, `confidence`, `evidenceRefs`를 포함하며, 세션 구분은 `evidenceRefs.sessionId`로 검증합니다. 최상위 `memoryWrite`가 반환되면 Spring은 선택 후보 전체에서 중복 없는 근거 2개 이상과 모든 후보의 confidence 0.70 이상을 다시 확인한 뒤 별도 트랜잭션으로 승격합니다.

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

DTO 상세·타임아웃·재시도·`usage` 필드는 [docs/ai-integration-contract.md](ai-integration-contract.md) v0.6이 기준입니다(turn 요청/응답 구조, grade/quiz-assessment/diagnosis/extract/exam draft DTO, 오류 category 5종 AUTH/TIMEOUT/SCHEMA/POLICY/INTERNAL과 Spring 매핑 포함).

내부 API 필수 정책:

- 외부에 공개하지 않습니다. FastAPI는 Docker 내부 네트워크에만 바인딩합니다.
- 서비스 간 인증: Spring이 모든 내부 호출에 `X-Internal-Token`(환경 변수 주입 시크릿) 헤더를 첨부하고 FastAPI가 검증합니다(DEC-014 Accepted).
- `schemaVersion`, `turnId`, timeout, 최대 payload 크기를 합의합니다.
- 알 수 없는 상태 패치나 UI 액션은 Spring이 거부합니다.
- FastAPI 오류 코드는 Spring 외부 오류로 안전하게 매핑합니다.

## 9. SSE 스트리밍 계약 (확정)

AI 응답 스트리밍은 SSE를 기본 전송 방식으로 사용합니다. 이벤트는 `status`,
`thought_summary`, `content_delta`, `ui_action`, `completed`, `error`이며,
`completed` 또는 `error`는 정확히 1회, 스트림의 마지막 이벤트입니다.

### 9.1 연결과 턴 호출 순서

1. FE가 `GET /api/sessions/{sessionId}/stream`을 fetch로 먼저 연결합니다.
2. 연결 성공 후 `POST /api/sessions/{sessionId}/turns`를 전송합니다.
3. Spring은 활성 SSE 연결이 있으면 FastAPI의 내부 NDJSON 스트림을 중계하고,
   없으면 기존 동기 JSON 턴 응답을 반환합니다.

- 인증: `Authorization: Bearer` 헤더 + ReadableStream 파싱(DEC-021).
  브라우저 `EventSource`는 Authorization 헤더를 지원하지 않으므로 사용하지
  않습니다.
- 응답 헤더: `Content-Type: text/event-stream`,
  `Cache-Control: no-cache`, `X-Accel-Buffering: no`.
- 세션당 활성 스트림은 하나입니다. 실행 중 중복 연결은
  `TURN_IN_PROGRESS(409)`입니다.
- heartbeat: 다른 이벤트가 없으면 10초마다 `:heartbeat` comment를
  전송합니다. `event`와 `data`가 없으며 FE는 무시합니다.
- `Last-Event-ID` replay와 SSE `id` 필드는 지원하지 않습니다. 재연결 시
  세션 상세와 메시지를 다시 조회합니다.
- fetch abort 또는 전송 단절을 감지하면 Spring이 FastAPI 상류 응답을 닫아
  취소하며 부분 답변은 저장하지 않습니다.
- 사용자가 `POST /api/sessions/{sessionId}/turns/cancel`로 중지하면 수신한 content
  유무에 따라 §5의 부분 저장 또는 `TURN_CANCELLED` 규칙을 적용합니다.

### 9.2 외부 SSE data 스키마

`status`, `thought_summary`, `content_delta`는 내부 전용 `type` 필드를
제거하고 다음 JSON만 data로 전달합니다.

```text
event: status
data: {"stage":"PLANNING"}

event: thought_summary
data: {"text":"학습 계획을 세우는 중입니다"}

event: content_delta
data: {"text":"편차는 "}

:heartbeat
```

사용자 위젯은 Spring이 §5 W1~W7 규칙으로 정본화합니다. 퀴즈 제안은 내부 AI의
exact allowlist 입력을 받아 생성하고, 그 밖의 상태 전이 위젯은 Spring 규칙으로
생성합니다.
한 턴에는 마지막 상태 전이 위젯만 존재하므로 `ui_action`은 0회 또는
1회입니다. `data.action`은 §5 `uiActions` 항목 하나와 완전히 동일합니다.

```text
event: ui_action
data: {"action":{"type":"BINARY_DECISION","content":"퀴즈를 진행할까요?","yesEvent":"SHOW_QUIZ_TYPE_SELECT","noEvent":"WAIT"}}
```

진단 입력형 위젯은 yes/no 필드를 포함하지 않습니다.

```text
event: ui_action
data: {"action":{"type":"DIAGNOSIS_QUESTION","content":"왜 역수를 곱하는지가 막혔나요?","diagnosisId":30}}
```

`completed.result`는 Spring 외부 턴 응답이며 내부 `statePatch`,
`actionsExecuted`, `usage`, `memoryCandidates`를 포함하지 않습니다.

```text
event: completed
data: {"result":{"turnId":"turn-123","sessionId":100,"messages":[{"messageId":501,"senderType":"AI","messageType":"EXPLANATION","content":"편차는 평균과 관측값의 차이입니다.","pageNumber":3,"status":"COMPLETED","createdAt":"2026-07-28T09:00:00Z"}],"uiActions":[{"type":"BINARY_DECISION","content":"퀴즈를 진행할까요?","yesEvent":"SHOW_QUIZ_TYPE_SELECT","noEvent":"WAIT"}],"state":{"currentPage":3,"pageStatus":"EXPLAINED","activeQuizId":null}}}
```

오류 data는 Spring의 안정된 외부 오류 코드와 공개 메시지만 포함합니다.

```text
event: error
data: {"code":"AI_SERVICE_TIMEOUT","category":"TIMEOUT","message":"AI 서비스 응답 시간이 초과되었습니다.","retryable":true,"traceId":"01J..."}
```

### 9.3 검증·저장·timeout

- Spring은 `status`, `thought_summary`, `content_delta`, `heartbeat`,
  `completed`, `error` 외의 내부 이벤트를 거부합니다.
- `content_delta` 누적 문자열은 내부
  `completed.result.messages[].content`를 순서대로 이은 문자열과 같아야
  합니다.
- 내부 completed 전체 검증과 메시지·상태 저장 트랜잭션 커밋 후
  `[ui_action] → completed → 종료` 순서로 외부 terminal을 전송합니다.
- 내부 completed 응답 검증이 끝나면 SSE cancellation 상태와 무관하게 저장합니다. 저장 커밋 후 외부 `completed` 전송이 실패해도 저장된 턴을 실패 처리하지 않고 FE의 세션·메시지 복원 경로로 수렴합니다.
- error, terminal 전 EOF, schema 오류, 저장 실패에는 completed를 보내지
  않으며 중간 content는 확정 메시지로 저장하지 않습니다. 단, 명시적인 사용자
  취소는 누적 content가 있으면 validator와 일반 상태 반영을 건너뛰고 텍스트만
  저장한 뒤 completed로 종료합니다.
- 완성된 내부 이벤트를 30초 동안 받지 못하면
  `AI_SERVICE_TIMEOUT/TIMEOUT`으로 종료합니다. heartbeat도 이벤트 수신으로
  인정합니다.
- 스트림 턴 총 상한은 최초 FastAPI 호출부터 200초입니다. heartbeat가
  계속 와도 연장하지 않으며 제한된 재시도도 같은 총 예산을 공유합니다.
- retryable 오류는 content delta 전달 전까지만 최대 1회 자동 재시도합니다.
  일부 content가 전달된 뒤에는 서로 다른 시도의 본문을 섞지 않도록
  재시도하지 않습니다.
