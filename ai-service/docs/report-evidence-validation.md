# 리포트 평가 기준별 근거 유형 검증

요구사항 연결: AI-02의 허용 근거 경계와 SYS-01의 기존 계약 검증을 보완한다.

리포트 생성 결과의 criterionResults는 요청 snapshot에 존재하는 evidenceId만 참조할 수 있다.
각 참조의 sourceType도 해당 criterion의 allowedSourceTypes에 포함되어야 한다.
ASSESSED와 INSUFFICIENT_DATA 모두 인용한 근거에 같은 검사를 적용한다.
ID whitelist 검사 후 유형을 검사하므로 존재하지 않는 ID의 기존 오류는 유지한다.

허용되지 않은 유형은 내부 DISALLOWED_EVIDENCE_SOURCE 사유로 처리한다. 기존 총 시간
예산 안에서 한 번만 재생성하고, 반복 위반은 기존 502 계약 오류를 반환하여 성공 결과로
전달하지 않는다. 재생성 안내에는 사유 코드만 추가하며 근거 원문을 오류 안내에 넣지 않는다.
입력/출력 DTO와 공개 오류 코드는 변경하지 않는다. Spring 저장 경계 자체는 이 작업에서
변경하지 않았으며, 실제 연동 저장과 AI 내용의 교육적 타당성은 별도 인수 대상이다.

기존 golden 출력 2개가 기준에 허용되지 않은 REPAIR/QA를 인용했다. 요청의 허용 유형이나
minimumEvidence를 완화하지 않고, snapshot의 허용된 근거로 출력 참조를 바로잡았다.
