# 학습 턴 실측 준비 도구

상태: **도구/오프라인 회귀 검증 준비**. 실제 xAI 비교 실행·품질 합격·속도 개선 확정이 아니다.
이번 구현 중 실 xAI 호출은 하지 않는다. `prepare`, `check`, `report`는 네트워크 0회다.

## 범위와 안전장치

- 로컬 FastAPI ASGI + 실제 xAI의 비교용이며 Spring/FE의 전달·렌더 시간은 포함하지 않는다.
- 변경 전/후의 **전체 AI 소스**를 별도 프로세스에서 import한다. 프롬프트 함수만 바꾸지 않는다.
  각 worker는 앱과 httpx pool을 유지하며 동시성 1로 전후 순서를 교차한다.
- 운영/개발 서버를 되돌리거나 worktree를 편집하지 않는다. 준비한 두 checkout을 읽기만 한다.
  `src`, pyproject, lock의 미커밋/미추적 변경을 거부하고, 각 턴 전에 commit/source tree를 재확인한다.
- 두 버전의 lock/pyproject 해시, Python/설치 라이브러리 버전, 실제 에이전트 프로필/설정을 비교한다.
  다르면 LLM 호출 전에 중단한다. fixture는 hash를 확인하고 turnId/traceId만 호출마다 새로 만든다.
- `run --live --env-file ... --max-provider-calls N`을 모두 지정해야 실제 호출한다.
  상한은 **양쪽 worker 합계**이고 전송 직전 SQLite에서 원자적으로 차감한다.
  네트워크/스키마 재시도와 실패한 전송도 포함한다. SDK/HTTP transport 자동 재시도는 추가하지 않는다.
- 상한 또는 실패 시 중단하고 부분 결과를 저장한다. 자동 재개/전체 재실행/추가 예산은 없다.
  worker를 종료·회수하며, 결과 디렉터리/파일을 덮어쓰지 않는다.
- Files 업로드/삭제는 이 도구에서 **금지**한다. 테스트 자료의 유효한 기존 xaiFileId를 재사용한다.
  해당 ID는 같은 xAI 키로 접근 가능해야 하며, 이전 임시 테스트 후 삭제된 ID를 사용하면 안 된다.
  새 업로드가 필요하면 별도 승인한 Files 업로드·정리 절차를 먼저 거친다.
- 키·인증 헤더·PDF/대화/답변 본문은 콘솔/공유 보고서에 넣지 않는다.
  입력·완료 응답·퀴즈 정답은 저장소 밖 0700 디렉터리의 비공개 0600 파일에 저장한다.
  `private/`를 PR/채팅/공유 보고서에 첨부하지 않는다. 확인 후 조직 보존 정책에 맞춰 정리한다.

## 1. 같은 시험 입력 준비 (비공개)

기존 테스트 자료에서 사용하는 **TurnRequest JSON 스냅샷**을 `snapshot`에 넣는다.
실제 학생 데이터 대신 합성 학습자 이력/테스트 계정을 사용하고, 임의의 운영 세션을 재생하지 않는다.

```json
{
  "snapshot": { "schemaVersion": "1.0", "turnId": "template", "session": {}, "event": {}, "context": {} },
  "question": "볼록 함수면 학습률과 관계없이 경사하강법이 항상 수렴하나요?",
  "followupQuestion": "방금 말한 학습률 조건을 어기면 어떤 일이 생기는지 예를 하나만 더 들어줘"
}
```

위 `snapshot`의 `{}`는 생략 표기다. 실제 계약 필드를 모두 포함한 테스트 요청을 사용한다.
`context.xaiFileId`, 현재 페이지 텍스트, 이전/다음 페이지 근거가 필요하다.
후속 QA를 위해 `qaThreadDigest.threadRef`와 `digest`, 관련 `recentMessages`를 제공한다.
`quizContext`를 제공하면 퀴즈에만 유지하고 나머지 요청에서는 제거한다.
새 질문 시나리오는 이전 QA thread/recentMessages/conversationSummary를 비워 일반 질문과 구분한다.
다른 학습자 문맥은 그대로 유지한다. 이력 원문은 이 입력 파일에서만 관리한다.

```bash
cd ai-service
uv sync --locked --dev
uv run python -m tests.benchmarks prepare \
  --input /absolute/private/input.json \
  --output /absolute/private/prepared-new
```

생성된 `suite.json`에는 설명, 일반 QA, 후속 QA, MCQ/OX/SHORT/ESSAY 7개 고정 요청과 평가표가 있다.
같은 자료/질문/학습자 상태를 전후에 사용한다. 실제 세션을 계속 진행하며 비교하지 않는다.
파일 ID 교체나 입력 수정 시 원본 입력을 수정한 뒤 `prepare`로 **새 suite**를 만든다.
hash만 수동으로 고쳐 과거 실험에 결과를 추가하지 않는다.

### Optimization 19페이지 권장 품질 시나리오

실측 때 실제 PDF/추출 텍스트와 다시 대조한다. 아래는 답변의 문구 일치가 아닌 내용 기준이다.

| 사례 | 입력/근거 | 사람이 판정할 항목 |
| --- | --- | --- |
| 설명 | p15 볼록성, p14 학습률 조건을 이전 문맥으로 제공 | 국소 최소=전역 최소와 반복법 수렴/최적점 유일성을 혼동하지 않음. 그림의 특징을 모든 함수의 성질로 일반화하지 않음 |
| 일반 QA | 위 예시 question, QA 이력 없음 | 임의 학습률에서의 무조건 수렴을 부정하고 필요한 조건/확인 불가 범위를 밝힘 |
| 후속 QA | 학습률/발산을 다룬 이전 2회 질문을 digest/recentMessages로 제공 | 직전 학습률 주제를 실제로 이어받아 예시를 제공. 새로 일반 정의만 반복하지 않음 |
| 퀴즈 4종 | p15 단일 또는 명시 quizContext, 전후 같은 범위 | 범위·난이도·정답·해설/기준이 일치하고 조건 없는 보장을 정답으로 만들지 않음 |

퀴즈 제안은 페이지별 학습 판단이다. p15 결과만으로 모든 페이지가 적절하다고 판정하지 않는다.
제안 타이밍을 추가 확인하려면 표지/개념 전환/설명 미완성 페이지를 별도 suite로 반복하고,
각 페이지에 제안이 필요한 이유·불필요한 이유를 먼저 정한다. 별도 호출 예산이 필요하다.
MCQ 정답 위치는 분포를 기록하되 소수 문항의 2번 빈도만으로 편향을 확정하지 않는다.

## 2. 버전별 사전 확인 (호출 0회)

이번 #429만 비교하려면 baseline `b10d09f`, candidate `f80cb17c`다.
기존 clone에서 별도 읽기용 worktree를 준비하고 사용한다. 이미 사용하는 checkout을 전환하지 않는다.
`--before`/`--after`에는 저장소 루트, `--python`에는 locked 의존성이 설치된 Python 3.14 실행파일을 지정한다.
도구는 의존성을 설치하거나 업데이트하지 않는다.

```bash
uv run python -m tests.benchmarks check \
  --before /absolute/before-repo --after /absolute/after-repo \
  --suite /absolute/private/prepared-new/suite.json \
  --repetitions 3 --output /absolute/private/check-new
```

`check`는 실키 없이 가짜 키/차단 transport로 실제 버전의 요청 모델·프로필·import 경로를 검증한다.
**실키/잔액/파일 ID의 유효성이나 provider의 새 출력 스키마 수용을 검증한 것은 아니다.**
기본 7종 × 전후 × 3회 = **42턴/기본 60회 호출**이다. 노트 등 추가 액션·재생성은 늘 수 있다.
후속 QA를 빼고 6종만 비교하면 36턴/기본 48회다. 이는 예산 승인이나 실제 실행 횟수가 아니다.

`--cases explain,qa_new,quiz_mcq,quiz_ox`처럼 범위를 선택할 수 있다.
동일 설정을 강제하려면 `--settings /absolute/private/settings.json`을 양 명령에 지정한다.
키/토큰은 이 JSON에 넣지 않는다. 예시(모델/effort는 실제 비교 대상 설정으로 확정할 것):

```json
{
  "model_name": "grok-4.5",
  "orchestrator_reasoning_effort": "low",
  "explainer_reasoning_effort": "medium",
  "qa_reasoning_effort": "low",
  "quiz_reasoning_effort": "medium",
  "agent_max_tokens": 16384,
  "turn_timeout_seconds": 180,
  "edupilot_prompt_cache_layout_enabled": false,
  "edupilot_prompt_cache_routing_enabled": false
}
```

생략한 설정은 해당 버전의 Settings가 로드한다. `check`는 .env를 안 읽고, `run`은 지정 .env를 읽으므로
check와 run의 manifest에서 프로필을 대조한다. run 자체도 양쪽 실제 설정이 같은지 재검증한다.
출력/문항 수 상한을 낮춰 속도를 만드는 비교는 하지 않는다. 실제 생성 문항 수·본문 길이도 기록한다.

## 3. 승인 후에만 실행 (이번 작업에서는 하지 않음)

```bash
uv run python -m tests.benchmarks run --live \
  --env-file /absolute/ai-service/.env --max-provider-calls 72 \
  --before /absolute/before-repo --after /absolute/after-repo \
  --suite /absolute/private/prepared-new/suite.json \
  --repetitions 3 --output /absolute/private/run-new
```

72는 60회 기본 호출에 재시도 여유를 둔 **예시**다. 반드시 사용자 승인한 상한으로 바꾼다.
기존 16회 예산은 소진됐으므로 재사용하지 않는다. 실패 후 새 디렉터리로 실행하면 새 비용이 발생한다.
마지막 정상 턴이 상한을 채운 경우와 예산 때문에 중간에 끊긴 경우를 manifest.status로 구분한다.
첫 계약 실패 시 멈추므로 전후 한 쪽 표본만 남을 수 있다. 이를 속도 개선으로 해석하지 않는다.

## 4. 산출물/판정

- `manifest.json`: 전후 commit/source tree/lock, 실제 프로필, 순서, 호출 상한/사용량, 종료 상태.
  worker 정리 실패는 CLEANUP_FAILED로 종료하며 성공으로 표시하지 않는다.
  결과 수신 전에 중단된 전송은 unattributedProviderAttempts로 표시하고 전체 비용을 미상으로 둔다.
- `metrics.json`: 턴별 첫 본문/준비/완료/Planner 시간, 입력·출력·추론·캐시·비용·provider 모델,
  오류/재생성, 본문 길이, 문항 수, MCQ 정답 위치 분포. 본문은 없음.
- `report.md`: 성공 수/실패 수와 중앙값·범위. 자동으로 개선율/p95/교육 품질 PASS를 만들지 않음.
- `private/`: 고정 요청, 원래 NDJSON 응답(정답 포함), 안전한 계측 로그, 비용 상한 DB, 수동 평가표.

설명/QA는 status·heartbeat가 아닌 첫 유효 content_delta의 **서버 로그 시각**을 쓴다.
ASGI 클라이언트는 버퍼링하므로 수신 반복문의 시각을 TTFT로 쓰지 않는다.
본문 델타가 없는 퀴즈는 `resultReadyMs`로 측정한다. 중첩된 Planner/LLM/전체 시간을 더하지 않는다.
provider 사용량은 `(llmCallId, attempt)`로 중복 제거한다. 실패/중단 시도에 최종 usage가 없으면
해당 턴 총비용은 미상(null)이며 알려진 일부 비용을 전체 비용으로 보고하지 않는다.
캐시 토큰도 결측을 0으로 만들지 않는다. 최초 요청/프로세스 재시작이 cold cache라는 보장은 없다.

`private/quality-review.json`의 항목을 실제 PDF·응답과 대조해 PASS/FAIL/판정불가로 기록한다.
소스 페이지/문장 근거를 비공개 evidence에 남긴다. 판정 안 한 항목은 NOT_REVIEWED로 둔다.
**자동 계약 PASS ≠ 학습 품질 PASS**. 실제 이전 답변과 비교해 중요한 조건/근거/문항 내용이 줄었으면
속도가 개선돼도 채택하지 않는다. 질문/설명 유형·출력 길이·캐시 적중이 다른 표본은 구분한다.

보고서만 다시 만들 때(네트워크 없음, 기존 파일 덮어쓰기 없음):

```bash
uv run python -m tests.benchmarks report \
  --result /absolute/private/run-new --output /absolute/private/rebuilt-report.md
```

실측 후에만 캐시 설정·추가 구조 변경을 결정한다. 이 도구는 dev/prod 설정이나 배포를 변경하지 않는다.
