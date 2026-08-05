# 화면-API 매핑 문서

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 |
| 마지막 갱신 | 2026-08-04 |
| 대상 | Frontend · Spring Backend |

## 1. 화면별 매핑

| 화면/영역 | 사용자 행동/시점 | API | 성공 시 UI | 주요 오류 |
| --- | --- | --- | --- | --- |
| 회원가입 | 이메일 입력 중 중복 확인 | `GET /api/auth/email-availability?email={email}` | 사용 가능 여부 표시 | 이메일 누락·형식 오류 |
| 회원가입 | 역할·선택 소속·수신 동의·약관 버전 제출 | `POST /api/auth/signup` | 확장 사용자 응답 확인 후 로그인 화면 또는 자동 로그인 정책에 따른 이동 | 역할/약관 버전 오류, 유효성, 이메일 중복 |
| 로그인 | 제출 | `POST /api/auth/login` | 토큰 저장 후 자료 목록 이동 | 자격 증명 실패, 비활성 계정 |
| 앱 초기 진입 | 인증 상태 확인 | `GET /api/users/me` | 사용자 정보/권한 반영 | 토큰 만료 |
| 계정 설정 | 이름·소속 수정 | `PATCH /api/users/me` | 확장 사용자 정보 갱신 | 빈 변경, 길이 오류 |
| 계정 설정 | 아바타 업로드·교체·삭제 | `POST·GET·DELETE /api/users/me/avatar` | 인증 fetch로 Blob object URL 생성·교체 | 형식/2MiB 초과, 인증 실패 |
| 계정 설정 | 학습 환경설정 조회·수정 | `GET·PATCH /api/users/me/preferences` | 알림 설정·AI 답변 스타일 저장 | 빈 변경, enum 오류 |
| 피드백 화면/모달 | 피드백 제출 | `POST /api/feedback` | 접수 ID·시각 확인 후 완료 표시 | 비인증, category·내용 길이 오류 |
| 강의실 목록 | 화면 진입·검색·정렬·페이지 이동 | `GET /api/classrooms` | 역할별 소유/참여 강의실, 진도·최근 학습 또는 승인 대기 수 표시 | 권한, 페이지네이션 |
| 강의실 개설 | 생성 폼 제출 | `POST /api/classrooms` | 계산된 주차 수·초대 코드가 포함된 상세로 이동 | INSTRUCTOR 권한, 날짜·색상 검증 |
| 강의실 상세 | 화면 진입 | `GET /api/classrooms/{id}` | 기간·현재 주차·인원·역할별 상세 표시 | `CLASSROOM_NOT_FOUND` |
| 강의자 학습 현황 | 대시보드 진입·새로고침 | `GET /api/classrooms/{id}/analytics` | 멤버·진도·최근 7일 질문/비활성·자료별 조회율·페이지별 질문 수 표시 | 소유 INSTRUCTOR, `CLASSROOM_NOT_FOUND` 은닉 |
| 강의실 설정 | 정보·기간 수정, 주차 공개일 동시 이동 선택, 완료 전환 | `PATCH·DELETE /api/classrooms/{id}` | `startDate` 변경 시 `shiftWeekReleaseDates`에 따라 주차 공개일 이동, 수정 상세 또는 COMPLETED 읽기 전용 상태 반영 | 날짜·주차 범위 충돌, 소유권, 완료 상태 |
| 강의실 설정 | 강의실명 재입력 후 영구 삭제 | `DELETE /api/classrooms/{id}/permanent` | 앞뒤 공백 제거 후 `confirmName`이 정확히 일치하면 강의실 목록으로 이동. 자료·기존 학습 이력·개인 일정은 유지 | 소유 INSTRUCTOR, 불일치 400, 학생·비소유·삭제 후 재시도 404 |
| 초대 관리 | 코드 확인·재발급 | `GET /api/classrooms/{id}/invite-code`, `POST .../regenerate` | 새 코드를 복사·공유 | 소유권, 완료 상태 |
| 강의실 참여 | 초대 코드 제출·내 요청 조회 | `POST /api/classroom-join-requests`, `GET /api/classroom-join-requests/me` | PENDING 상태와 처리 결과 표시 | 무효 코드, 멤버/대기 요청 중복 |
| 참여 요청 관리 | 요청 목록·승인·거절 | `GET /api/classrooms/{id}/join-requests`, `POST .../{requestId}/approve|reject` | 목록에서 처리 상태·학습자 정보 갱신 | 이미 처리됨, 완료 상태 |
| 주차·자료 | 주차 목록·생성·수정·삭제·상태·순서 변경 | `GET·POST /api/classrooms/{id}/weeks`, `PATCH·DELETE .../weeks/{weekNumber}`, `PATCH .../weeks/{weekId}/status`, `PATCH .../weeks/reorder` | `weekId`로 상태 변경, `displayOrder`로 정렬하며 `weekNumber`는 유지. 학습자는 노출 판정을 통과한 주차만 표시 | 전체 주차 ID 집합 검증, 주차 중복·범위·소유권·완료 상태 |
| 주차·자료 | 기존 자료 연결·해제 | `POST·DELETE /api/classrooms/{id}/weeks/{weekNumber}/materials/{materialId}` | 주차 자료 목록 갱신 | 자료 중복 연결, 자료·강의실 소유권 |
| 강의실 자료 업로드 | PDF와 강의실·주차 part 제출 | `POST /api/materials` | 처리 중 자료를 해당 주차에 즉시 표시 | INSTRUCTOR 소유권, part 조합·파일 오류 |
| 강의실 자료 학습 | 공개 자료 열기·통합학습 시작 | `GET /api/materials/{materialId}`, `GET .../file`, `POST /api/sessions` | PDF 뷰어와 사용자×자료 공유 세션으로 이동 | 공개 취소·연결 해제·멤버십 |
| 공지 | 목록·즉시 게시·수정·삭제 | `GET·POST /api/classrooms/{id}/notices`, `PATCH·DELETE .../notices/{noticeId}` | 최신 공지 목록 갱신 | 강사 권한, 완료 상태 |
| 캘린더 | 기간·강의실 필터 조회, 개인 일정 생성·수정·삭제 | `GET·POST /api/users/me/schedule`, `PATCH·DELETE /api/users/me/schedule/{scheduleId}` | 주차 공개·공지·본인 개인 일정을 시간순 표시하고 개인 일정만 편집 | 날짜·시간 범위, 강의실 접근권, 개인 일정 소유권 |
| 시험 관리 | 시험 생성·목록·상세·수정 | `POST·GET /api/classrooms/{classroomId}/exams`, `GET·PATCH /api/exams/{examId}` | DRAFT 편집기와 전체 상태 목록 표시. rubric 편집기는 기본 접힘·미입력 상태 | 강사 권한, 완료 강의실, DRAFT 편집 상태 |
| 시험 관리 | 공개·마감·DRAFT 삭제 | `POST /api/exams/{examId}/publish`, `POST .../close`, `DELETE /api/exams/{examId}` | 상태 배지와 응시 가능 여부 갱신 | `EXAM_NOT_EDITABLE`, `EXAM_NOT_PUBLISHED` |
| 시험 결과 관리 | 학생별 최신 제출·특정 시도 조회 | `GET /api/exams/{examId}/submissions`, `GET .../submissions/{submissionId}` | 운영 화면은 전체 상태의 최신 attempt를 표시. 성적·리포트 대표값은 최신 GRADED attempt | 시험 소유권, 페이지네이션 |
| 시험 응시 | 공개·마감 시험 목록과 상세 조회 | `GET /api/classrooms/{classroomId}/exams`, `GET /api/exams/{examId}` | PUBLISHED는 응시 UI, CLOSED는 읽기 전용 결과 UI | DRAFT는 `EXAM_NOT_FOUND`로 은닉 |
| 시험 응시 | 답안 제출·통신 재시도·재응시 | `POST /api/exams/{examId}/submissions` | 응답 `status`로 분기. 같은 제출 재시도는 같은 `requestId`, 재응시·GRADING_FAILED 재제출은 새 `requestId` | CLOSED, SUBMITTED 중복, 재응시 불가, 답안 형식 오류 |
| 시험 결과 | 내 최신 또는 지정 시도 조회 | `GET /api/exams/{examId}/submissions/me?attemptNo=` | SUBMITTED는 2초 polling→30초 뒤 5초, terminal에서 중단. 31분 초과 시 마지막 조회 후 문의 안내 | 접근 권한, 시도 없음 |
| 리포트 학생 선택 `/classrooms/:classroomId/reports` | 수강생 목록·제외 | `GET·DELETE /api/classrooms/{classroomId}/students[/{studentId}]` | 프로필·가입일·최근 학습 시각을 표시하고 제외 후 목록 갱신 | 강의실 관리 권한, 제외된 학생 404 |
| 학생 리포트 `/classrooms/:classroomId/students/:studentId/reports` | 버전 목록 조회·FULL/WEEK 생성 | `GET·POST /api/classrooms/{classroomId}/students/{studentId}/reports` | 202의 `reportId`를 유지하고 `pollAfterSeconds` 간격으로 상세 polling | 범위·주차 검증, 학생 소속, 강의실 관리 권한 |
| 리포트 상세 `/reports/:reportId` | 생성 상태·실패 fallback·완료 결과 조회 | `GET /api/reports/{reportId}` | PROCESSING 표시, FAILED 사실 요약, COMPLETED 점수·단계·trend·근거 표시. null score는 데이터 부족으로 표시 | `REPORT_NOT_FOUND`, AI failureCode |
| 리포트 기준 `/classrooms/:classroomId/report-criteria` | 기본·커스텀 목록, 기준 생성·버전 변경·활성 토글 | `GET·POST /api/classrooms/{classroomId}/report-criteria`, `PATCH .../{criterionId}` | 기본 9종과 활성 커스텀을 표시하고 변경은 다음 생성부터 적용 | 기준 20개 상한, 정규화 이름 중복, 소유권 |
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
| 학습 세션 | 대화 새로 시작 | `POST /api/sessions/{sessionId}/conversations` | 기존 화면 이력은 유지하고 이후 AI 대화 문맥만 새 경계로 시작 | 진행 중 턴·비활성 세션·소유권 |
| 학습 세션 | 노트 작성 | `POST /api/sessions/{sessionId}/notes` | 현재 자료 노트 목록에 추가 | 세션 소유권, 내용·페이지·메시지 참조 오류 |
| 학습 세션 | 자료 노트 진입·페이지 이동 | `GET /api/materials/{materialId}/notes?page&size` 또는 `GET /api/sessions/{sessionId}/notes?page&size` | 같은 자료 범위 노트를 최신순으로 표시 | 자료·세션 소유권, 페이지네이션 오류 |
| 학습 세션 | 노트 내용 수정 | `PATCH /api/notes/{noteId}` | 수정된 내용·시각 반영 | `NOTE_NOT_FOUND`, 내용 길이 오류 |
| 학습 세션 | 노트 삭제 | `DELETE /api/notes/{noteId}` | 목록에서 제거 | `NOTE_NOT_FOUND` |
| PDF 뷰어 | 다음/이전/번호 입력 | `PATCH /api/sessions/{sessionId}/page` | 응답 페이지로 뷰어 동기화, 설명 여부 UI | 페이지 범위/상태 충돌 |
| 채팅 | 스트림 선연결 | `GET /api/sessions/{sessionId}/stream` | fetch+Bearer로 SSE 연결 후 turns 호출 | 중복 연결/AI 스트림 중단 |
| 채팅 | 설명 시작 선택 | `POST /api/sessions/{sessionId}/turns` | 설명 스트림/메시지 표시 | AI timeout/스키마 오류 |
| 채팅 | 질문 전송 | 같은 turns API | QA 답변과 후속 질문 문맥 반영 | 빈 질문/AI 오류 |
| 채팅 | 진단 답변 제출 | 같은 turns API | 오개념 교정 답변 표시 | 진단 상태 충돌 |
| 퀴즈 유형 선택 | MCQ/OX/SHORT/ESSAY 선택 | 같은 turns API | 응답의 `state.activeQuizId`로 퀴즈 문항 조회 후 UI 열기 | 지원하지 않는 타입 |
| 퀴즈 풀이 | 문항 표시/새로고침 복원 | `GET /api/quizzes/{quizId}` | 공개 문항 렌더링 | 퀴즈 없음/세션 권한 |
| 퀴즈 풀이 | 제출 | `POST /api/quizzes/{quizId}/submit` | 동기 채점·평가 결과, 기준 미달이면 `DIAGNOSIS_QUESTION` 표시 | 중복 제출/답안 오류. 제출 후 AI 파이프라인 실패는 기본 이동 액션으로 격리 |
| 학습 기록 | 퀴즈 탭 진입 | `GET /api/sessions/{sessionId}/quizzes` | 퀴즈/점수 요약 | 세션 권한 |
| 학습 분석 | 메모리 화면 진입 | `GET /api/users/me/memory?materialId={materialId}` | 해당 자료의 공개 가능한 개인화 요약 | 데이터 없음 |
| 학습 세션 | 종료 버튼 | `POST /api/sessions/{sessionId}/complete` | 완료 화면/목록 이동 | 이미 완료/상태 충돌 |

스트리밍 턴은 `GET /stream`을 먼저 연결한 뒤 `POST /turns`를 전송합니다.
SSE 연결이 없으면 turns API는 기존 동기 JSON 응답으로 동작합니다. 스트림 중단
후에는 세션 상세·메시지를 다시 조회해 동기화하고, 수동 재시도에는 새
`requestId`를 사용합니다. 같은 `requestId`는
`TURN_ALREADY_PROCESSED(409)`로 거부하며 `Last-Event-ID` replay는 지원하지
않습니다.

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
| 현재 페이지 설명 | `EXPLAIN_CURRENT_PAGE` | `detailLevel` 선택 — 생략 시 사용자 `aiAnswerStyle` 적용 |
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
- 별도 시험의 정답·해설·모범 답안·rubric은 DEC-031 D4 확정 전까지 제출 후에도 비노출
- 장기 메모리 승격 내부 점수/근거 원문

## 5. 공동 합의 필요

- 로그인 토큰 저장/갱신 UX
- S3 전환 시 PDF 뷰어의 presigned URL 연동 방식(현재는 Spring 인증 스트리밍)
- 메시지와 퀴즈 목록 페이지네이션
- SSE 이벤트 schema와 heartbeat·취소·`Last-Event-ID` 재연결 (인증은 fetch 스트림 + Authorization 헤더로 확정 — DEC-021)
- 처리 중 PDF와 AI 장시간 작업 표시
- 통합 학습 퀴즈 재제출 정책
- 별도 시험 정답·해설 공개 시점(현재 임시 비공개)
- 오류별 사용자 문구와 재시도 버튼 정책
- 타 사용자 아바타가 필요한 Epic 10 강의실 범위에서 공개 또는 사용자 ID 기반 아바타 endpoint 검토
- 강의실 색상은 `BLUE | GREEN | PURPLE | ORANGE | RED | GRAY`와 DEC-030의 고정 hex 매핑을 사용
