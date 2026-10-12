# DEV guardian 경로와 검증 상태 보존 (#479, #526)

일반 develop 배포는 `infra/nginx/edupilot.conf`를 서버에 덮어쓴다. 저장소에는
`/guardian-request`, `/guardian-consent`의 정확한 SPA 경로를 포함하며 GET/HEAD와
선택적 마지막 `/`를 지원한다. 하위 경로·유사 경로는 기존 404 계약을 유지한다.

DEV 워크플로는 `docker-compose.yml`, `docker-compose.prod.yml`, `docker-compose.dev.yml`
순서로 적용한다. 마지막 파일은 현재 검증 단계의 `logging/PAUSED`와 guardian TEAM·WEB
비활성 상태를 명시적으로 유지한다. `.env`의 SES/NORMAL/활성화 값으로 이 상태를
바꾸지 않는다. 가입 차단·필수 동의 설정과 운영 Compose 조합은 변경하지 않는다.
실메일 또는 guardian 기능 활성화는 별도 검토된 설정 변경으로 준비한다.

`node --test scripts/qa/dev-deploy-preservation.test.mjs`는 합성 env 파일과 빈 Docker 설정을
사용하여 실제 Compose `config` 병합 결과 및 모든 DEV 명령의 override 연결을 검사한다.
Docker daemon, 컨테이너, 운영 `.env`, 메일 또는 DB에 연결하지 않는다.
운영 인수 카탈로그의 현재 Nginx 소스 해시는 검증된 guardian 경로 설정으로 갱신한다.
카탈로그의 과거 baseline·CI·인수 기록과 실제 사용자 실행 증거는 그대로 유지한다.

게시 전 최종 후보의 Main/AI 필수 CI와 보호규칙을 확인한다. 실제 배포는 Main·AI 이미지
교체, Nginx 재시작과 Main의 Flyway 기동을 수반한다. 배포 후 승인된 경로 GET/HEAD,
404·401 제어 경로, 선택된 컨테이너 설정과 배포 SHA를 별도로 확인해야 한다.
소스·로컬 시험은 guardian API 실배포나 실제 메일 4회 수신 증거가 아니다.
