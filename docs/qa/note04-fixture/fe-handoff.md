# FE 인증 handoff와 NOTE-04 인수 증거

## 비밀값 없는 인증 handoff

이 작업은 인증 계약 구현/수정 담당 세션과 병행하는 fixture 준비다. 별도 auth 계약 문서의 최종
브랜치/head/문서 링크를 부모가 확정한 뒤 이 계획과 함께 인계한다. 아직 링크나 완료 상태를 추측하지 않는다.
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

## 실제 응답으로 확인할 항목 — 전부 실행 대기

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

FE UI에서 100개 표시 후 더 보기로 1개를 추가하여 총 101개가 되는지 확인한다.
`hasNext=false`에서 추가 자동 요청이 멈추는지 확인한다. page2는 명시적 확인용 요청이다.
HTTP 실제 응답 관찰과 FE 목록/버튼 동작 관찰을 별도 기록하고 FE commit SHA를 함께 고정한다.

## 모의 오류/클라이언트 검증 — 실서버 오류를 유발하지 않음

아래는 승인된 GET success payload와 FE 테스트 환경의 mock 오류를 결합하는 계획이다.
현재 로컬 실행기의 success/400/404도 **SQLite 모델 응답**이므로 `LOCAL_MOCK_ONLY`다.
FE 코드/브라우저 테스트를 이 세션에서 실행하지 않았다. 테스트 목록을 인수 완료 증거로 사용하지 않는다.

| case | FE mock/행동 | 기대 동작 | 증거 분류 |
| --- | --- | --- | --- |
| MOCK-01 | page0 성공 후 page1 응답을 HTTP500으로 대체 | 기존 100개 보존, 실패 안내, 다음 목록 page=1 유지 | MOCK_FE_ONLY |
| MOCK-02 | MOCK-01 뒤 page1 retry, 성공 payload 복원 | 같은 page=1 재요청, 1개 append, 101 unique IDs; page2 건너뜀 없음 | MOCK_FE_ONLY + 승인 후 REAL success payload 참조 |
| MOCK-03 | page1에 HTTP429 모의 | rate-limit 안내/동의된 재시도 UX; 기존 목록/같은 page 보존; 무제한 재요청 없음 | MOCK_FE_ONLY |
| MOCK-04 | page1 성공 payload 중복 전달/재요청 | quizId 기준 101개 유지, 중복 append 없음 | MOCK_FE_ONLY |
| MOCK-05 | A1 page1 응답 지연 중 A2 이동/탭 닫기 | AbortController/요청 세대 폐기; 늦은 A1 행이 A2에 섞이지 않음; A2 sentinel 1개 | MOCK_FE_ONLY, abort는 HTTP status가 아님 |
| MOCK-06 | A→B 인증 변경 중 지연 응답 | A cache 폐기, 늦은 A 행 미표시, B1 sentinel만 표시 | MOCK_FE_ONLY + 승인 후 REAL 소유권 조회 참조 |

HTTP429 mock의 error code는 실제 해당 목록 endpoint에서 발생한다고 보장하지 않는다.
FE 오류 처리 계약에서 선택한 합성 code임을 명시한다. 실DEV quota 소진, AI 생성, 트래픽 폭주로 500/429를 만들지 않는다.
retry/dedup/abort는 FE 동작이므로 이 SQLite 테스트의 안정된 재조회 결과만으로 통과했다고 보고하지 않는다.
사후 데이터 변화가 있는 offset 조회에 snapshot 연속성 보장을 확대하지 않는다.

## 인수 결과 기록 형식

case마다 `runLabel`, `manifestId`, BE 배포/FE SHA, 검증시각, 주체 alias, 승인 reference,
`evidenceType`(REAL_HTTP / MOCK_FE_ONLY / LOCAL_MOCK_ONLY), 요청 목록 page/size,
HTTP status/error.code, ordered quiz IDs/hash, UI 전/후 행수/unique count, retry page,
abort/지연응답 처리 결과, redaction 여부와 담당을 기록한다.
mock으로 대체한 요청을 실제 응답 수에 넣지 않고 abort를 500으로 분류하지 않는다.
미실행은 `NOT_RUN`, 통과는 해당 evidence 범위만 `PASS`로 표시한다.

인수 전·후 manifest 조건을 재검사한다. 실제 submission이 생기거나 데이터가 변하면 동결 조건 위반으로
중단한다. 이 검증에서 submit POST, 학습 턴, 계정 생성/동의/메일/SMS를 호출하지 않는다.
현재 REAL/MOCK_FE case는 모두 `NOT_RUN`; 로컬 검사 결과는 [local-validation.md](local-validation.md)에만 기록한다.
