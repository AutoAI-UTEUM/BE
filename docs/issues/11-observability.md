# [Platform] 요청·오류·AI 호출 로깅 및 모니터링

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [운영 Epic 초안](epics/08-operations.md)을 사용합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·예외·검증 참고 |
| Status | Todo |
| Priority | High |

권장 라벨:

```text
area: main-service
area: ai-service
area: integration
area: infra
area: docs
type: chore
```

## 목표

한 사용자 요청을 Frontend → Main Service → AI Service 구간에서 추적하고 장애 원인을 확인할 수 있게 하되, 토큰·비밀번호·PDF/답안 원문 등 민감정보는 로그에 남기지 않는다.

## 연결 요구사항

- `OPS-004` health check
- `OPS-005` 요청·오류·외부 AI 호출 로그
- 공통 오류의 `traceId`

## 범위

### 포함

- traceId/requestId/turnId 전달
- Main Service 구조화 요청·오류 로그
- AI Service 구조화 호출·오류 로그
- Grok 호출 시간·성공/실패 분류
- 환경별 로그 레벨
- 민감정보 마스킹
- health/readiness 기본 상태
- 핵심 메트릭과 알림 후보
- 장애 조사와 로그 검색 절차 문서

### 제외

- 사용자 대화/답안 원문을 기본 운영 로그에 저장
- 특정 상용 APM 도구 확정 전 벤더 종속 구현
- 복잡한 학습 분석 대시보드

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Contract]` 공통 추적 ID와 로그 필드 기준
- `[Main]` 요청·오류·외부 AI 호출 구조화 로그
- `[AI]` agent/tool/Grok 호출 구조화 로그
- `[Security]` 민감정보 마스킹·로그 접근 정책
- `[Infra]` 로그 수집·보관·메트릭·알림 도구 결정
- `[Integration]` 단일 요청의 end-to-end trace 검증
- `[Docs]` 로그 검색·장애 조사·보관 정책 문서

## 공통 로그 필드 후보

```text
timestamp
level
service
environment
traceId
requestId
turnId
sessionId
endpoint/tool
status
durationMs
errorCode
```

## 로그 금지 정보

- 비밀번호와 password hash
- JWT, refresh token, API Key
- 실제 `.env` 값
- 전체 PDF 텍스트
- 전체 학생 답안·대화 원문
- 퀴즈 비공개 정답·루브릭
- 내부 chain-of-thought

## 선행 의존성

- [공통 trace/error 기반](00-foundation.md)
- AI 연동 기능의 turnId/actionId 계약
- 로그/메트릭 도구 결정은 배포 환경과 함께 진행 가능

## 주요 예외·검증

- Main과 AI traceId 불일치
- 동일 키를 서비스마다 다른 의미로 사용
- 예외 stack에 토큰/프롬프트 원문 포함
- 운영에서 debug 로그 상시 활성화
- 로그 보관 기간과 접근 권한 미정
- health는 성공하지만 핵심 의존성은 사용 불가

## 완료 조건

- [ ] 추적 ID와 공통 로그 필드가 승인됐다.
- [ ] 한 요청을 Main과 AI 로그에서 연결할 수 있다.
- [ ] AI 호출 시간과 오류 유형을 확인할 수 있다.
- [ ] 민감정보 마스킹 테스트가 통과한다.
- [ ] local/dev/prod 로그 레벨이 분리된다.
- [ ] health/readiness 기준이 문서화됐다.
- [ ] 핵심 메트릭·알림 후보와 운영 책임자가 정해졌다.
- [ ] 장애 조사 절차가 문서화됐다.
