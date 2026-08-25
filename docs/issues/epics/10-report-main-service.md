# [Epic][Main] 리포트 데이터·권한·저장·API

## 목적

강사 권한을 검증하고 학생별 학습 데이터를 결정적으로 집계하여 FastAPI에 최소 근거
snapshot을 전달한 뒤, 검증된 결과를 버전형 리포트로 저장·제공합니다.

## 범위

- 강의실·학생·분석 범위 권한과 소유권 검증
- 외부 Report API와 Spring–FastAPI 내부 계약
- page progress, criterion, generation, report version, evidence migration
- source 중복 제거·점수 정규화·누적/최근 추세·진도
- versioned data sufficiency와 criterion eligibility
- requestId 멱등성·동시 generation claim
- AI score/evidence/criterion 불변식 검증
- version 조회·custom criterion·report QA orchestration
- AI 실패 시 완료 report 미생성·사실 요약 제공

## 제외

- LLM의 DB 직접 접근
- 전체 답안·대화·정답 기본 노출
- 단일 pageStatus로 진도 추정
- 기존 report 덮어쓰기
- Classroom/Exam 전체 도메인의 임의 선행 구현

## 하위 작업

- [ ] [BE#117 강의실·별도 시험·리포트 범위와 평가 정책 승인](https://github.com/AutoAI-EduPilot/BE/issues/117)
- [ ] [BE#118 외부 Report API·내부 AI API·evidence schema 승인](https://github.com/AutoAI-EduPilot/BE/issues/118)
- [ ] [BE#119 Report domain·page progress schema와 migration](https://github.com/AutoAI-EduPilot/BE/issues/119)
- [ ] [BE#123 권한 기반 snapshot·지표·충분성 집계](https://github.com/AutoAI-EduPilot/BE/issues/123)
- [ ] [BE#120 비동기 생성·AI 검증·버전 저장·QA·보안 통합 테스트](https://github.com/AutoAI-EduPilot/BE/issues/120)

> GitHub Epic: [BE#115](https://github.com/AutoAI-EduPilot/BE/issues/115). 위 이슈는 정식 Sub-issue로 연결했습니다.

## 선행 조건

- GitHub #102 승인
- Classroom/Course/Lecture/Enrollment 최소 계약
- 별도 시험 결과 계약

## 완료 조건

- [ ] 관리 강사만 해당 학생 report에 접근한다.
- [ ] 통합 학습과 별도 시험 지표가 분리 집계된다.
- [ ] 진도·점수·추세·충분성이 결정적으로 재현된다.
- [ ] unknown evidence와 잘못된 score/criterion이 거부된다.
- [ ] 중복·동시 요청이 중복 report를 만들지 않는다.
- [ ] 재생성이 새 version을 만들고 이전 version을 보존한다.
- [ ] AI timeout·invalid JSON·늦은 응답이 상태를 손상시키지 않는다.
- [ ] 타 학생·타 강의실 혼입 테스트가 통과한다.
- [ ] OpenAPI와 관련 문서가 동기화된다.

## 관련 문서

- [상세 설계](../../report-agent-design.md)
- [작업 분해](../13-report-agent.md)
