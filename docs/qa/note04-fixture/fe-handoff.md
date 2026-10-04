# FE 인증 handoff와 NOTE-04 인수 증거

## 비밀값 없는 인증 handoff

이 작업은 인증 계약 구현/수정 담당 세션과 병행하는 fixture 준비다.
인증 전달 계약은 [PR502 고정 문서](https://github.com/AutoAI-UTEUM/BE/blob/d0047567c2864e999e1c5bac781a39ac43de8a3e/docs/qa/fe-auth-contract/README.md)를 함께 읽는다.
이 문서의 존재가 실제 로그인·DOB·이메일 확인 준비 완료를 의미하지 않는다.
인증 준비와 실제 배포를 연결하는 조건은 [PR501의 배포 사전 점검](https://github.com/AutoAI-UTEUM/BE/blob/fd12b01ecfef34b45629e2a8efe4068a4b5861c1/docs/launch-deployment-preflight.md)을 참조한다.
PR495 후보의 신규 인증 동작이 현재 DEV에 적용됐다고 가정하지 않는다.

현재 인계 가능한 값은 run label, manifest ID, A/B 별칭, 고정 BE base, 합성 구성, mock 응답,
승인 대기 목록뿐이다. 실제 user/material/session/quiz IDs, 배포 SHA, 인증/동의 상태는 조회하지 않았다.
로컬 숫자 ID는 `idsAreDevIds=false`이고 실제 호출에 사용하지 않는다.

승인 후 FE handoff에는 아래 비밀 없는 메타만 포함한다.

| 항목 | 전달값 |
| --- | --- |
| 검증 버전 | 정확한 BE 배포 SHA, FE 인수 SHA, 환경 이름과 승인된 API base |
| 검증 범위 | 확정 manifest ID/run label/사용 기간/동결 기간 |
| 주체 | A/B 별칭, 승인된 실환경 user ID, LEARNER 역할, 인증 경로의 문서 참조 |
| 리소스 | A1/A2/B1의 실제 material/session ID 및 소유 alias, 101/1/1 quiz IDs/count hash |
| 인증 상태 | 합성 주체별 승인된 로그인 가능 여부, 필요 단계/만료 메타; token 값 없음 |
| 책임 | BE 실행·정리 담당, FE 인수 담당, 승인/증거 reference |

우선 FE 담당자가 승인된 전용 주체로 기존 로그인 절차를 직접 수행하는 방식을 검토한다.
로그인이 불가하면 인증 담당과 해결한 후 진행한다. 부모의 최신 가입/DOB/메일확인/동의 계약을 우회하거나
DB에 동의 값을 임의 주입하지 않는다. 계정 생성·동의·credentials 전달은 이 계획이나 로컬 검증에 포함되지 않는다.
비밀번호/token 공유가 별도로 필요하면 승인된 대상·기한·비공개 전달 수단을 먼저 확정한다.
GitHub PR/issue/문서/manifest/HAR에는 비밀번호·JWT·refresh cookie·OTP·메일주소·SSH key를 넣지 않는다.
로그인 직후 BE는 자기계정/소유권 최소 메타만 확인하며 공개 handoff에서는 authorization/cookie 헤더를 제거한다.

## 현행 FE224의 조회와 재시도 흐름

10/4 FE 후속 요청을 반영해 초기 PR503의 더 보기/실패 page1만 재시도 인수 문구를 정정했다.
새 UI 요구를 추가하지 않는다. [FE PR224](https://github.com/AutoAI-UTEUM/FE/pull/224)는 병합됐고
현행 FE develop source head는 `1b6987d8472a064a080a00bd43232993e9bd41eb`다.
실제 배포 FE224라는 상태는 FE 전달 기준이며 이 세션이 배포 build를 측정한 결과는 아니다.

| source | 확인한 현재 동작 |
| --- | --- |
| [sessionsRepository.ts](https://github.com/AutoAI-UTEUM/FE/blob/1b6987d8472a064a080a00bd43232993e9bd41eb/src/features/sessions/sessionsRepository.ts#L326) | `listQuizzes`는 기존 query 없는 단일 GET. 복습 화면의 `listQuizHistory`만 첫 query 없는 GET 뒤 완전한 메타가 있으면 page1 이상을 순차 자동 조회한다. `hasNext=false`에서 종료한다. 메타 없는 legacy 응답은 한 번만 읽는다. |
| [LearnerReviewQuizzesPage.tsx](https://github.com/AutoAI-UTEUM/FE/blob/1b6987d8472a064a080a00bd43232993e9bd41eb/src/app/pages/learner/LearnerReviewQuizzesPage.tsx#L58) | 세션은 최대 4개 동시 조회한다. 한 세션의 모든 페이지 성공 후 그 세션 batch를 모으며, 현재 시도의 workers가 끝난 뒤 결과를 반영한다. 페이지별 부분 목록을 UI에 append하지 않는다. 더 보기 버튼이 없다. |
| [같은 source의 retry](https://github.com/AutoAI-UTEUM/FE/blob/1b6987d8472a064a080a00bd43232993e9bd41eb/src/app/pages/learner/LearnerReviewQuizzesPage.tsx#L62) | 늦은 페이지 실패는 해당 세션 전체 실패다. 다른 성공 세션 batch는 유지한다. 다시 시도는 실패 세션만 `listQuizHistory`를 새로 호출하여 query 없는 page0부터 전체 조회한다. loading 동안 retry 버튼을 비활성화한다. |
| [계정 key/abort](https://github.com/AutoAI-UTEUM/FE/blob/1b6987d8472a064a080a00bd43232993e9bd41eb/src/app/pages/learner/LearnerReviewQuizzesPage.tsx#L30) | 계정/역할 key로 collection 범위를 교체한다. effect cleanup은 abort하고, 늦은 응답은 결과에 반영하지 않는다. |

실제 repository 함수·추출한 조회 effect callback·정렬 helper를 읽기 전용으로 Node에서 실행하고
합성 SQLite 응답/가짜 500·429/지연 응답에 연결한 **9개 오프라인 검사**가 통과했다.
React mount/render·브라우저·실HTTP·실인증은 실행하지 않았다. 해당 증거는 `LOCAL_FE_CONSUMER_MOCK`이며
실제 FE 화면 인수는 아직 `NOT_RUN`이다.

## BE 실응답으로 확인할 항목 — 전부 실행 대기

endpoint: `GET /api/sessions/{sessionId}/quizzes?page=1&size=100`.
목록 page 기본값 0, size 기본값 100, 허용 범위 1..100, 정렬 `createdAt DESC, quizId DESC`다.
페이지 배열은 `data.quizzes`; `quizzes[].page`는 PDF 페이지이고 `data.page`가 목록 페이지다.
미제출의 score/maxScore/passed는 null이며 정답/루브릭은 목록에 없다.

| case | 승인 후 주체/조회 | 기대값 | 필요한 증거 |
| --- | --- | --- | --- |
| REAL-01 | A의 A1 page0,size100 | 200, 100개, page0,size100,totalElements101,totalPages2,hasNext=true | 실제 status와 data 메타, ordered quiz IDs/hash |
| REAL-02 | A의 A1 page1,size100 | 200, 1개, page1,hasNext=false | page0와 중복 없는 101번째 ID |
| REAL-03 | A의 A1 page2,size100 | 200, 0개, totalElements101,totalPages2,hasNext=false | 빈 배열과 메타 유지 |
| REAL-04 | A1 page1 반복 | 같은 1개 ID/내용, 변경 0 | 두 실제 요청의 IDs/hash 비교 |
| REAL-05 | A의 A2와 B의 B1 | 각각 자기 sentinel 1개; A1과 혼합 0 | 각 ordered IDs와 소유 alias |
| REAL-06 | A→B1, B→A1/A2 | 404 SESSION_NOT_FOUND | 실제 status/error.code; 리소스 메타 미노출 |
| REAL-07 | page=-1, size=0, size=101 | 400 VALIDATION_FAILED | 실제 status/error.code |
| REAL-08 | A1 101개 | createdAt 모두 동일, quizId DESC, 모두 미제출, PDF page7 | 해당 필드와 manifest invariant 비교 |

REAL-01~08은 BE GET/manifest 계약 인수다. page2는 명시적인 BE 계약 확인용이며 FE 자동 요청의 기대값이 아니다.
FE 복습 화면에서는 A1의 query 없는 page0→page1 자동 조회와 `hasNext=false` 종료를 관찰한다.
A 계정의 성공 목록은 A1 101개 + A2 sentinel 1개 = **102개**, B 계정은 B1 sentinel **1개**다.
이 수치는 [실행계획](dev-execution-plan.md)의 A/B 미삭제 session inventory가 승인된 2/1개와 같다는 사전조건을 갖는다.
FE가 자동 조회할 승인 밖 세션이 있으면 해당 quiz를 읽지 않고 인수를 중단한다.
A1의 page0 100개만 먼저 성공 목록으로 표시하는 것을 통과 조건으로 두지 않는다.
한 세션의 모든 페이지 성공/실패, 다른 성공 세션 보존과 최종 unique IDs를 관찰한다.
직접 BE 응답 관찰과 FE UI/자동 요청 trace를 별도 기록하고 정확한 FE source/build SHA를 함께 고정한다.

## 모의 오류/클라이언트 검증 — 실서버 오류를 유발하지 않음

아래는 승인된 GET success payload와 FE 테스트 환경의 mock 오류를 결합하는 계획이다.
현재 로컬 실행기의 success/400/404도 **SQLite 모델 응답**이므로 `LOCAL_MOCK_ONLY`다.
FE source를 사용한 오프라인 callback 검사는 실행했지만 브라우저/React/DEV 테스트는 실행하지 않았다.
아래 UI 인수 목록을 완료 증거로 사용하지 않는다.

| case | FE mock/행동 | 기대 동작 | 증거 분류 |
| --- | --- | --- | --- |
| MOCK-01 | A1 page0 성공 후 page1을 HTTP500으로 대체, A2는 성공 | A1 전체 실패로 부분 100개 미표시; A2 sentinel 1개 보존; 실패 세션 안내 | MOCK_FE_ONLY |
| MOCK-02 | MOCK-01 뒤 다시 시도, A1 성공 payload 복원 | 실패 A1만 query 없는 page0부터 page1까지 다시 조회; A2 재조회 없음; 최종 A 목록 102 unique IDs | MOCK_FE_ONLY + 승인 후 REAL success payload 참조 |
| MOCK-03 | A1 page1에 HTTP429 모의 | 현행 500과 같은 세션 단위 실패/다시 시도 안내; A1 부분 폐기·A2 보존·retry page0 시작; 새 rate-limit UI 추가 없음 | MOCK_FE_ONLY |
| MOCK-04 | 자동 조회의 page1 payload에 page0의 quizId 반복 | 세션 완료 반환값에서 quizId dedup; A1=101, A 전체=102 | MOCK_FE_ONLY |
| MOCK-05 | A1 page1 지연 중 화면 이탈/탭 닫기 | effect cleanup abort; 늦은 이전 조회 결과 미반영 | MOCK_FE_ONLY, abort는 HTTP status가 아님 |
| MOCK-06 | A→B 인증 변경 중 지연 응답 | A cache 폐기, 늦은 A 행 미표시, B1 sentinel만 표시 | MOCK_FE_ONLY + 승인 후 REAL 소유권 조회 참조 |

HTTP429 mock의 error code는 실제 해당 목록 endpoint에서 발생한다고 보장하지 않는다.
FE 기존 테스트와 같은 `SERVER_ERROR`/`RATE_LIMITED`는 합성 code이며 세션 실패 경고에서 구분되지 않는다.
실DEV quota 소진, AI 생성, 트래픽 폭주로 500/429를 만들지 않는다.
retry/dedup/abort는 FE 동작이므로 이 SQLite 테스트의 안정된 재조회 결과만으로 통과했다고 보고하지 않는다.
사후 데이터 변화가 있는 offset 조회에 snapshot 연속성 보장을 확대하지 않는다.

## 인수 결과 기록 형식

case마다 `runLabel`, `manifestId`, BE 배포/FE SHA, 검증시각, 주체 alias, 승인 reference,
`evidenceType`(REAL_HTTP / MOCK_FE_ONLY / LOCAL_MOCK_ONLY / LOCAL_FE_CONSUMER_MOCK), 요청 목록 page/size,
query 없는 page0 여부, 세션별 자동 요청 순서, HTTP status/error.code, ordered quiz IDs/hash,
UI 전/후 행수/unique count, 실패 세션 IDs, 보존한 성공 세션 IDs, retry의 page0 재시작/피어 재조회 여부,
abort/지연응답 처리 결과, redaction 여부와 담당을 기록한다.
mock으로 대체한 요청을 실제 응답 수에 넣지 않고 abort를 500으로 분류하지 않는다.
미실행은 `NOT_RUN`, 통과는 해당 evidence 범위만 `PASS`로 표시한다.

인수 전·후 manifest 조건을 재검사한다. 실제 submission이 생기거나 데이터가 변하면 동결 조건 위반으로
중단한다. 이 검증에서 submit POST, 학습 턴, 계정 생성/동의/메일/SMS를 호출하지 않는다.
현재 REAL/MOCK_FE case는 모두 `NOT_RUN`; 로컬 검사 결과는 [local-validation.md](local-validation.md)에만 기록한다.
