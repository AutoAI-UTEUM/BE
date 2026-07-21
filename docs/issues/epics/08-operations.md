# [Epic] 배포·운영·관측 기반 구축

## 목적

Main Service와 AI Service를 재현 가능하게 배포하고, 요청·오류·외부 AI 호출을 민감정보 없이 추적하며 장애 시 롤백할 수 있게 한다.

## 범위

- 구조화 요청·오류·AI 호출 로그와 trace ID
- 서비스·의존성 health/readiness 기준
- 서비스별 Dockerfile과 로컬 Docker Compose
- CI/CD, dev/prod 환경 변수, migration·롤백 절차
- 배포 체크리스트와 장애 대응 문서

## 제외

- 고급 APM·대규모 대시보드
- 자동 확장·멀티 리전
- 제품 리포트·통계 기능

## 하위 작업

- [ ] dev/prod 인프라·파일 저장소·배포 전략 확정
- [ ] 공통 로그·trace·민감정보 마스킹 구현
- [ ] Docker와 로컬 통합 실행 구성
- [ ] CI/CD·health/readiness·migration 절차 구성
- [ ] dev 배포·롤백·장애 시나리오 검증

## 완료 조건

- [ ] 두 서비스가 로컬과 dev 환경에서 재현 가능하게 실행된다.
- [ ] 요청에서 Spring–FastAPI–Gemini 호출까지 trace ID로 추적된다.
- [ ] 토큰·비밀번호·API key·PDF 원문이 로그에 남지 않는다.
- [ ] health/readiness가 실제 의존성 상태를 구분한다.
- [ ] 배포·migration·rollback 절차가 문서화되고 검증됐다.

## 관련 문서

- [로깅·관측 상세 계획](../11-observability.md)
- [배포·운영 상세 계획](../12-deployment.md)
- [백엔드 실행 계획](../../backend-plan.md)
- [Definition of Done](../../definition-of-done.md)
