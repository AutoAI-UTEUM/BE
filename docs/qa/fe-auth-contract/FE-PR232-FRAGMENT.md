# FE232 fragment 소비자 재확인 (2026-10-04)

**후속 BE 통합:** 아래 query 발급 차이는 읽은 BE `e949fbec`에 대한 역사적 결과다. [PR512 발급 구현과 현재 통합 검증](BE-FRAGMENT-INTEGRATION.md)에서 새 fragment 발급을 별도로 확인한다. 배포·기능 활성화·실메일 인수는 별도다.

[FE PR232](https://github.com/AutoAI-UTEUM/FE/pull/232)의 merge/보고된 DEV head `5592042cb7a25c2fc797e557a20643061b84e273`와 PR source `114f4cf4bd13ef531f7396747ef1abdb6e104804`를 읽기 확인했다. FE231/PR509 당시 [미지원·답변 대기 기록](FE-PR231-SETTINGS.md)은 그대로 역사적 snapshot으로 보존하며 최신 구현 결과와 구분한다. 당시 metadata의 pending은 현재 FE232의 fragment 지원 여부가 아니다.

현재 작업은 원 독립 브랜치 `feature/479-fe231-settings-contract`와 draft PR509에 **추가**한다. BE 선택 base/검토 runtime은 `e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e`이며 main-service tree `20a4c0c7c55b44d8b792ce282e622eddb4d573d9`를 수정하지 않았다. 최종 BE/FE artifact·capability·메일 전환·배포/기능 활성화 합의가 완료됐다는 뜻은 아니다.

## 최신 FE 구현과 요청 계약

- [early HTML](https://github.com/AutoAI-UTEUM/FE/blob/5592042cb7a25c2fc797e557a20643061b84e273/index.html)과 [SPA parser](https://github.com/AutoAI-UTEUM/FE/blob/5592042cb7a25c2fc797e557a20643061b84e273/src/features/auth/emailLinkToken.ts)는 정확한 `#token=<43자 base64url>`만 읽는다. query의 token 키는 encoded 키도 거부하고 유효 fragment가 함께 있어도 obsolete-query다. 값 없는 query token도 구형 링크로 처리한다.
- 파싱 직후 `replaceState(null, '', '/verify-email')`로 주소/state를 정리하고 토큰은 closure 메모리에만 둔다. 현재 HTML의 static no-referrer와 동기 scrub script가 link/module/recovery보다 앞에 있는 것을 source로 확인했다. module 이전 observer stand-in도 깨끗한 URL을 읽었다. 실제 브라우저 speculative fetch·CSP·외부 script 임의 동작을 검증한 결과는 아니다.
- [확인 화면](https://github.com/AutoAI-UTEUM/FE/blob/5592042cb7a25c2fc797e557a20643061b84e273/src/app/pages/VerifyEmailPage.tsx)의 버튼 action만 기존 공개 `POST /api/auth/email-verification/confirm`에 `{token}`을 보낸다. actual repository는 Bearer를 붙이지 않고 credentials omit/no-referrer/no-store를 사용한다. token은 await 전에 지우고 동기 ref lock으로 중복 클릭을 막는다.
- 확인 응답을 새 로그인이나 현재 사용자 VERIFIED 상태로 적용하지 않는다. 합성 token-account VERIFIED 이후에도 별도로 제공한 현재 계정 refresh는 Bearer status/me에서 PENDING/required=true를 유지했다. 실제 AuthProvider의 계정 전환·브라우저 검증은 아니다.
- 기본 readiness는 OFF다. 이전 exact 키 `be-auth-ee69e425-v1`를 합성 harness에 넣은 ON 조건만 실행했으며 이 키는 실제 BE revision 신호가 아니다. OFF 초기/새 SPA fragment는 메모리에서 폐기되고 새 API 호출이 없다.

입력·응답·오류의 나머지 BE 계약은 [원 계약](README.md), 최신 FE의 상세 시점/한계와 인수 절차는 [FE 작성 문서](https://github.com/AutoAI-UTEUM/FE/blob/5592042cb7a25c2fc797e557a20643061b84e273/docs/qa/email-link-fragment-contract.md)를 재사용한다. FE의1042/Vitest·합성ON18/OFF8 및 dev run37203882484 success는 [FE PR 보고](https://github.com/AutoAI-UTEUM/FE/pull/232)이며 이 세션 실행으로 합산하지 않는다.

## 추가 소비자 검사와 확인한 경계

| 경계 | 실제 소스 callback/API를 합성 실행한 결과 |
| --- | --- |
| 초기 scrub·mount | module observer 전에 query/hash 제거. 자동 confirm 요청 없음. static source 순서도 대조. |
| 중복 클릭 | 같은 action ref에서 동시에 두 번 실행해 공개 POST 한 번, Bearer/cookie 생략 및 정확한 body 확인. |
| reload | 정리된 URL을 새 parser/frame으로 열면 토큰 없음, confirm 호출 없음. |
| pagehide/back | persisted 이벤트 stand-in이 pending 토큰을 버리고 request를 abort, busy를 해제. pageshow 뒤 토큰/늦은 성공 메시지·refresh가 복구되지 않음. 실제 BFCache/browser 인수와 구분. |
| SPA 새 링크 | 이전 confirm abort 및 late result 무시, 새 token만 새 action에서 한 번 제출. |
| 이탈/unmount | abort 후 늦은 성공은 UI/refresh에 적용되지 않음. 서버 토큰 소비/DB rollback을 보장하지 않음. |
| 구 query/혼합/중복 | 구 query·encoded key·query+fragment는 obsolete-query, 잘못된/추가 fragment field는 invalid-fragment. POST 없음. |
| 무효/만료/replay·429 | 기존 BE 합성 오류는 token을 폐기하고 수동 후속 동작을 안내. 자동 재소비/재시도 없음. |

검토한 이 범위에서 새 FE 결함은 확정하지 않았다. FE 작성자의 기존 HTML/SPA29 및 E2E 결과를 다시 실행한 것으로 부르지 않는다. 이번14개는 BE 합성 envelope와 captured page action·parser의 조합이며 URL 단위 테스트만 복제한 결과가 아니다.

## core에 전달했던 BE e949 호환 차이

읽은 [e949 EmailVerificationService.java](https://github.com/AutoAI-UTEUM/BE/blob/e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e/main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java#L86)는 `/verify-email?token=`을 생성했다. FE232는 이 형식을 거부한다. 합성으로 재요청202를 수신한 뒤 당시 생성 형태의 새 query 링크도 obsolete-query가 되는 것을 재현했다. 202는 접수이며 사용 가능한 새 fragment 링크의 증거가 아니다. 당시 후보와 FE232의 계약 차이는 후속 BE PR512의 runtime 범위로 처리했다.

[EmailOutboxStore](../../../main-service/src/main/java/io/edupilot/mail/EmailOutboxStore.java)는 완성된 message를 encrypt해 저장하고 claim 때 저장 payload를 decrypt한다. 새 생성 코드를 바꾸는 것만으로 이미 저장된 query 본문이 변환된다는 source 근거가 없다. 기존 대기/재시도/이미 보낸 query의 만료·폐기·재발급 방침과 실제 provider fragment 보존/클릭 추적은 core·운영이 결정/확인해야 한다. 실제 대기함·본문·계정·DB는 조회하지 않았다.

FE PR 보고의 `/verify-email` 직접 GET404, Nginx 경로/로그, 유효 메일 origin/provider와 배포 준비는 기존 core/운영 검토를 재사용한다. 이 세션은 source·합성 검증만 수행하며 직접 서버 GET·Nginx 수정·실메일 발송을 하지 않는다. FE source 지원과 회신을 BE fragment 배포/실메일/활성화 승인으로 확대하지 않는다.

## 재현과 한계

```powershell
$env:FE_AUTH_FRAGMENT_SOURCE_ROOT = 'C:\Users\russe\Documents\Codex\2026-10-04\task\FE-pr232-readonly'
$env:FE_AUTH_SETTINGS_SOURCE_ROOT = 'C:\Users\russe\Documents\Codex\2026-10-04\task\FE-pr231-readonly'
node --disable-warning=ExperimentalWarning --test --test-reporter=spec docs/qa/fe-auth-contract/fragment-consumer-pr232.test.mjs docs/qa/fe-auth-contract/fragment-boundary.test.mjs
```

**19/19 passed, failure0/skip0**: 최신 FE23214 + 역사적 FE2315. 공유된 token/window harness를 확장했으므로 해당 과거5개의 보존도 확인했다. settings15·이전 auth/전체 FE 검사는 이번 변화의 결과로 반복 합산하지 않는다.

`fragment-page-harness.mjs`는 실제 parser/early script/API client와 페이지의 action/layout/mount callback body를 VM에서 실행한다. React render/effect scheduling·실제 reload/BFCache·브라우저/쿠키/서버/메일은 실행하지 않으며 refs·setter·event/window/document는 합성 stand-in이다. 최종 SHA와 실행 기록은 [EVIDENCE.md](EVIDENCE.md)에 남긴다. BE runtime/FE 파일·migration/defaults·workflow/Nginx·실API/학생/Google/메일/유료AI·DEV DB·배포/활성화·외부 댓글/Discord 전송은 미실행이다.
