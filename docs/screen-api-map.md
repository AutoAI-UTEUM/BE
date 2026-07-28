# 화면-API 매핑 문서

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 |
| 마지막 갱신 | 2026-07-26 |
| 대상 | Frontend · Spring Backend |

## 1. 화면별 매핑

| 화면/영역 | 사용자 행동/시점 | API | 성공 시 UI | 주요 오류 |
| --- | --- | --- | --- | --- |
| 회원가입 | 제출 | `POST /api/auth/signup` | 로그인 화면 또는 자동 로그인 정책에 따른 이동 | 유효성, 이메일 중복 |
| 로그인 | 제출 | `POST /api/auth/login` | 토큰 저장 후 자료 목록 이동 | 자격 증명 실패, 비활성 계정 |
| 앱 초기 진입 | 인증 상태 확인 | `GET /api/users/me` | 사용자 정보/권한 반영 | 토큰 만료 |
| 전역 | access 만료(401) 시 | `POST /api/auth/refresh` (credentials 포함) | 새 access로 원요청 재시도 | TOKEN_INVALID → 로그인 이동 |
| 헤더/메뉴 | 로그아웃 버튼 | `POST /api/auth/logout` | 메모리 access 삭제 후 로그인 화면 | 없음(멱등) |
| 계정 설정 | 탈퇴 버튼 → 비밀번호 확인 모달 | `DELETE /api/users/me` | 토큰 정리 후 로그인 화면 이동 | 비밀번호 불일치 (DEC-028) |
| 자료 목록 | 화면 진입/페이지 이동 | `GET /api/materials` | 자료 카드 목록 | 권한, 네트워크 |
| 자료 업로드 | 파일 제출 | `POST /api/materials` | 처리 상태 표시 후 목록 반영 | 파일 형식/크기/처리 실패 |
| 자료 상세 | 화면 진입 | `GET /api/materials/{materialId}` | 제목, 페이지 수, 학습 시작 가능 여부 | 자료 없음/권한 |
| PDF 뷰어 | 자료 원본 표시 | `GET /api/materials/{materialId}/file` | 인증된 PDF 스트림 표시 | 자료 없음/권한 |
| 자료 목록/상세 | 삭제 버튼 → 확인 모달 | `DELETE /api/materials/{materialId}` | 목록에서 제외 | 활성 세션 존재(409 — 세션 정리 안내) |
| (dev 전용) PDF 디버깅 | 페이지 추출 텍스트 확인 | `GET /api/materials/{materialId}/pages/{pageNumber}` | 페이지 보조 정보 반영 | 운영 비노출(DEC-025) — dev/디버깅 프로파일 한정 |
| 학습 시작 | 시작 버튼 | `POST /api/sessions` | 세션 화면 이동, 초기 선택 UI 표시 | 자료 준비 안 됨 |
| 학습 재개 | 내 세션 목록 진입 | `GET /api/sessions` | 최근 세션 목록에서 재진입 | 권한, 네트워크 |
| 학습 세션 | 최초 진입/새로고침 | `GET /api/sessions/{sessionId}` | 페이지·상태·진행 중 `uiActions`·`activeQuizId` 복원 | 세션 없음/소유권 |
| 학습 세션 | 삭제 버튼 | `DELETE /api/sessions/{sessionId}` | 목록으로 이동, 목록에서 제외 | 상태 충돌/소유권 |
| 학습 세션 | 채팅 이력 복원 | `GET /api/sessions/{sessionId}/messages` | 이전 메시지 표시 | 페이지네이션 오류 |
| PDF 뷰어 | 다음/이전/번호 입력 | `PATCH /api/sessions/{sessionId}/page` | 응답 페이지로 뷰어 동기화, 설명 여부 UI | 페이지 범위/상태 충돌 |
| 채팅 | 설명 시작 선택 | `POST /api/sessions/{sessionId}/turns` | 설명 스트림/메시지 표시 | AI timeout/스키마 오류 |
| 채팅 | 질문 전송 | 같은 turns API | QA 답변과 후속 질문 문맥 반영 | 빈 질문/AI 오류 |
| 채팅 | 진단 답변 제출 | 같은 turns API | 오개념 교정 답변 표시 | 진단 상태 충돌 |
| 퀴즈 유형 선택 | MCQ/OX/SHORT/ESSAY 선택 | 같은 turns API | 응답의 `state.activeQuizId`로 퀴즈 문항 조회 후 UI 열기 | 지원하지 않는 타입 |
| 퀴즈 풀이 | 문항 표시/새로고침 복원 | `GET /api/quizzes/{quizId}` | 공개 문항 렌더링 | 퀴즈 없음/세션 권한 |
| 퀴즈 풀이 | 제출 | `POST /api/quizzes/{quizId}/submit` | 동기 채점·평가 결과, 기준 미달이면 `DIAGNOSIS_QUESTION` 표시 | 중복 제출/답안 오류. 제출 후 AI 파이프라인 실패는 기본 이동 액션으로 격리 |
| 학습 기록 | 퀴즈 탭 진입 | `GET /api/sessions/{sessionId}/quizzes` | 퀴즈/점수 요약 | 세션 권한 |
| 학습 분석 | 메모리 화면 진입 | `GET /api/users/me/memory?materialId={materialId}` | 해당 자료의 공개 가능한 개인화 요약 | 데이터 없음 |
| 학습 세션 | 종료 버튼 | `POST /api/sessions/{sessionId}/complete` | 완료 화면/목록 이동 | 이미 완료/상태 충돌 |

## 2. 학습 화면 상태 동기화

학습 화면은 서버 상태를 기준으로 다음 값을 유지합니다.

| 서버 필드 | FE 사용 위치 |
| --- | --- |
| `sessionId` | 모든 세션 API 경로 |
| `materialId` | PDF 자료 연결 |
| `currentPage` | PDF 뷰어 현재 페이지 |
| `pageStatus` | 설명/퀴즈/진단 UI 상태 |
| `status` | 입력 활성화 및 종료 상태 |
| `messages` | 채팅 타임라인 |
| `uiActions` | 버튼, 선택지, 진단 질문 렌더링 — 세션 조회 응답에도 포함되어 재진입 복원에 사용 |
| `pendingDiagnosis` | 진단 답변 UI 복원 |
| `activeQuizId` | 진행 중 퀴즈 풀이 화면 복원(`GET /api/quizzes/{quizId}`) |

FE가 낙관적으로 페이지를 먼저 움직이더라도 실패 시 Spring 응답의 `currentPage`로 되돌립니다. AI 메시지는 최종 완료 이벤트 또는 일반 응답을 받은 후 확정 상태로 표시합니다.

## 3. 이벤트 매핑

| UI 행동 | `eventType` | 필수 payload |
| --- | --- | --- |
| 현재 페이지 설명 | `EXPLAIN_CURRENT_PAGE` | `detailLevel` |
| 새 질문/후속 질문 | `USER_QUESTION` | `message` |
| 퀴즈 유형 선택 | `QUIZ_TYPE_SELECTED` | `quizType` |
| 진단 답변 | `DIAGNOSIS_ANSWER_SUBMITTED` | `diagnosisId`, `answer` |
| 교정 내용 추가 질문 | `USER_QUESTION` 재사용 | `message` — 교정 문맥은 Spring이 서버 측에서 연결하므로 FE는 별도 표시가 필요 없음 |

확정 규칙: `MOVE_NEXT_PAGE`는 turns 이벤트가 아니라 `PATCH /api/sessions/{sessionId}/page` 호출로 처리하고, 단순 `WAIT`는 API 호출 없이 FE 로컬로 처리합니다. `SHOW_QUIZ_TYPE_SELECT`는 FE 로컬 UI 표시(유형 선택 후 `QUIZ_TYPE_SELECTED` 전송)입니다.

`DIAGNOSIS_QUESTION`은 `{ "type": "DIAGNOSIS_QUESTION", "content": "<diagnosticPrompt>", "diagnosisId": 30 }` 형식입니다. FE는 `content`를 질문 본문으로 표시하고 답변 제출 시 같은 `diagnosisId`를 `DIAGNOSIS_ANSWER_SUBMITTED` payload에 포함합니다. 이 액션에는 `yesEvent`·`noEvent`가 없습니다.

turn 응답의 `state.activeQuizId`는 nullable입니다. 퀴즈 생성 턴에서는 Spring이 저장한 quiz ID이며 FE는 `GET /api/quizzes/{quizId}`로 공개 문항을 조회합니다. 일반 설명·QA·교정 턴에서는 현재 세션의 값을 그대로 반환합니다.

## 4. FE가 의존하면 안 되는 정보

- FastAPI 내부 엔드포인트
- Orchestrator의 세부 Plan 또는 비공개 reason
- Grok 프롬프트와 내부 추론
- 퀴즈 제출 전 정답·루브릭
- 장기 메모리 승격 내부 점수/근거 원문

## 5. 공동 합의 필요

- 로그인 토큰 저장/갱신 UX
- S3 전환 시 PDF 뷰어의 presigned URL 연동 방식(현재는 Spring 인증 스트리밍)
- 메시지와 퀴즈 목록 페이지네이션
- SSE 이벤트 schema와 heartbeat·취소·`Last-Event-ID` 재연결 (인증은 fetch 스트림 + Authorization 헤더로 확정 — DEC-021)
- 처리 중 PDF와 AI 장시간 작업 표시
- 퀴즈 재제출 및 결과 공개 정책
- 오류별 사용자 문구와 재시도 버튼 정책
