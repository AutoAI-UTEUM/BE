# [Feature] 반복 근거 기반 학습자 개인화 메모리

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [학습 지원 Epic 초안](epics/07-learning-support.md)을 사용합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·예외·검증 참고 |
| Status | Todo |
| Priority | High |

권장 라벨:

```text
area: frontend
area: main-service
area: ai-service
area: integration
type: feature
```

## 목표

퀴즈 평가, QA, 진단, 교정에서 반복적으로 확인된 강점·약점·오개념·선호 패턴만 장기 LearnerMemory로 승격하고 다음 설명·QA·퀴즈에 안전하게 반영한다.

## 연결 요구사항

- `LEARN-004` 최근 QuizAssessment 큐
- `LEARN-005` 반복 근거 기반 장기 메모리 승격
- `LEARN-006` 사용자 메모리 조회 — Could
- `AI-002` 설명에 확정 메모리 반영

## 사용자 흐름

1. QuizAssessment, QA, Diagnosis, Repair에서 메모리 후보가 발생한다.
2. 첫 관찰은 임시 후보로만 저장한다.
3. 서로 독립된 학습 이벤트에서 같은 패턴이 반복된다.
4. Orchestrator가 근거가 충분한 후보에 `memoryWrite`를 계획한다.
5. Policy와 Main Service가 승격 조건·근거를 검증한다.
6. LearnerMemory를 갱신하고 기존 후보를 archive/정리한다.
7. 다음 에이전트 호출에는 확정된 `memoryDigest`만 전달한다.

## 범위

### 포함

- 임시 memory candidate와 확정 LearnerMemory 구분
- 강점, 약점, 오개념, 설명/퀴즈 선호
- targetDifficulty, nextCoachingGoals, memoryDigest
- 반복 근거와 승격 이력 추적
- 동시 갱신 방지
- 에이전트별 memoryDigest 전달
- 사용자 공개 메모리 조회 API는 별도 하위 이슈로 선택 가능

### 제외

- 단일 질문/퀴즈 결과로 즉시 장기 메모리 갱신
- 성격·지능·능력에 대한 근거 없는 추론
- 자료 범위를 넘어선 전역 프로필 정책 — 현재는 user+material 범위 초안

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Decision]` 반복 근거·승격 임계값·감사 이력 정책 확정
- `[Decision]` user/material 메모리 범위 확정
- `[Contract]` candidate/promote/memory digest 계약
- `[Main]` LearnerMemory와 후보/근거 schema·migration
- `[Main]` 승격 검증·낙관적 잠금·조회 구현
- `[AI]` LearnerMemoryService 후보 정리·digest 생성
- `[AI]` Orchestrator memoryWrite와 Policy 검증
- `[AI]` Explainer/QA/Quiz/Repair의 확정 digest 반영
- `[FE]` 내 학습자 메모리 조회 화면 — Could
- `[Integration]` 단일 관찰 미승격·반복 관찰 승격 테스트

## 외부·내부 API 초안

```http
GET  /api/users/me/memory?materialId={materialId}
POST /internal/ai/turn
```

메모리 후보 생성(`BUILD_MEMORY_CANDIDATE`)과 승격(`PROMOTE_MEMORY`)은 전용 엔드포인트 없이 `/internal/ai/turn` 내부 도구로 Orchestrator의 `memoryWrite` 판단에 따라 실행합니다(DEC-022 하이브리드 원칙).

## 선행 의존성

- [QA 근거](05-question-answer.md)
- [평가·진단·교정 근거](08-diagnosis-repair.md)
- `DEC-011` 평가 큐
- `DEC-012` 메모리 승격 규칙

## 주요 예외

- 단일 결과만으로 승격
- 근거 참조 없이 memoryWrite
- 서로 다른 자료의 약점을 잘못 합침
- 동시 승격으로 최신 메모리 덮어쓰기
- 임시 후보가 확정 digest에 노출됨
- 민감한 원문 답안/질문을 digest에 과도하게 저장

## 완료 조건

- [ ] 메모리 범위와 승격 기준이 승인됐다.
- [ ] 임시 후보와 확정 메모리가 영속 모델에서 구분된다.
- [ ] 단일 관찰은 장기 메모리를 변경하지 않는다.
- [ ] 반복 근거가 있을 때만 승격된다.
- [ ] 승격 근거와 갱신 이력을 추적할 수 있다.
- [ ] 동시 갱신이 기존 메모리를 유실시키지 않는다.
- [ ] 에이전트는 확정 memoryDigest만 참고한다.
- [ ] 미승격·승격·동시성 테스트가 통과한다.
