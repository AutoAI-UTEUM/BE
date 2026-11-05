# DEV V65 유지보수 경로

이 경로는 로컬 구현/합성 검증 대상이다. 병합·DEV 중단·백업·격리 복구·이미지 빌드/게시·배포·접근 재개는 각각 승인된 실행 계획 이후에만 수행한다. PROD, AI/MySQL 교체, 보호자 기능 활성화, 정책값 지정은 포함하지 않는다.

기존 [`Deploy dev`](../../.github/workflows/deploy-dev.yml)의 정상 경로와 schemaVersion 2 계약은 그대로 유지한다. 그 workflow를 Main/Nginx 정지 상태에서 dispatch하지 않는다. 새 [`dev_maintenance.py`](../../scripts/deploy/dev_maintenance.py)는 기존에 인증·호스트 검증이 끝난 DEV 운영자 세션에서 사용하는 **별도 명시적 CLI**이며 SSH, 새 자격증명, dispatch를 제공하지 않는다. 기존 SSH transport의 payload로 가장하지 않는다.

## 실행 전 필요한 것

- 승인된 새 커밋의 clean 소스 checkout. CLI는 실행 시 실제 HEAD 일치와 untracked 파일을 포함한 clean 상태를 확인한다. 입력 JSON과 실제 `.env`/백업은 그 checkout 밖에 둔다. 이번 미커밋 구현은 배포 승인 SHA가 아니다.
- `/opt/edupilot`, 사용자가 실제 OS 조회로 확인한 hostname `ip-172-31-40-63.ap-northeast-2.compute.internal`인 기존 DEV. 진단과 유지보수 CLI는 `socket.gethostname()`의 전체 값을 정확히 비교한다. 단축명·임의 접미사·다른 리전/인스턴스·추가 접미사를 허용하지 않으며 DNS 조회나 외부 `hostname` 명령을 사용하지 않는다. 인스턴스 이동이나 별도 경로를 자동 허용하지 않는다. 기존 신뢰된 SSH 호스트 지문과 접속 수단은 실행 담당자가 먼저 확인한다. OS hostname 검사는 SSH 서버키 검증을 대체하지 않는다.
- 정확한 승인 SHA/Main·AI CI, 검토된 V65 MySQL 증거, V1~V65 checksum 카탈로그, 현재 컨테이너/설정 hash, 이전 이미지의 registry 가용성, 현재 7회 예산 및 신규 교체+수동 복구 2회 여유.
- **중단 전에 이미 빌드/게시된** 승인 SHA의 Main digest. 현재 도구는 이미지를 빌드하지 않는다. begin에서 해당 digest를 pull하여 OCI revision/계약/catalog/entrypoint/정책 및 환경 보존을 검증한다. 별도 승인된 이미지 준비 경로가 없으면 begin하지 않는다. 기존 workflow의 빌드 전 검증을 생략하여 이미지를 얻지 않는다.
- 기존 signup pause marker/입력 모두 true, mail logging/PAUSED, guardian 기본 false 및 prior-consent OFF/미설정. 도구는 이 설정을 변경하지 않는다.
- 실제 writer inventory, 외부 AI 작업의 종료를 확인할 담당자/수단, 격리 복구 환경과 승인된 DB/files/ledger 복구 계획. 해시만 입력했다고 이 준비가 자동 검증되지는 않는다.

일반 서비스 검증을 건너뛰지 않는다. begin 직전 4개 서비스가 각각 하나씩 running이고 Main/AI/MySQL healthy여야 한다. 직접 공개된 Main/AI/MySQL 포트, host/container network mode, 예상과 다른 restart policy, Docker pause/restarting은 거부한다. 원본 4개 설정 hash와 기존 환경 보존 검사는 공유 helper를 사용한다. Nginx Health 미존재는 허용한다.

## 단계와 영향

| action | 입력·검증 | 변경 및 완료 상태 |
| --- | --- | --- |
| `begin` | schemaVersion 3 plan, 정상 baseline, 정확한 신규 이미지/환경 검증 | 동일 Nginx를 먼저 정지하고 결과 확인 후 동일 Main 정지. FE/API/SSE 전체 DEV 중단. `MAIN_STOPPED_AWAITING_DRAIN_REVIEW` |
| `confirm-drain` | 실제 Main-stop receipt에 연결한 fresh 외부 작업/writer 검토 | 컨테이너가 계속 의도된 정지 상태인지 읽기 확인한 뒤 `QUIESCED` receipt 생성. DB/files 백업은 아직 수행하지 않음 |
| `install` | 실제 QUIESCED 시각/hash에 연결한 기존 schemaVersion 2 전체 manifest | DB dump 파일/hash/gzip/권한, 격리 복구/files/ledger receipt, baseline DB, 예산, 이미지/설정/정책을 재검증한 뒤 Main만 한 번 교체. V65와 private health 확인 후 `VERIFIED_HELD`. Nginx는 계속 정지 |
| `reopen` | VERIFIED_HELD receipt와 새 Main 기준 예산을 검토한 별도 fresh 명시적 승인 | 동일 Nginx start 및 설정/public health 검사. 성공 `REOPENED`. 실패/일반 인터럽트는 동일 Nginx를 한 번 다시 정지 시도 |
| `status` | 기존 session plan | journal만 읽는다. 실제 live 정지/접근 차단 또는 현재 health를 증명하지 않음 |

stop은 `docker stop --timeout -1 <검증된 ID>`로 image의 검토된 stop signal(Main SIGTERM, Nginx SIGQUIT)을 사용한다. [Docker 공식 문서](https://docs.docker.com/reference/cli/docker/container/stop/)의 무제한 daemon 대기 설정이며 자동 SIGKILL을 사용하지 않는다. CLI 대기는 120초로 제한하며, client timeout 시 daemon 작업 결과는 불확실하다. 자동 재시도/강제 종료/접근 재개 없이 중단한다. Main의 exit 137/OOM/예상 밖 종료는 정상 quiescence로 인정하지 않는다.

`MAIN_START_REQUESTED`는 새 JVM과 scheduler가 시작할 수 있는 비가역 경계다. **그 시점부터 worker가 DB/files를 변경했을 수 있다.** Nginx가 정지한 동안에도 새 Main의 worker를 일괄 정지한 것으로 표현하지 않는다. install 이후 예전 QUIESCED 증거를 새 백업의 근거로 재사용하지 않는다. 실패 후 복구를 하려면 새 Main/worker 정지와 외부 호출 종료부터 별도 검토한다.

## 입력 계약

plan은 기존 schemaVersion 1의 `approvedSha`, `preflightReceiptSha256`, `checkedAtUtc`, `services`, `configSha256`, `flywayCatalogSha256`, `rollback`, `budget`, `ci`를 보존하되 `schemaVersion:3`, catalog=검토된 V65로 한다. 추가 `maintenance`의 정확한 필드는 다음과 같다.

- `sessionId`: 32자리 소문자 hex, 작업마다 새 값. 완료/중단된 ID를 재사용하지 않는다.
- `fromVersion`: 64 또는 65.
- `newMainRepoDigest`: 승인 SHA 이미지의 고정 digest. 현재 Main digest와 같으면 거부한다.
- `guardianMysql`: 기존 V65 계약의 `sourceSha`, `catalogSha256`, `runId`, `reviewedSuccess`.
- `operatorPlanSha256`, `writerInventoryReceiptSha256`, `isolatedRecoveryPlanSha256`: 담당자가 검토한 실제 계획/자원/수단 근거. 합성/과거 receipt를 현재 증거로 제출하지 않는다.

`confirm-drain` evidence의 정확한 필드:

`sessionId`, `mainStoppedReceiptSha256`, `checkedAtUtc`, `activeRequests:0`, `activeAiJobs:0`, `independentWriters:0`, `writerInventoryReceiptSha256`, `drainEvidenceSha256`.

이 검토는 Main-stop event 이후 시각이어야 한다. 도구가 AI provider 작업/독립 writer를 자동 계측한다는 뜻이 아니다. 검증 수단과 실제 0건 근거가 없으면 중단한다. QUIESCED의 반환 `atUtc`, `receiptSha256`을 install manifest의 `v65.writeQuiescence.startedAtUtc`, `receiptSha256`에 그대로 연결한다. 실제 백업은 그 이후에 생성하고 격리 복구/files/최신 신뢰 원장을 검증해야 한다. 유지보수 CLI 자체는 dump/복구/원장 반출을 실행하지 않는다. 별도 [삭제 원장 operator](deletion-ledger-operator.md)는 실제 활성 QUIESCED journal과 같은 잠금을 사용하며 원장 export·격리 DB의 V65 migration/논리 replay만 제공한다. 백업 생성·SQL 복원·files 복구는 여전히 별도 검토와 실행이 필요하다.

`install` evidence는 [기존 전체 V65 manifest](dev-v65-main-only-deployment.md)다. baseline 서비스/설정/CI/예산, fromVersion/MySQL 증거가 begin과 같아야 한다. 쓰기 중지·백업·preflight 및 건강한 begin baseline은 교체 직전 30분 이내여야 한다. 이미지 pull 중 만료되거나 변경되면 Main을 시작하지 않는다. 시각을 새로 적어 과거 snapshot을 재사용하지 않는다.

`reopen` evidence의 정확한 필드:

`sessionId`, `verifiedReceiptSha256`, `checkedAtUtc`, `allowIngress:true`, `workersMayHaveRunAcknowledged:true`, `budget`, `evidenceSha256`.

budget은 기존 필드와 같은 형태이며 confirmed는 begin+1, reserved는 동일, currentMainContainerId는 VERIFIED_HELD의 새 Main ID이고 수동 복구 1회 여유가 있어야 한다. 컨테이너 실제 재생성 확인 전에는 confirmed를 임의 증가/초기화하지 않는다. 변경된 외부 예약/이력이 있으면 이 작업은 중단하고 별도 재대사한다.

plan의 `checkedAtUtc`/`preflightReceiptSha256`은 해당 action의 새 검토를 표현하며, 다른 baseline/계약 필드는 변경할 수 없다. 이것으로 이전 baseline/QUIESCED/백업 시각을 갱신하지 않는다. `status`에는 과거 plan을 사용할 수 있다.

## 명령·중복·실패

기본 실행은 로컬 plan 검증뿐이다. 아래 예시는 서버 변경을 하지 않는다.

```sh
python3 -I -S -B scripts/deploy/dev_maintenance.py \
  --action begin --plan /private/reviewed-plan.json --approved-sha "$REVIEWED_SHA"
```

실제 실행에는 검토한 action마다 별도 `--execute --root /opt/edupilot`가 필요하다. confirm-drain/install/reopen은 각각 검토한 JSON의 `--evidence /private/action-evidence.json`도 요구한다. 단계를 연속 자동 실행하거나 `finally`에서 reopen하지 않는다. 입력 파일은 private 0600, 소스 checkout 밖이며 사용자/아동 원문이나 credentials를 넣지 않는다. 기존 GHCR 인증 입력은 begin/install의 이미지 검사 때만 메모리로 사용하며 receipt에 저장하지 않는다.

`.dev-maintenance/`는 0700, event/active/lock 파일은 0600이다. event에는 원문 ENV/SQL 결과/Health.Log를 저장하지 않는다. 변경 명령 전에 intent를 append/fsync하며, hash chain/독점 lock/active session으로 중복·동시 실행과 다른 baseline을 거부한다. 성공한 단계의 반복이나 변경 결과가 불확실한 단계의 재실행도 거부한다. phase와 receipt만 보고 결과를 추측하지 않는다.

SIGTERM/SIGHUP/일반 KeyboardInterrupt는 중단 처리한다. reopen 뒤 오류는 동일 pinned Nginx를 닫는 보상 동작을 한 번 시도한다. 이 동작마저 실패하면 `REBLOCK_REQUESTED`가 남고 접근 차단은 **미확인**이다. 재개 중 public 검사는 잠시 접근을 열어야 하므로 오류 전 짧은 노출이 가능하다. SIGKILL/전원 장애/접속 단절은 cleanup을 보장하지 않는다. stale action.lock, pending intent, 손상된 journal을 자동 삭제하거나 재실행하지 말고 실제 상태/예산을 읽기 점검한 뒤 별도 복구 승인을 받는다. 현재 CLI는 불확실한 작업을 자동 reconcile/rollback하는 명령을 제공하지 않는다.

V65 DDL 가능성 이후 old image 재실행, Flyway repair/down-migration, live DEV DB 위의 복구를 자동 수행하지 않는다. 유지보수 차단 상태에서 호환 가능한 forward fix 또는 검증된 격리 DB/files/최신 ledger 복구 계획을 별도 검토한다.

## 검증 한계

중단 전에 Main 이미지를 준비하는 경로는 [build-only image preparation](dev-image-preparation.md)다. source/CI 검토와 이미지 게시만 분리하며 runtime manifest·백업·예산·호스트 검증을 대체하지 않는다. 별도 원장 job/격리 DB의 자원과 비용은 현재 계획에서 따로 대사해야 하며, 과거 Main 재생성 7회 예산을 자동으로 확장하거나 초기화하지 않는다.

`test_dev_maintenance.py`는 모든 subprocess/DB/HTTP를 가로채는 합성 검증이다. 실제 Docker stop/start·Compose·MySQL·signals·private/public health·인증·백업·격리 복구는 실행하지 않는다. 기존 접근 거절을 우회하지 않는다. 합성 통과는 실제 DEV 준비 완료나 운영 receipt 생성의 증거가 아니다.
