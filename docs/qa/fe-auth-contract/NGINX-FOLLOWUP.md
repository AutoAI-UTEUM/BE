# FE229 메일 링크 SPA·Nginx 로그 후속 검토 (#471)

브랜치 한 줄: `feature/471-email-link-nginx` — 메일 확인 SPA 직접 경로와 query/Referer 없는 access 로그를 격리 합성 Nginx로 검증한다.

Base는 검토 후보 `4e578c5f811d0084de0b80ee72b5ed7c72ba84c6`이다. **저장소 설정의 검토용 수정안**이며 실제 서버 설정·reload·배포·FE ON·메일 발송·운영 로그 조회를 실행하지 않았다. 해당 base는 develop 배포 승인이 아니며 이번 수정안도 기존 후보에 자동 포함됐다고 표시하지 않는다.

## FE 구현과 이전 누락의 구분

[FE229](https://github.com/AutoAI-UTEUM/FE/pull/229)의 source head `43467a8343053e41f48d9667469dc110ceb32018`과 merge `5e91b5789daa13b3a4923653285709d18f232c79`의 tree는 `68dd14368b603d4da7dc6127814867a415a85d77`로 같다. API·route·mapper·early HTML script를 읽었다. 이전 FE1b head의 KNOWN GAP5는 역사적 source/mock 증거이며 이 구현을 현재 누락으로 표시하지 않는다.

| 이전 FE1b 차이 | FE229 source에서 확인한 범위 |
| --- | --- |
| LOCAL DOB/정책 미전달 | readiness ON에서 `dateOfBirth`/`consents` 전달 및 입력 검증·현재 정책 조회. OFF 요청에는 새 필드를 추가하지 않음 |
| 신규 Google DOB/정책 미전달 | ON+role 추가 가입 요청에서 전달; 기존 subject 로그인은 추가 DOB 요구와 구분 |
| login 이메일3필드 유실 | optional 상태·required·verifiedAt을 보존하고 없는 증거를 성공으로 만들지 않음 |
| me/Google 이메일 상태 유실 | optional 상태 및 기존 UNKNOWN/PENDING·required=false를 보존; false는 확인 성공 증거가 아님 |
| 이메일 API/type/UI·공개 route 부재 | 공개 `/verify-email`, 명시적 confirm POST(credentials omit), Bearer status/request와 본인 상태 재조회 구현 |

`VITE_AUTH_CONTRACT_READINESS=be-auth-ee69e425-v1`과 정확히 일치해야 새 입력/API가 ON이다. 기본 OFF이다. 이는 FE build의 계약 선택값이며 BE capability·실메일·연령 승인 신호가 아니다. ee69/4e578c5의 Main tree가 같아도 실제 승인 manifest의 BE SHA/이미지/FE build/readiness 적용은 별도로 대조한다.

[FE DEV workflow37194565734](https://github.com/AutoAI-UTEUM/FE/actions/runs/37194565734)의 exact5e91 completed/success metadata는 확인했다. Vitest1003/합성 E2E17 및 직접 `/verify-email` GET404는 FE 작성자 보고다. 실제 FE 실행 이미지/메일 수신·가입·브라우저 인수를 이 작업에서 독립 재현하지 않았다.

FE는 index HTML 선행 no-referrer 및 token history 제거/메모리 보관을 구현했다. 이는 브라우저 후속 Referer/history 노출을 줄이지만 첫 HTTP 요청의 query가 Nginx에 도착하는 것을 제거하지 않는다.
별도로 `EMAIL_VERIFICATION_REQUIRED` 403에서 refresh/logout 없이 세션을 보존하고 후속 업무 요청·route를 보류하는 코드도 읽었다. 계정 관리 경로는 유지된다. 최신 소비자 양성 검증은 별도 [BE PR507](https://github.com/AutoAI-UTEUM/BE/pull/507)에 있다.

## 현재 BE 저장소 설정의 근거와 최소 수정

[Nginx 설정](../../../infra/nginx/edupilot.conf)의 기존 SPA prefix allowlist에는 verify-email이 없다. 기본 catch-all의 `try_files ... =404`와 일치하며 직접 메일 링크 404가 발생할 수 있다. [Compose](../../../docker-compose.prod.yml)는 이 파일을 nginx:stable의 `default.conf.template`에 mount한다. 실제 실행 image digest·추가 include·유효 설정은 조회하지 않았다.

기존 설정에는 access_log/log_format override가 없다. [Nginx 공식 로그 문서](https://nginx.org/en/docs/http/ngx_http_log_module.html)의 기본 combined는 원 요청과 Referer를 기록하며 query가 포함될 수 있다. 실제 운영 로그를 검색해 유출을 확인했다는 주장은 아니다.

수정 diff는 다음 범위다:

- case/정규화 encoded path/단일 trailing slash를 지원하는 `^/verify-email/?$`만 SPA entry로 연결. 자식 경로·unknown·dotfile·missing asset 404와 API proxy는 유지한다. GET/HEAD가 메일 token을 소비하는 API를 호출하지 않는다.
- HTTP80/HTTPS443 두 server의 access_log에 query/Referer/header/body 없는 명시적 JSON 형식을 지정한다. method·정규화 uri·status·bytes·시간·기존 remote address는 남긴다.
- [Nginx URI 문서](https://nginx.org/en/docs/http/ngx_http_core_module.html#var_uri)의 `$uri`는 query 없는 정규화 경로이며 내부 SPA redirect 이후 `/index.html`이다. **원래 SPA 경로와 모든 query·Referer·User-Agent 관측은 줄고 combined→JSON 파서 호환성이 바뀐다.** 운영 로그 소비자/대시보드 호환성 검토가 필요하다.
- 메일 API/DTO·가입 gate·정책 flag·권한·로그 보존 기간·error_log 수준·proxy timeout·DB/migration·FE 코드는 바꾸지 않는다.

## 로컬 합성 검증과 잔여 error log 경계

Docker daemon이 없어 공식 nginx.org Windows stable `1.30.5`를 격리 temp에 사용했다. [검증 코드](../../../scripts/qa/nginx-email-link.test.mjs)는 기존/base와 수정 템플릿을 복사해 **127.0.0.1 임의 포트**, 합성 HTML/자체 시험 인증서/가짜 upstream만 사용한다. 실제 Linux DEV image의 인수가 아니다. 두 `nginx -t`와 **Node14/14 pass, failure0/skip0**를 확인했고 두 owned master를 종료했다.

```powershell
$env:NGINX_TEST_BINARY = 'C:\isolated\nginx.exe'
$env:NGINX_TEST_OPENSSL = 'C:\trusted\openssl.exe'
node --test scripts/qa/nginx-email-link.test.mjs
```

수정 전 직접 GET404와 combined access에 합성 query/Referer 기록을 재현했다. 수정 후 canonical/slash/case/encoded 메일 경로200, unknown/자식/dotfile/asset404, HEAD200/본문없음·landing POST405, 기존 SPA/asset/API 분리와 HTTP301 링크 query 보존을 확인했다. 성공/오류/내부 redirect 양쪽 listener의 access JSON16행에 합성 query/Referer가 없었다. 실제 token·운영 log·실학생을 사용하지 않았다.

**합성 upstream 조기 종료502에서는 error log에 합성 query가 남았다.** 이 수정안은 access log만 다루며 “모든 token 로그 노출 해결”이 아니다. error log를 끄거나 수준/보존/권한을 임의 변경하지 않았다. 실서비스 노출 완화의 추가 선택안은 FE와 합의한 query→fragment 메일 링크 계약(첫 HTTP URI에 token 없음) 또는 원문 영구 저장 전에 적용되는 검토된 error-log 보호 경로다. 단순 수집 후 마스킹은 원본 Docker/파일 로그 보존을 없앴다는 증거가 아니다.

현재 FE229는 query token을 읽으므로 BE만 fragment로 바꾸면 확인이 실패한다. 형식을 지금 변경하지 않는다. 별도 FE/BE 계약·호환 시험과 운영자 검토가 필요하며 full exposure 인수 전 ON을 보류한다.

## 적용 전에 필요한 증거·담당·메일 base

실Nginx 적용/배포·로그 소비자 호환·error log/상위 proxy 보호·메일 운영 담당과 실제 권한은 확정하지 않았다. AI 팀의 운영 담당/14일 로그 보고를 이 변경의 사용자 승인으로 사용하지 않는다. 실제 적용 담당·창·배포 범위는 부모/운영자가 지정해야 한다.

유효 `EDUPILOT_MAIL_BASE_URL`은 승인된 FE 확인 화면 origin과 맞아야 한다. base application/Compose는 dev, prod override는 www이며 DEV도 override를 사용한다. 이 작업에서 실제 값을 조회하거나 수정하지 않았다. 주소/키/토큰 원문 대신 승인 origin·exact artifacts·provider 활성 여부와 합성 수신 증거를 기록한다.

승인된 동일 Linux image/digest의 `nginx -t`, live effective log destination/format과 상위 proxy, synthetic token의 성공/404/오류/redirect 로그 보호, 실제 `/verify-email` 직접200·entry/no-referrer, FE default OFF/ON 계약, 명시적 POST/본인 상태·GET 비소비/재발급·만료, 실제 메일 수신과 gate 보존 복구를 인수한 뒤 활성화를 판단한다. 현재 이 실환경 항목은 NOT_RUN이다. [수동 활성화·복구 계획](../../launch-deployment-preflight.md)을 함께 사용한다.

14일의 저장소 근거는 Main/AI CloudWatch 설정 script/runbook이며 Nginx50m/3 회전과 다르다. 실제 적용·담당·권한은 [로그 보존/최소 조회 승인안](../log-retention-access-plan.md)에서 분리했다. 원문/token 로그 수집·권한/보존/보안 설정 변경은 실행하지 않는다.
