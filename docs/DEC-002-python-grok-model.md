# DEC-002 — Python 버전 · Grok 모델 선정 (v2)

| 항목 | 내용 |
| --- | --- |
| 상태 | 확정 (AI 담당 결정, 3인 패널 검토 반영) |
| 소유 | 고영빈 (Agent Server) |
| 결정일 | 2026-07-23 (v2: 패널 검토 조건 반영 개정) |
| 관련 | DEC-006, 에이전트 명세 §4.5, ai-integration-contract.md §5·§6, test-strategy.md |

## 1. 결정 사항

### D1. Python — 3.14.x

- pydantic v2 전용을 전제로 한다 (v1 호환 계층은 3.14 비호환 — 공식 확인됨. 새 코드에서 v1 API 사용 금지, 의존성의 v1 사용 여부를 lockfile 확정 시 확인).
- CI는 3.14.x 단일 버전으로 고정한다.
- Fallback 조건: 스켈레톤 단계에서 전체 의존성 설치 + 테스트 통과를 검증하고, 핵심 의존성 하나라도 3.14에서 막히면 3.13으로 하향한다. 검증 완료 시 이 조항은 소멸.

### D2. 모델 — 전 에이전트 공통, grok-4.5 버전 고정 + 표류 감지

- MVP는 단일 모델을 전 에이전트가 공유한다. 에이전트별 모델 이원화는 하지 않는다.
- dated 버전(`grok-4.5-<date>`)으로 고정하고 `MODEL_NAME` env로 관리한다. dated 식별자 미발행 시 대체 경로: alias(`grok-4.5`) 고정 + 채점 golden 세트를 모델 표류 감지기로 운용.
- 버전 고정만으로는 안정성이 담보되지 않는다 — xAI는 dated 버전까지 은퇴시키고 은퇴된 슬러그를 에러 없이 다른 모델로 무언 리다이렉트한 전례가 있다(2026-05-15, 8개 모델 일괄 은퇴). 따라서:
  - 매 응답의 실제 `model` 필드를 고정값과 대조하고, 불일치 시 경보 로그를 남긴다(런타임 assertion).
  - xAI deprecation 공지 모니터링을 운영 루틴에 포함한다(소유: AI 담당, 주 1회).

### D3. 용도별 차등 — `reasoning_effort`로 (temperature는 보조)

| 용도 | reasoning_effort | 근거 |
| --- | --- | --- |
| 오케스트레이터 Plan | low~medium | 짧은 structured output. high로 두면 자유 발화 턴의 첫 토큰이 플래너 완료(high TTFT 중앙값 ~17초) 뒤로 밀려 채팅 UX 실격 |
| 설명 · QA · 퀴즈 생성 (스트리밍) | low~medium | 반응성 우선 |
| 채점 · 평가 · 진단 (비대화형) | high | 판단 깊이 우선, 지연 허용 |

- 지연 예산: 자유 발화 턴의 첫 answer 청크까지 p50 5초 이내 목표. 구현 첫 주에 effort별 TTFT 실측해 표 확정.
- 사실관계: grok-4.5는 reasoning 비활성화 불가(grok-4.5에 한정 — grok-4.3은 `reasoning_effort: none` 지원). `presencePenalty`/`frequencyPenalty`/`stop`은 reasoning 모델에서 요청 시 에러.
- temperature: 공식 문서의 비호환 파라미터 목록에 없음(지원 가능성 높음). 구현 첫 주 스모크 테스트(동일 채점 입력 N=10회, 점수 분산 비교)로 반영 여부 판정 후, 지원 시 채점 계열에 최저값 추가 적용. 미지원이어도 D4가 결정성을 담보하므로 계획 영향 없음.

### D4. 채점 결정성의 담보 방식 — 구조로

- "결정성"은 "동일 입력 → 비트 단위 동일 출력"(LLM 불가)이 아니라 **"루브릭 기준 일관 채점 + 검증 통과"**로 해석한다. 이 재해석은 팀 합의로 명세에 역반영한다.
- 담보 장치:
  1. MCQ/OX: Spring 결정론 채점 (LLM 미개입)
  2. SHORT/ESSAY: structured outputs(json_schema)로 채점 JSON 스키마 강제
  3. ESSAY는 출제 단계에서 전용 스키마(modelAnswer + rubric 항목·가중치, weight 합계 검증) 강제 → 채점은 루브릭 항목별 점수 산출, 총점은 코드에서 합산
  4. 검증 계층: questionId 매칭, 점수 범위 검증(범위 초과는 clamp가 아니라 재시도 → 실패), verdict-점수 일관성(CORRECT ≥ 0.8·maxScore, PARTIAL 0.2~0.8, WRONG ≤ 0.2 — 구현 시 확정), 문항 수 일치. 위반 시 재시도 후 실패, 부분 결과 반환 금지.
- 측정 가능한 수용 기준: golden 답안 세트(정답/부분정답/오답 각 5개) 반복 채점(N=10)에서 문항 점수 표준편차 ≤ 0.1 — live 스모크에 포함.

### D5. 에이전트별 LLM 프로필 config 선반영

- `AgentLlmProfile { model, reasoningEffort, maxTokens, temperature? }`를 settings로 관리. 지금은 전 에이전트가 같은 모델, 이후 이원화는 config 변경만으로.
- maxTokens: reasoning 토큰이 출력을 잠식해 스키마 파싱 실패로 직결되므로 채점·Plan 계열은 여유값(예: 16K)에서 시작, 첫 주 실측으로 조정.
- 계약의 `config.model`(Backend 오버라이드)과의 우선순위: AgentLlmProfile이 항상 우선, `config.model`은 allowlist 값만 허용.

## 2. 비용 전제와 이원화 임계값

- 추정(세션 30턴 중 LLM 경유 22턴, 호출당 입력 ~6K·출력 ~4K, 턴당 2회 호출): 세션당 약 $1.2, 월 300세션 시 약 $360.
- 재검토 트리거: 월 LLM 비용 $150 초과 시(제안값 — 팀 확정 필요) 이원화 검토. 대상 grok-4.3($1.25/$2.50, 캐시 입력 $0.20), 설명·QA 이원화 시 약 55% 절감 추정.
- 판단 데이터: 전 내부 응답 usage(model, inputTokens, outputTokens, reasoningTokens) 수집.

## 3. 검토했으나 채택하지 않은 대안

| 대안 | 기각 사유 |
| --- | --- |
| alias만 사용 (표류 감지 없이) | 모델 자동 업데이트 시 채점 기준 표류를 감지 수단 없이 수용 |
| 채점 = temperature 최저 (원안) | reasoning 모델에서 반영 여부 미검증 → D3·D4로 대체, 스모크 확인 후 보조 적용 |
| 역방향 이원화 (저가 모델 기본) | 후보였던 grok-4.1-fast 등 fast 계열이 2026-05 일괄 은퇴로 부재. 현실 대안은 grok-4.3 하나, MVP는 단일 모델 유지 |
| Python 3.13 | 3.14 안정·호환 확인. 단 D1 fallback 유지 |

## 4. 후속 조치 (구현 첫 주)

- [ ] 3.14 의존성 전체 검증 (통과 시 D1 fallback 조항 소멸)
- [ ] grok-4.5 dated 버전 발행 여부 확인 → env 반영 / 미발행 시 alias+감지기 경로 확정
- [ ] 응답 model 필드 대조 assertion 구현
- [ ] effort별 TTFT 실측 → D3 표 확정 (자유 턴 p50 5초 예산 검증)
- [ ] temperature 스모크 테스트 (N=10 분산 비교) → 결과 추기
- [ ] AgentLlmProfile + maxTokens 기본값 스켈레톤 반영
- [ ] usage 수집 시작

## 5. 재검토 조건

- xAI의 grok-4.5 가격/정책 변경 또는 deprecation 공지 (주 1회 모니터링)
- 월 LLM 비용 $150 초과 (→ grok-4.3 이원화 검토)
- 응답 model 필드 불일치 경보 발생 (무언 리다이렉트 감지 시 즉시 재검토)
- TTFT 실측이 p50 5초 예산 초과 (→ D3 effort 배치 재조정)
