# AI 서비스 테스트 전략 (test-strategy)

| 항목 | 내용 |
| --- | --- |
| 상태 | 작성 중 |
| 소유 | 고영빈 (Agent Server) |
| 마지막 갱신 | 2026-07-23 (자리 확보 — 본문은 AI 담당이 이관) |
| 관련 | [결정 대기 목록](decisions.md) DEC-002 v2 (D3·D4·§4 후속 조치), [Definition of Done](definition-of-done.md) |

> 이 파일은 AI 담당이 관리하던 테스트 전략 문서를 BE 저장소로 이관하기 위한 자리입니다.

## 이관 시 반영할 항목 (DEC-002 v2에서 확정된 검증 체계)

- **golden 답안 세트**: 정답/부분정답/오답 각 5개 반복 채점(N=10), 문항 점수 표준편차 ≤ 0.1 수용 기준 — live 스모크 포함 (D4)
- **모델 표류 감지**: golden 세트를 감지기로 운용(dated 버전 미발행 시), 응답 `model` 필드 대조 assertion (D2)
- **temperature 스모크 테스트**: 동일 채점 입력 N=10회 점수 분산 비교로 반영 여부 판정 (D3)
- **effort별 TTFT 실측**: 자유 발화 턴 첫 answer_delta p50 5초 예산 검증 (D3)
- **CI 원칙**: Python 3.14.x 단일 버전, 실제 Grok 미호출(mock 기반) — Epic 8 ⓓ CI 파이프라인과 정합
- 3.14 의존성 전체 검증 절차 (D1 fallback 판정 기준)
