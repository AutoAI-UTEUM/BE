# [Epic][AI] ReportAgent 생성·근거 검증·질의응답

## 목적

Spring이 계산한 사실·지표·데이터 충분성과 허용된 evidence snapshot만 사용해 강사용
학습 리포트를 생성하고, 저장 report 범위 안에서 근거 기반 질의응답을 제공합니다.

## 범위

- internal report generate/query strict contract
- ReportAgent 전용 profile·prompt version
- criterion별 평가·추세·서술·evidence IDs
- 강점·보완점·오개념 후보·coaching insight·추천 행동
- 데이터 부족·모순 근거·단일 근거 안전 규칙
- unknown evidence/criterion, duplicate, score range 검증
- usage·model·timeout·오류 변환
- FakeLlm/respx·golden 계약 테스트

## 제외

- DB·Spring 외부 API 직접 조회
- 서버 지표·충분성·종합 점수 재계산
- 학생 간 순위·심리/성격/지능 평가
- 학습 turn Orchestrator 도구 추가
- evidence 없는 자유 생성 답변

## 하위 작업

- [ ] [BE#121 ReportAgent·ReportQuery strict schema와 profile 계약](https://github.com/AutoAI-EduPilot/BE/issues/121)
- [ ] [BE#124 근거 기반 ReportAgent 생성·과잉 일반화 방지](https://github.com/AutoAI-EduPilot/BE/issues/124)
- [ ] [BE#122 리포트 QA·오류 처리·golden/contract 테스트](https://github.com/AutoAI-EduPilot/BE/issues/122)

> GitHub Epic: [BE#116](https://github.com/AutoAI-EduPilot/BE/issues/116). 위 이슈는 정식 Sub-issue로 연결했습니다.

## 선행 조건

- Main Service evidence snapshot 계약 Approved
- 별도 시험 evidence는 학생별 최신 GRADED attempt만 포함하며 SUBMITTED·GRADING_FAILED는 집계에서 제외
- criterion catalog와 data sufficiency policy version
- 최대 evidence·token·timeout 예산

## 완료 조건

- [ ] 모든 assessed 결과와 추천에 유효한 evidence ID가 있다.
- [ ] insufficient criterion은 score를 생성하지 않는다.
- [ ] 요청에 없는 학생·기준·근거를 출력할 수 없다.
- [ ] 단일 관찰을 반복 패턴·오개념·성향으로 확정하지 않는다.
- [ ] 모순 근거를 추가 확인 대상으로 표시한다.
- [ ] report QA가 지정 snapshot 밖의 질문을 안전하게 거절한다.
- [ ] timeout·invalid JSON·schema drift가 계약 오류로 변환된다.
- [ ] 실제 Grok 없이 전체 계약 테스트가 통과한다.

## 관련 문서

- [상세 설계](../../report-agent-design.md)
- [작업 분해](../13-report-agent.md)
