# [Epic] 프로젝트 기반·공통 계약 구축

## 목적

Spring Main Service와 FastAPI AI Service가 같은 저장소에서 독립적으로 빌드·테스트되고, 공통 응답·오류·내부 API 계약을 기준으로 기능 개발을 시작할 수 있게 한다.

## 범위

- `main-service`, `ai-service` 초기 구조와 local/dev/prod 환경 분리
- 공통 응답·오류·trace ID와 외부/내부 API 기준
- DB migration, OpenAPI, health check, CI 기준
- 환경 변수 예시와 비밀값 관리 문서

## 제외

- 실제 학습 기능
- 운영 인프라 배포
- 실제 Gemini 응답 구현

## 하위 작업

- [ ] 기술 스택과 막히는 공통 결정 확정
- [ ] Main Service 초기 구조·공통 오류·migration 구성
- [ ] AI Service 초기 구조·표준 응답 구성
- [ ] Spring–FastAPI stub/health 계약 검증
- [ ] 두 서비스 테스트·빌드 CI와 실행 문서 구성

필요한 항목만 Sub-issue로 만들고 이 체크박스에 링크한다.

## 완료 조건

- [ ] 두 서비스가 문서화된 명령으로 빌드·테스트된다.
- [ ] 환경 분리와 필수 환경 변수가 문서화됐다.
- [ ] 외부 API와 내부 AI API의 공통 계약이 승인됐다.
- [ ] 빈 DB migration과 Main–AI stub 호출이 재현된다.
- [ ] CI가 두 서비스의 실패를 검출하며 비밀값이 저장소·로그에 없다.

## 관련 문서

- [상세 계획](../00-foundation.md)
- [아키텍처](../../architecture.md)
- [API 명세](../../api-spec.md)
- [백엔드 실행 계획](../../backend-plan.md)
- [결정 대기 목록](../../decisions.md)
