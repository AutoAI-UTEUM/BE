# BE fragment 발급과 FE232 통합 검증

이 문서는 검증 후보에 들어온 [BE PR512](https://github.com/AutoAI-UTEUM/BE/pull/512)의 fragment 발급 구현과 [FE232](https://github.com/AutoAI-UTEUM/FE/pull/232)의 소비 계약을 연결한다. BE 기능 커밋은 `4921cb48f7d8f46f4bb720c83d3db0aba456d48d`, 읽기 전용 FE merge는 `5592042cb7a25c2fc797e557a20643061b84e273`다. 기능 브랜치 `feature/479-launch-contract-alignment`는 문서와 Node QA만 변경한다.

현재 BE가 새로 생성하는 인증 메일은 `/verify-email#token=<43자 base64url>`이며 텍스트 URL과 HTML 링크가 같은 값을 사용한다. FE232는 초기 script에서 URL을 정리하고 메모리에 캡처한 토큰을 명시적 버튼의 공개 POST body로만 보낸다. 기존 confirm API, 기간, 일회 사용과 재발급 시 이전 토큰 무효화 계약은 유지한다. `/verify-email` 직접 GET과 실제 메일 provider의 링크 보존은 별도 인수다.

`contract.test.mjs`와 `followup.test.mjs`는 현재 BE checkout의 fragment 발급을 검사한다. `fragment-consumer-pr232.test.mjs`는 현재 발급 형태를 읽어 pinned FE232 parser/action과 연결하고, 자동 POST 없음·정리된 URL·버튼 후 정확한 body를 합성 실행한다. Java의 실제 token/메일 생성·암호화 outbox·복구·확인/replay는 PR512의 테스트와 통합 Gradle 결과로 구분한다. Node 소스 대조만으로 Java 런타임 통과를 주장하지 않는다.

과거 BE `e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e`의 query 발급과 FE231 미지원, FE232 query 거부 기록은 보존한다. 해당 역사적 테스트는 `readBeAt`으로 정확한 Git object를 읽는다. 현재 checkout을 과거 query 기대에 맞춰 바꾸거나 과거 실패 기록을 지우지 않는다. `pr232-review.json`·`settings-fragment-review.json`의 당시 pending 값도 보존하며 현재 FE232의 형식 회신과 구분한다.

새 발급 코드가 기존에 저장된 메일 본문을 자동으로 바꾸지는 않는다. 기존 작업의 전환과 공동 인수는 담당 운영자의 별도 검토 대상이다. BE 배포, readiness 활성화, 실메일·실계정·브라우저 인수, DEV 데이터 작업은 실행하지 않았다. 세부 운영 전환안은 비공개 인계 자료에서 관리한다.

재현은 [FE232 명령](FE-PR232-FRAGMENT.md)의 읽기 전용 FE 경로를 사용한다. 통합 실행 결과와 과거 검사의 구분은 [EVIDENCE.md](EVIDENCE.md)에 남긴다.
