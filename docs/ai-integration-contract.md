# Spring–FastAPI AI 연동 계약 (ai-integration-contract)

| 항목 | 내용 |
| --- | --- |
| 상태 | 작성 중 — v0.3 갱신 예정 |
| 소유 | 고영빈 (Agent Server) — Spring 측 검토: 한승준 |
| 마지막 갱신 | 2026-07-23 (자리 확보 — 본문은 AI 담당이 이관) |
| 관련 | [API 명세](api-spec.md) §8, [에이전트 시스템 명세](agent-system-spec.md), [결정 대기 목록](decisions.md) DEC-002 v2·DEC-022 |

> 이 파일은 AI 담당이 관리하던 계약 문서를 BE 저장소로 이관하기 위한 자리입니다.
> 본문 이관 전까지 내부 API 계약의 기준은 [API 명세](api-spec.md) §8과 [에이전트 시스템 명세](agent-system-spec.md)입니다.

## 이관 시 반영할 항목 (DEC-002 v2 §5에서 이관된 연계 결정)

- `/internal/ai/grade` 타임아웃(비스트리밍, heartbeat 불가) 및 재시도 횟수/예산
- 재시도와 turnId 멱등성 충돌 해소 — 실패 턴은 같은 turnId 재호출 허용(replay), 성공 턴만 DUPLICATE_TURN
- §7 `MODEL_NAME` 기본값·§3.4 usage 예시를 고정 버전 체계(grok-4.5)로 정합화
- usage에 reasoningTokens·grade usage 필드 추가 (DEC-002 §2 비용 판단 데이터)
- agent-system-spec §4.5 "채점 결정성" 재해석(루브릭 기준 일관 채점 + 검증 통과)의 명세 역반영 — 팀 합의 후

## 정합성 규칙

- 이 문서와 [API 명세](api-spec.md) §8이 충돌하면 합의로 해소하기 전까지 api-spec을 우선한다.
- 계약 변경은 BE·AI 동시 리뷰를 거쳐 같은 PR 흐름으로 갱신한다.
