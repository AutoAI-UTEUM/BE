# [Feature] 강사 학습 리포트·ReportAgent

> 상세 작업 분해 자료입니다. 실제 작업은 영역별 Epic과 GitHub Sub-issue로 관리합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·계약·예외·검증 참고 |
| Status | Blocked — #102 및 계약 승인 전 |
| Priority | Normal |

## 목표

강사가 관리하는 강의실의 한 학습자에 대해 여러 세션과 시험 결과를 근거 ID로 연결하고,
결정적 통계와 ReportAgent 해석을 결합한 버전형 학습 리포트를 생성·조회·질의응답합니다.

## 연결 요구사항 초안

- REPORT-001: 관리 강사는 학생 리포트를 생성·조회할 수 있다.
- REPORT-002: 통합 학습과 별도 시험은 분리 집계하되 함께 해석한다.
- REPORT-003: 각 평가와 추천은 실제 근거를 포함한다.
- REPORT-004: 데이터가 부족하면 확정 점수·능력 판단을 생성하지 않는다.
- REPORT-005: 재생성은 새 버전을 만들고 이전 결과를 보존한다.
- REPORT-006: 강의실별 사용자 평가 기준을 다음 생성부터 적용한다.
- REPORT-007: 리포트 QA는 선택된 학생·버전·snapshot만 사용한다.
- REPORT-008: 학생과 강의실 데이터 격리를 보장한다.

## 사용자 흐름

1. 강사가 강의실과 학생, 분석 범위를 선택합니다.
2. Spring이 권한을 검증하고 immutable evidence snapshot을 만듭니다.
3. Spring이 통계, 진도, 추세, 데이터 충분성을 계산합니다.
4. FastAPI ReportAgent가 구조화 평가와 근거 ID를 반환합니다.
5. Spring이 출력 불변식과 근거를 검증합니다.
6. 완료 report를 새 version으로 저장합니다.
7. FE가 report, 근거, 이전 version을 표시합니다.
8. 강사는 저장 report에 근거 기반 질문을 할 수 있습니다.

## 선행 의존성

- GitHub #102 강사 전용 기능·차등 권한
- Classroom/Course/Lecture/Enrollment 계약
- 별도 시험 기능 계약
- 페이지별 설명 완료 진도 근거
- 외부 Report API와 내부 AI contract 승인

## 영역별 Epic

- [FE 리포트 Epic](epics/09-report-frontend.md) — [GitHub FE#34](https://github.com/AutoAI-EduPilot/FE/issues/34)
- [Main Service 리포트 Epic](epics/10-report-main-service.md) — [GitHub BE#115](https://github.com/AutoAI-EduPilot/BE/issues/115)
- [AI Service ReportAgent Epic](epics/11-report-ai-service.md) — [GitHub BE#116](https://github.com/AutoAI-EduPilot/BE/issues/116)

GitHub 등록일은 2026-08-01입니다. Main과 AI 작업은 GitHub의 정식 Sub-issue 관계로 연결했습니다.
FE 저장소는 Epic 화면에서 Sub-issue 관계 추가 기능이 제공되지 않아, 각 이슈 본문의 상위 Epic 링크와
Epic의 역참조로 연결했습니다.

## Sub-issue 후보

Frontend:

1. [FE#35 리포트 API repository·route·권한 상태](https://github.com/AutoAI-EduPilot/FE/issues/35)
2. [FE#36 학생 리포트 생성 진행·버전 목록·상세·근거 UI](https://github.com/AutoAI-EduPilot/FE/issues/36)
3. [FE#37 평가 기준 관리·리포트 QA·접근성/통합 테스트](https://github.com/AutoAI-EduPilot/FE/issues/37)

Main Service:

1. [BE#117 Decision 강의실·별도 시험·리포트 범위와 평가 정책](https://github.com/AutoAI-EduPilot/BE/issues/117)
2. [BE#118 Contract 외부 Report API·내부 AI API·evidence schema](https://github.com/AutoAI-EduPilot/BE/issues/118)
3. [BE#119 DB/Main Report criterion·generation·version·evidence migration](https://github.com/AutoAI-EduPilot/BE/issues/119)
4. [BE#123 Main 권한 기반 snapshot·지표·충분성·진도 집계](https://github.com/AutoAI-EduPilot/BE/issues/123)
5. [BE#120 Main/Integration 비동기 생성·검증·저장·QA·격리 테스트](https://github.com/AutoAI-EduPilot/BE/issues/120)

AI Service:

1. [BE#121 AI/Contract ReportAgent·ReportQuery strict schema와 profile](https://github.com/AutoAI-EduPilot/BE/issues/121)
2. [BE#124 AI 근거 기반 ReportAgent 생성·과잉 일반화 방지](https://github.com/AutoAI-EduPilot/BE/issues/124)
3. [BE#122 AI 리포트 QA·오류 처리·golden/contract 테스트](https://github.com/AutoAI-EduPilot/BE/issues/122)

## 주요 예외

- INSTRUCTOR지만 해당 classroom 관리 권한이 없음
- student가 classroom enrollment에 없음
- 선택 session/exam이 다른 학생 또는 classroom 소속
- 데이터 부족인데 종합 점수 생성
- unknown/duplicate evidence 또는 criterion
- 건너뛴 페이지를 진도로 계산
- 같은 requestId로 중복 generation 생성
- 늦게 도착한 AI 응답 저장
- failed generation을 완료 report로 노출
- 기존 report version 덮어쓰기
- QA가 다른 학생·다른 report 근거 사용
- 전체 답안·정답·루브릭 노출

## 완료 조건

- [ ] 선행 도메인과 역할/소유권 계약이 승인됐다.
- [ ] 외부·내부 API와 evidence schema가 승인됐다.
- [ ] 서버 계산값과 AI 해석 책임이 분리됐다.
- [ ] 각 claim과 평가 항목에 유효한 evidence가 있다.
- [ ] insufficient data에서 확정 평가가 생성되지 않는다.
- [ ] 재생성이 새 version을 만들고 이전 version을 보존한다.
- [ ] FE ↔ Main ↔ FastAPI 전체 흐름이 동작한다.
- [ ] 타 강의실·타 학생 데이터 혼입 테스트가 통과한다.
- [ ] 실제 Grok 없이 계약 테스트가 통과한다.

## 관련 문서

- [리포트 시스템 설계](../report-agent-design.md)
- [리포트 원안](../report-agent-reference-draft.md)
- [이슈·PR 작업 흐름](../issue-workflow.md)
- [Definition of Done](../definition-of-done.md)
