# DEV Main 한정 배포 경로 (#479)

V65가 포함된 develop은 이 문서의 V64 기본 경로로 배포하지 않는다. 별도 검토된 [V65 Main-only 계약](dev-v65-main-only-deployment.md)의 명시적 선택과 schemaVersion 2 백업/복구 근거가 필요하다. 기존 V64 검증은 유지한다.

2026-10-15 구현·합성 검증 범위를 설명한다. 이 문서는 실제 병합·이미지 push·SSH·배포를 실행했다는 증거가 아니다. 기존 DEV environment의 권한·자격 증명과 PROD 경로는 변경하지 않는다.

## 트리거와 실행 범위

`deploy-dev.yml`은 `workflow_dispatch`만 받는다. 안전 경로 PR 자체나 BE549/550이 develop에 병합되어도 이 워크플로는 자동 실행되지 않는다. 수동 실행도 ref가 develop이고 `approved_sha == github.sha`여야 한다. FE240/241의 기존 자동 배포 워크플로는 변경하지 않았다. FE 실제 배포는 팀원이 담당하므로 소유 범위와 일정을 조율하기 전에는 해당 FE PR을 병합하거나 배포하지 않는다.

사용자 지시에 따라 FE 복구 파일 확보는 이 Main 경로에서 제외한다. FE archive/index hash를 요구하거나 static 파일·volume을 읽고 복사하지 않는다. Main은 uploads volume만 사용하며 FE volume은 기존 Nginx에 read-only로 연결되어 있다. 이 경로는 Nginx를 재생성하지 않고 설정 검사와 graceful reload만 수행한다. Main의 불변 rollback digest와 registry 가용성 검토는 계속 필요하다.

기존 workflow ID가 default branch에 존재해야 manual dispatch가 가능하다. 새 default-branch 변경이나 환경 권한 변경으로 우회하지 않는다. 실행 가능 여부를 기존 GitHub UI/API에서 확인한 다음 검토한 develop SHA를 지정한다.

승인 SHA의 Main 이미지만 build/push한다. `latest` tag와 AI build/push는 없다. build가 반환한 registry digest로 Main만 pull한다. 서버의 기존 Compose 3개와 Nginx 소스 파일, `.env` 및 기존 Docker credentials를 덮어쓰지 않는다. 짧게 사용하는 private temp directory의 image override와 job GHCR 로그인만 종료 시 정리한다.

Compose에는 Main의 새 digest와 MySQL·AI·Nginx의 **현재 검토한 digest**를 마지막 image override로 제공한다. 실제 교체 명령은 다음 한 번뿐이다.

```text
docker --config <private-job-dir> compose --env-file .env
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.dev.yml -f <reviewed-images.yml>
  up -d --no-deps --no-build --pull never main-service
```

전체 pull/up, `--remove-orphans`, AI/MySQL/Nginx pull, Nginx restart/recreate, DB repair/restore, 자동 재시도/롤백은 없다. 기존 Nginx container에서 `nginx -t` 후 `nginx -s reload`만 수행하여 새 Main IP를 반영한다. Main 교체 시 기존 HTTP/SSE 연결은 끊길 수 있다.

## 필요한 읽기 전용 증거

배포 입력은 비밀 없는 `reviewed_manifest` JSON이며 아래 **정확한 필드**만 받는다. 빈 값·임의 예시 값·10월 13일 값을 현재 값으로 복사하여 채우지 않는다. 실제 메타데이터 receipt를 받은 후 담당자가 원본과 대조해 작성한다. 이 저장소의 테스트 fixture는 합성 값으로 실행에 사용할 수 없다.

| 필드 | 필수 내용 |
| --- | --- |
| `schemaVersion` | 정수 1 |
| `approvedSha` | 검토한 최종 develop SHA, 40자리 hex |
| `preflightReceiptSha256`, `checkedAtUtc` | 실제 readonly receipt hash와 timezone 포함 조회 시각. 준비/교체 직전 모두 30분 이내 필요 |
| `services` | `main-service`, `ai-service`, `mysql`, `nginx` 각각 `id`(64 hex), `imageId`(`sha256:`), `repoDigest`(해당 registry repository `@sha256:`). image ID를 registry digest로 바꾸어 쓰지 않음 |
| `configSha256` | `docker-compose.yml`, `docker-compose.prod.yml`, `docker-compose.dev.yml`, `infra/nginx/edupilot.conf` 네 소스 파일 hash |
| `flywayCatalogSha256` | V1..64 signed integer checksum map의 `json.dumps(catalog, sort_keys=True, separators=(',', ':'))` UTF-8 SHA-256. 검토한 소스와 실제 이력 모두 일치 필요 |
| `rollback` | `mainRepoDigest`(현재 Main digest와 동일), `registryAvailabilityReviewed:true`. FE 복구 파일과 index hash는 입력하지 않음 |
| `budget` | `confirmed`, `reserved`, `limit:7`, `currentMainContainerId`, `evidenceSha256`. 기존 증거로 현재 이력을 대조하고 Main 1회와 수동 복구 1회의 여유를 확보: confirmed+reserved+2≤7 |
| `ci` | `headSha`(approvedSha와 동일), `mainRunId`, `aiRunId`, `reviewedSuccess:true`. 담당자가 해당 head의 Main·AI 성공을 GitHub에서 확인해야 함 |

CI run ID와 성공 검토 표시는 자동 GitHub API 재검증을 뜻하지 않는다. 기존 workflow token 권한은 변경하지 않았다. 담당자가 정확한 최종 통합 SHA의 CI를 확인하기 전 dispatch하지 않는다. 개별 PR CI 또는 다른 base의 임시 merge CI로 대체하지 않는다.

7회 한도의 확인된 출처는 2026-10-08 18:34 UTC의 중단된 서비스메일 4종 시험 재개 승인(`Sentinel_9f6e37d733e08191bf576dc99bf844b6`)이다. PR546·548의 로컬 장부가 이를 이어 사용했다. 모든 향후 DEV 배포에 영구 적용되는 조직 정책을 입증하는 자료로 사용하지 않는다. 이 후보의 `+2` 조건은 Main 교체 1회와 필요할 수 있는 수동 복구 1회를 고려한 보수적인 구현 가드이며, 원래 사용자 발언에 있는 별도 숫자 정책은 아니다. 실제 manifest에 적용할 장부 범위와 현재 횟수는 이 출처를 함께 검토하며, 기존 장부를 초기화하거나 한도를 임의로 늘리지 않는다.

별도 `verified_host_fingerprint` 입력은 기존 신뢰한 known_hosts와 대조한 DEV 호스트 공개키의 `SHA256:...` 값이다. 키 내용이나 암호가 아니다. 새 키를 신뢰하거나 호스트 검증을 끄지 않는다. 이 입력은 기존 host identity 확인을 실행 시 요구하며 서버/GitHub의 키·권한·secrets를 변경하지 않는다. 선택한 host key와 실제 SSH 협상 키가 다르면 중단하고 기존 신뢰 근거를 확인한다.

운영자는 기존 known_hosts의 DEV 항목만 아래처럼 로컬에서 읽어 공개 fingerprint를 확인할 수 있다. 개인키 파일 경로를 이 명령에 넣지 않는다. 네트워크 접속·키 생성·파일 변경은 없다. 여러 키가 나오면 운영자가 기존 신뢰 근거와 대조하고 협상되는 호스트 키를 확인한다. 임의 첫 행 선택이나 `ssh-keyscan`으로 대체하지 않는다.

```powershell
$devKnownHosts = Read-Host '기존에 검증한 known_hosts 절대 경로'
& "$env:WINDIR\System32\OpenSSH\ssh-keygen.exe" -F dev.uteum.com -f $devKnownHosts |
  & "$env:WINDIR\System32\OpenSSH\ssh-keygen.exe" -lf - -E sha256
```

## 교체 전·후 검사

서버에서 메타데이터를 다시 읽어 네 container ID/image ID/RepoDigest, running/health, source config hash, DEV logging/PAUSED, guardian OFF, trial 입력 비어 있음, 추가 Java/Spring override 부재, 가입 pause marker, 동의 입력, 표준 Main 실행 형식을 대조한다. V1..64 성공/checksum은 READ ONLY SQL transaction 후 ROLLBACK으로 확인한다. FE static 파일은 조회하지 않는다.

가입 pause 환경변수는 소스상 Nginx에만 전달된다. Main에 이 변수가 없음을 정상으로 두고 Nginx의 정확한 true/false 입력과 host marker를 대조한다. 기존 v3 metadata 수집기의 Main 기반 pause 비교 false는 정책 변경 증거로 사용하지 않는다. 배포 경로가 실제 실행되기 직전에 Nginx 입력을 읽으며 이를 위해 사용자에게 같은 Main 런타임 점검을 다시 요청하지 않는다.

Compose의 최종 Main 환경과 현재 Main의 설정/credentials를 **메모리에서만** 비교한다. 값의 변경·누락이나 새 이미지의 추가 Java/Spring override가 있으면 교체하지 않는다. 전체 Env·Compose config·비밀·메일 주소·SQL 원문 결과·예외 원문은 로그에 쓰지 않는다. `.env`나 기존 환경변수를 새로 생성/수정하지 않는다.

기존 Main rollback manifest의 registry metadata 조회와 새 Main digest pull 뒤, 실제 교체 직전에 동일한 사전 검사를 한 번 더 수행한다. MySQL·AI·Nginx의 container/image ID가 교체 뒤 달라지면 중단한다. 새 Main healthy·설정 보존·digest/새 ID를 확인하고 Nginx를 reload한다. `/api/health`와 `/api/health/ready`의 공개 읽기만 수행하고 DB·AI UP을 확인한다. 학생/답안/메일 API, 유료 모델, 사용자 데이터 생성·수정·삭제는 호출하지 않는다.

실패 시 자동 재배포나 과거 workflow 재실행으로 복구하지 않는다. `mainRecreationRequested`가 true이면 실제 교체 여부와 누적 예산을 수동 대조한다. 승인된 Main 불변 rollback digest와 복원 절차를 확인한 후 해당 복구 승인을 따로 적용한다. FE 복원은 이 경로의 조건이나 작업에 포함하지 않는다. DB down-migration/repair, UNKNOWN 메일 재발송은 하지 않는다. 코드 rollback이 기존 outbox cleanup/payload 제거를 되돌리는 것은 아니다. PAUSED에서도 Main 기동 후 기존 recovery/cleanup이 운영 DB를 갱신할 수 있다.

## 반영 순서

1. 승인된 8개 파일을 별도 일반 PR로 게시하고 정확한 head의 CI를 확인한다. 이 PR의 병합과 수동 DEV 실행은 이번 게시 승인에 포함하지 않는다.
2. 별도 병합 승인이 확인되면 안전 경로를 먼저 develop에 반영하여 자동 BE 배포를 제거한다. 이 변경만으로 서버 배포는 실행되지 않는다.
3. 승인된 BE549/550을 반영한 최종 develop의 Main·AI CI를 확인한다. FE239는 제외한다.
4. 현재 DEV·Main 복구 digest·예산의 readonly 증거를 대조하고 manifest를 작성한다. FE 담당자의 동시 배포 여부는 조율하되 FE 복구 파일은 수집하지 않는다.
5. 기존 네 PR 승인과 이 경로의 병합·실행 범위를 확인한 후에만 정확한 SHA/manifest/기존 host fingerprint로 수동 DEV 실행을 요청한다. FE239는 제외하며 FE240/241 실제 반영은 팀원 소유 범위 조율 뒤 담당자가 진행한다. 파일 캐시의 배포 후 인수는 paired FE241·BE550 조합이 필요하므로 BE 단독 검증으로 완료 처리하지 않는다.

오프라인 검증:

```text
python3 -I -S -B scripts/deploy/test_dev_main_only.py
node --test scripts/qa/dev-deploy-preservation.test.mjs
```

테스트는 synthetic metadata와 실제 local Compose `config`만 사용한다. Docker daemon/registry/SSH/운영 DB/메일에 연결하지 않으며 배포 인수 증거로 사용하지 않는다.
