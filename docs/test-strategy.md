# Agent Server 테스트 전략 (초안)

| 항목 | 내용 |
| --- | --- |
| 상태 | 초안 v0.1 |
| 작성일 | 2026-07-23 |
| 전제 | 싱글톤 제거 → DI 기반 새 구조. 계약은 `ai-integration-contract.md` 기준 |

---

## 1. 왜 현재 구조는 테스트가 어려운가

- 모듈 레벨 싱글톤: `_engine = OrchestrationEngine()` (session.py:30), `_bridge/_quiz/_grader` (bridge.py:37-39), 전역 `generator` (test_gen.py:32) 등이 **import 시점에 생성**됨.
  - 테스트에서 가짜 LLM으로 바꿔치기하려면 monkeypatch로 모듈 내부를 뜯어야 하고, 테스트 간 상태가 새어 나감.
- 에이전트가 Gemini 클라이언트를 내부에서 직접 생성 → LLM 없이 로직만 검증할 방법이 없음.
- 상태가 `Dict[str, Any]` → 테스트가 깨져도 스키마 위반인지 로직 버그인지 구분 불가.

## 2. 설계 원칙: "테스트 가능성은 구조에서 나온다"

### 2.1 App Factory 패턴

```python
# app/factory.py
def create_app(settings: Settings, deps: Dependencies | None = None) -> FastAPI:
    app = FastAPI(lifespan=make_lifespan(settings))
    app.include_router(turn_router)
    ...
    return app
```

- 전역 `app = FastAPI()` 금지. 테스트마다 독립된 앱 인스턴스를 생성한다.
- Settings는 `pydantic-settings`로 주입 (env 직접 읽는 `os.getenv` 산재 금지).

### 2.2 의존성은 전부 FastAPI `Depends` + Protocol

```python
# llm/client.py
class LlmClient(Protocol):
    async def complete_json(self, *, messages, schema: type[BaseModel], model: str | None = None) -> BaseModel: ...
    def stream(self, *, messages, schema, model=None) -> AsyncIterator[LlmChunk]: ...

# api/deps.py
def get_llm(request: Request) -> LlmClient:
    return request.app.state.llm      # lifespan에서 1회 생성

def get_turn_service(llm: LlmClient = Depends(get_llm), ...) -> TurnService: ...
```

- 싱글톤이 필요하면(커넥션 풀 등) **lifespan에서 만들어 `app.state`에 두고 Depends로 꺼낸다** — "전역 변수 싱글톤"과 달리 앱 단위로 격리되어 교체 가능.
- 에이전트/서비스는 생성자 주입: `ExplainerAgent(llm=...)`. 내부에서 클라이언트를 만들지 않는다.
- 시간/ID도 주입: `Clock`, `IdGenerator` Protocol → 테스트에서 고정값 사용 (quizId, turnId, createdAt 검증 가능).

### 2.3 테스트에서 갈아끼우기

```python
# tests/conftest.py
@pytest.fixture
def app(fake_llm):
    app = create_app(Settings(_env_file=None, xai_api_key="test", ai_secret_key=""))
    app.state.llm = fake_llm                      # 또는
    app.dependency_overrides[get_llm] = lambda: fake_llm
    return app

@pytest.fixture
async def client(app):
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
```

---

## 3. 테스트 피라미드

### Level 1 — 단위 테스트 (LLM 무관, 대부분의 케이스가 여기)

- 대상: StateReducer 전이, Policy/Verifier 보정 규칙, MCQ/OX 결정론 채점, O/X alias 정규화, 퀴즈/채점 스키마 검증, statePatch 생성, questionId 매칭 검증.
- 순수 함수/작은 클래스로 만들어 fixture 없이 돌린다. 목표: 수 ms, 전체 로직 커버리지의 중심.

### Level 2 — 컴포넌트 테스트 (FakeLLM 주입)

- **FakeLlmClient**: 시나리오 스크립트 기반 가짜 구현.

```python
class FakeLlm:
    def __init__(self, script: dict[str, list[Any]]):  # agent/용도별 응답 큐
        ...
    # 응답 소진 시 명시적 실패 → 예상 밖 LLM 호출을 즉시 검출
```

- 각 에이전트를 FakeLLM으로 검증: 프롬프트에 필수 컨텍스트가 들어갔는지(요청 캡처), 출력 스키마 위반 응답 시 재시도/에러 경로.
- **실패 주입**이 핵심: timeout, 429(quota), malformed JSON, 스트림 중단 → fallback/error 이벤트 계약 검증.

### Level 3 — 계약 테스트 (엔드포인트 레벨, PR 게이트의 핵심)

- httpx `ASGITransport`로 실제 HTTP 경로 통과 (라우팅·검증·직렬화 포함).
- **NDJSON 시퀀스 golden test**: 스트림 전 라인을 수집해 검증.

```python
async def test_user_message_turn(client, fake_llm):
    async with client.stream("POST", "/internal/ai/turn", json=TURN_REQ, headers=AUTH) as r:
        events = [json.loads(line) async for line in r.aiter_lines() if line]
    types = [e["type"] for e in events if e["type"] != "heartbeat"]
    assert types[-1] == "done"                      # done/error 정확히 1회, 마지막
    assert types.count("done") + types.count("error") == 1
    NdjsonEvent.validate_lines(events)              # pydantic 스키마 검증
    assert "".join(e["text"] for e in events if e["type"] == "answer_delta") \
           == events[-1]["data"]["messages"][0]["content"]   # delta 누적 == done 본문
```

- 각 이벤트 타입/시나리오(설명, QA, 퀴즈 생성, QUIZ_GRADED→진단, 진단답변→교정, 저득점/고득점 분기)별 golden 케이스 작성.
- 인증(401), 검증 실패(400 envelope), turnId 중복, 에러 detail에 예외 문자열 미노출까지 계약으로 고정.
- 기존 `tests/test_bridge_contracts_and_verifiers.py`(138KB)의 시나리오를 새 계약으로 포팅해 재활용.

### Level 4 — Grok HTTP 계층 테스트 (respx)

- Grok은 OpenAI 호환 HTTP이므로 `respx`로 `api.x.ai` 응답을 목킹해 **실제 LlmClient 구현체**를 검증:
  - SSE 청크 파싱, `[DONE]` 처리, 타임아웃, 재시도, structured output 파싱 실패 처리.
- FakeLLM(Level 2~3)과 달리 여기서만 와이어 포맷을 다룬다 — 프로바이더 교체 시 이 레벨만 다시 쓴다.

### Level 5 — Live smoke (선택, CI 제외)

- `@pytest.mark.live` 마커 + 실제 `XAI_API_KEY` 필요. 수동/야간 실행.
- 케이스 최소화: 에이전트별 1회 호출로 "스키마 준수 응답이 오는가"만 확인. 내용 품질 평가는 별도(프롬프트 튜닝 노트북 등).

---

## 4. CI 구성

| 단계 | 실행 | 트리거 |
| --- | --- | --- |
| lint + typecheck | ruff, mypy | PR |
| Level 1~4 | `pytest -m "not live"` | PR (필수 게이트) |
| Level 5 | `pytest -m live` | 수동 / 주 1회 |

- PR 게이트는 외부 네트워크 0회 — 결정론 보장 (Clock/IdGenerator 고정 포함).
- 계약 문서 변경 시 golden 파일 diff가 리뷰에 드러나도록 golden을 repo에 커밋.

## 5. 요약 규칙 (팀 합의용)

1. 모듈 레벨에서 서비스/에이전트/클라이언트 인스턴스를 만들지 않는다. 전역은 `create_app` + lifespan + `app.state`만.
2. 모든 외부 의존(LLM, Redis, 시간, ID)은 Protocol 뒤에 두고 생성자/Depends로 주입한다.
3. LLM 호출 없는 로직은 순수 함수로 분리해 Level 1에서 커버한다.
4. 스트림 계약(이벤트 시퀀스, done/error 정확히 1회)은 golden test로 고정한다.
5. 실패 경로(타임아웃/쿼터/malformed)도 계약이다 — 성공 케이스만 테스트하지 않는다.
