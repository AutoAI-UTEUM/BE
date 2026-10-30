# DEV V65 Main-only 배포 계약

`Deploy dev`의 `release_contract=v65-prior-consent`는 V1~V65를 검토한 별도 경로다. 기본 선택은 기존 `v64`이며, V65 소스에 V64 선택/manifest를 사용하면 이미지 빌드와 SSH 전에 차단한다. V66 또는 기존 migration 변경도 새 검토 전까지 차단한다. PR 병합은 배포를 실행하지 않는다. 실제 dispatch는 사용자가 담당한다.

이 변경은 DEV/PROD 배포, 백업 생성, DB 쓰기/복구, 기능 활성화를 수행한 증거가 아니다. Main-only 범위, 기존 DEV 자격증명/SSH ED25519 신뢰, 환경 보존, 7회 예산 및 수동 복구 여유를 유지한다. AI/MySQL/Nginx 이미지를 pull/up/restart하지 않으며 Nginx는 검증 후 graceful reload만 한다.

## 배포 전 준비

1. 이 경로가 develop에 반영된 후 실제 배포할 develop SHA의 Main/AI CI를 확인한다. 그 SHA와 dispatch ref/SHA가 같아야 한다. 현재 V65 migration 카탈로그에 대해 성공한 격리 MySQL CI도 확인한다. Guardian CI는 PR 전용이므로 source SHA와 카탈로그를 따로 기록하며, 다른 카탈로그나 H2 결과로 대체하지 않는다.
2. 기존에 승인된 DEV 읽기 경로로 현재 4개 container ID/image ID/registry digest, health, 4개 설정 파일 hash, Flyway 성공 이력/checksum을 수집한다. 현재 DB는 정확히 V1~V64 또는 V1~V65여야 한다. V63 이하에서 V54/V60 등을 한 번에 적용하는 경로는 지원하지 않는다. 미래/중복/실패 이력, V65 부분 적용, 다른 checksum은 repair 없이 중단한다.
3. 기존 절차에 따라 쓰기 중지 상태를 확보하고 담당자의 근거 receipt를 준비한다. Nginx signup pause marker/입력은 이미 true여야 하고, mail은 logging/PAUSED, 기본 guardian 플래그는 false여야 한다. **signup pause와 outbox PAUSED만으로 기존 로그인 사용자의 쓰기가 중단되었다고 판단하지 않는다.** 실제 쓰기 중지 여부와 유지 책임은 운영자의 선행 확인 사항이다. 이 helper는 이를 설정하거나 기존 Main을 별도로 정지하지 않는다.
4. 쓰기 중지 시작 이후 생성된 검증된 일관성 있는 DB dump가 기존 배포 경로의 `backups/<이름>.sql.gz`에 있어야 한다. 이 작업은 dump를 새로 생성/복사/복구하지 않는다. Linux에서는 파일의 group/other 접근 비트가 없어야 한다. DB dump와 연결되는 파일 저장소 복구 절차, 더 최신의 신뢰 가능한 deletion ledger 및 재적용 계획, 실제 복구 훈련 receipt도 필요하다. DB gzip만으로 PDF/files/삭제 이력 복구가 입증되지 않는다.
5. 일치하는 baseline의 백업 시각/크기/SHA-256, 복구 및 쓰기 중지 receipt hash를 새 manifest에 기록한다. preflight/쓰기 중지/백업 시각은 준비와 교체 직전 모두 30분 이내여야 하고 `쓰기 중지 시작 ≤ 백업 생성 ≤ preflight`여야 한다. 빌드 중 만료되면 중단 후 상태와 예산을 다시 확인한다. 자동 재시도하지 않는다.

서버에서 백업 파일 크기/hash와 gzip CRC/길이를 읽기만 하여 재검증한다. 백업 내용은 문자열로 해석하거나 로그로 출력하지 않는다. receipt hash와 CI 성공 표시는 담당자의 검토 근거이며 helper가 GitHub나 별도 복구 시스템에 접속해 진위/실제 복구를 다시 검증한다는 뜻은 아니다. 원본 개인정보나 동의서는 manifest/Git/Actions 입력에 넣지 않는다.

## Manifest schemaVersion 2

기존 [V64 계약](dev-main-only-deployment.md)의 필드와 검증을 모두 유지하며, `schemaVersion`만 2로 설정하고 `v65`를 추가한다. 정의되지 않은 키는 거부한다. `flywayCatalogSha256`는 아래 V65 고정값이다. `rollback.mainRepoDigest`는 현재 Main digest의 registry 가용성 확인용으로 계속 필요하지만 **V65 이후 구버전 이미지를 실행해도 된다는 승인이 아니다.**

| `v65` 필드 | 필요한 값/근거 |
| --- | --- |
| `releaseContract` | `v65-prior-consent` |
| `fromVersion` | 현재 성공 이력이 정확히 일치하는 정수 64 또는 65 |
| `guardianMysql` | `sourceSha`(40 hex), `catalogSha256`(아래 V65 값), `runId`(양의 정수), `reviewedSuccess:true`. 실제 성공한 격리 MySQL CI와 해당 소스의 카탈로그를 검토 |
| `backup` | `relativePath`, `createdAtUtc`, `sizeBytes`, `sha256`, `flywayCatalogSha256`, `restoreDrillReceiptSha256`, `filesRecoveryReceiptSha256`, `deletionLedgerReceiptSha256` |
| `recovery` | `mode:COMPATIBLE_FORWARD_FIX_OR_VERIFIED_RESTORE`, `evidenceSha256`, `automaticRollback:false` |
| `writeQuiescence` | `confirmed:true`, `startedAtUtc`, `receiptSha256`. 실제 쓰기 중지와 이를 유지하는 책임자의 근거 |

`backup.relativePath`는 `backups/` 바로 아래의 영문/숫자/`_.-` 파일명 + `.sql.gz`이며 symlink/상위 디렉터리 참조는 거부한다. `sizeBytes`는 실제 양의 정수, hash는 64자리 소문자 hex다. 백업의 카탈로그는 `fromVersion`과 같아야 한다. placeholder나 합성 테스트 receipt를 실제 검토 근거로 제출하지 않는다.

| 카탈로그 | 고정 SHA-256 |
| --- | --- |
| V1~V64 | `75af39b82695df2088c3916f6ffc3a2b9c71627fb4cf2fcca3b080cb3335d228` |
| V1~V65 | `83b8357cd5b2ab07f5d01a857323af1107a1718eae9fb1dfcf0daaa5de7467da` |

카탈로그는 Flyway signed CRC32를 version→checksum map으로 만들고 `json.dumps(catalog, sort_keys=True, separators=(',', ':'))`를 UTF-8 SHA-256 처리한다. V65 checksum은 `-1852741875`다. 정확한 migration 집합과 전체 카탈로그 hash를 고정하므로 버전 상한만 늘린 구현이 아니다.

## 실행과 확인

준비된 manifest, 기존 검증 host fingerprint, exact develop SHA와 `release_contract=v65-prior-consent`로 사용자가 수동 dispatch한다. 기존 workflow가 default branch에 없으면 GitHub에서 manual workflow를 선택할 수 없을 수 있다. 이 경우 default branch/권한을 우회하지 말고 별도 검토가 필요하다.

준비 단계는 네트워크/배포 없이 manifest와 소스를 검사하고 바인딩된 script만 생성한다. 이후 workflow가 exact checkout에서 Main 이미지만 빌드하고 OCI revision/contract/catalog label을 붙인다. 서버는 반환된 registry digest의 image config에서 label/entrypoint/추가 설정을 확인한다. OCI label은 이 검토된 빌드 경로의 메타데이터이며 이미지 안의 코드를 독립적으로 실행 검증한 증거는 아니다.

Main 교체 전 현재 이력/환경/백업을 재확인한다. 교체 후에는 V1~V65 성공 이력/checksum, nullable 컬럼 3개의 이름/타입, enforced CHECK를 확인한 뒤 Nginx reload와 `/api/health`, `/api/health/ready`를 읽는다. 실패하면 현재 Main 교체 요청 여부를 남기고 자동 재시도/rollback 없이 중단한다. Flyway DDL은 부분 적용될 수 있으므로 실패 상태에서 기존 이미지를 재실행하거나 history repair/down-migration을 하지 않는다. V60 cleanup 및 V65 `PRIOR_RECORD` reader 호환성을 유지하는 forward fix 또는 검증된 DB/files/ledger 복구 계획으로 별도 대응한다.

배포 후 운영자는 image digest/container, Flyway V65 성공 이력, 제외 서비스 보존, logging/PAUSED, guardian OFF, signup pause 유지와 health/ready를 확인한다. 기능 OFF인 상태에서 prior-consent options/approval이 unavailable/503인 것은 예상 동작이다. 실제 보호자 승인, 이메일 발송, signup pause 해제, FE 연동 및 실계정 테스트는 별도 준비/승인 범위다. 2026년 말 테스트 계획을 동의 유효기간 또는 최대 재사용 기간으로 설정하지 않는다.

## 합성 검증

```text
python3 -I -S -B scripts/deploy/test_dev_main_only.py
python3 -I -S -B scripts/deploy/test_dev_v65_main_only.py
```

V64 회귀, V64→V65/이미 V65, SHA/branch/catalog/image 불일치, 미검토 V66, 실패·중복·부분 이력, 백업 누락/hash/gzip/시각, 준비되지 않은 환경/정책, 교체 후 불완전 이력/컬럼/CHECK, 제외 서비스 변경 및 무자동복구를 합성 데이터와 mock으로 확인한다. 실제 DEV 연결/권한, MySQL 버전 호환성, live 백업·쓰기 중지·복구 실행 및 배포 E2E는 이 테스트의 검증 범위 밖이다.
