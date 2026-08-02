# [Epic][FE] 강사 학습 리포트 UI

## 목적

강사가 학생을 선택해 리포트를 생성하고, 버전·평가·근거·다음 지도 행동을 확인하며
저장 리포트에 근거 기반 질문을 할 수 있게 합니다.

## 범위

- instructor 전용 report route와 classroom/student 선택
- 분석 범위·평가 기준 선택과 생성 요청
- 생성 polling, 실패·재시도·데이터 부족 상태
- report version 목록·stale 표시·상세
- 항목별 score/trend/narrative와 null score 표시
- 강점·보완점·오개념 후보·추천 행동
- evidence toggle과 source label
- custom criterion 관리와 report QA

## 제외

- FastAPI 직접 호출
- FE의 통계·점수·stage 재계산
- 전체 답안·대화·비공개 정답 무제한 표시
- 강의실 전체 경향 화면

## 하위 작업

- [ ] [FE#35 리포트 API repository·DTO mapper·route·권한 상태](https://github.com/AutoAI-EduPilot/FE/issues/35)
- [ ] [FE#36 생성 진행·버전 목록·상세·근거 UI](https://github.com/AutoAI-EduPilot/FE/issues/36)
- [ ] [FE#37 평가 기준 관리·리포트 QA·접근성/통합 테스트](https://github.com/AutoAI-EduPilot/FE/issues/37)

> GitHub Epic: [FE#34](https://github.com/AutoAI-EduPilot/FE/issues/34). FE 저장소 UI에서는
> 정식 Sub-issue 관계 추가 기능이 제공되지 않아 하위 이슈의 상위 Epic 링크로 연결했습니다.

## 선행 조건

- Main Service Report API 계약 Approved
- classroom/student instructor authorization
- FastAPI report contract fixture

## 완료 조건

- [ ] 강사가 학생과 범위를 선택해 report 생성을 요청할 수 있다.
- [ ] processing/failed/insufficient/completed 상태가 구분된다.
- [ ] 이전 version과 최신 version을 구분해 조회할 수 있다.
- [ ] 평가 근거를 keyboard 접근 가능한 UI로 확인할 수 있다.
- [ ] null score와 데이터 부족이 능력 점수처럼 표시되지 않는다.
- [ ] report QA가 현재 report ID만 전송한다.
- [ ] lint·typecheck·test·build와 FE–Main 통합 검증이 통과한다.

## 관련 문서

- [상세 설계](../../report-agent-design.md)
- [작업 분해](../13-report-agent.md)
