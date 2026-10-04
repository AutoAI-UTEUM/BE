# 로그 14일 근거와 최소 조회·변경 승인안 (#471/#479)

2026-10-04 저장소 기준의 읽기 검토다. CloudWatch·실서버·운영 로그·IAM·계정·학생·token을 조회하지 않았고 권한/보존/보안 설정을 변경하지 않았다. AI/FE 회신의 “14일·한승준 운영 담당”을 실제 값·사용자 승인으로 단정하지 않는다.

## 저장소에서 확인한 것

| 대상 | 현재 근거 | 실제 적용과의 구분 |
| --- | --- | --- |
| Main/AI 원격 로그 | [prod Compose](../../docker-compose.prod.yml)의 `awslogs`, ap-northeast-2, `/edupilot/${ENVIRONMENT}/main`·`/ai`, `cache-disabled=false` | DEV도 override를 사용하고 [DEV workflow](../../.github/workflows/deploy-dev.yml)가 ENVIRONMENT=dev를 지정한다. 실제 driver/group/환경은 아직 읽지 않았음 |
| Main/AI 그룹14일 | [app-alarms.sh](../../infra/cloudwatch/app-alarms.sh) 19–20행의 두 환경별 그룹에 59–60행 `put-retention-policy --retention-in-days 14` | **실행 시 설정할 값**. 현재 CloudWatch retentionInDays의 조회 결과가 아님. 이 script는 metric filter/alarm도 쓰므로 읽기 확인에 실행하지 않음 |
| 운영 근거 | [기존 runbook](../runbook-app-alarms.md)의 그룹14일 적용/describe 절차와 적용기록 표 | DEV/prod 행이 미실시·미검증·담당 `-`임. 정책 설명과 실제 운영 완료 근거를 구분 |
| Nginx 등 로컬 로그 | base/prod Compose의 json-file, max-size50m/max-file3; Nginx가 해당 설정 상속 | 크기/파일 수 회전이며 **14일 보존 아님**. live 옵션·로컬 파일·export/backup·다른 수집기는 미확인 |
| Main/AI 로컬 읽기 캐시 | Compose cache-disabled=false와 [Docker dual logging](https://docs.docker.com/engine/logging/dual-logging/) | 기본20m/5는 크기 회전이다. CloudWatch14일이 cache의 시간 제한을 설정하지 않음. 실제 Engine/override/캐시 파일은 미확인 |
| 권한·담당 | [저장소 IAM 예제](../../infra/iam/ec2-cloudwatch-logs-policy.json)는 EC2 로그 쓰기/그룹관리 예제 | 사용자 읽기 권한·현재 역할 부착·한승준 개인 운영 담당 확정의 증거가 아님 |

[awslogs 문서](https://docs.docker.com/engine/logging/drivers/awslogs/)의 그룹/stream 전달과 [CloudWatch 보존 설정](https://docs.aws.amazon.com/cli/latest/reference/logs/put-retention-policy.html)은 다른 설정이다. Compose create-group=true·배포 success·앱 UP만으로 retention14 적용을 추정하지 않는다. 실제 증거에는 승인된 환경/계정·지역·정확한 두 group name·retentionInDays·조회시각·확인자를 남기고 ARN/계정 ID 등 공유 제한 메타는 부모와 정한다.

## 먼저 승인할 최소 읽기 범위 — 현재 미승인·미실행

기본 제안 환경은 **DEV만**이다. 실제 계정/리전/담당/기한을 먼저 확인하고 아래 exact 대상과 출력 필드를 고정한다. prod·다른 log group·bucket/DB·실본문을 포함하지 않는다.

| 조회 대상 | 필요한 최소 필드/행위 | 승인에서 제외할 범위 |
| --- | --- | --- |
| `/edupilot/dev/main`, `/edupilot/dev/ai` 그룹 | 운영자가 exact 두 그룹의 group name/retentionInDays를 읽어 비밀 없는 결과 제출 | GetLogEvents/FilterLogEvents/Insights 원문 검색·export·PutRetentionPolicy·로그 삭제/생성·경보 변경 |
| DEV main-service/ai-service/nginx | 고정 서비스의 실행 image tag/digest, logging driver와 allowlisted group/region/cache-disabled/cache-max-size/cache-max-file/max-size/max-file 옵션 | inspect 전체 Env/LogConfig·.env·AWS/JWT/DB key·로그 메시지·다른 컨테이너 |
| Nginx 유효 설정·수집 경계 | 지정 운영자가 access/error log destination·format/수집기·원문 저장 전 보호 유무만 비밀 제거하여 확인 | nginx -T 원문/전체 server config·reload·권한/level/보존 변경·운영 token query 검색 |
| 역할·소유 책임 | 현재 담당자의 역할명/승인자·유효 기간과 위 metadata 조회 가능 여부만 확인 | IAM 정책 부착·docker 그룹 가입·sudo/root/광범위 admin 권한 제공 |

가장 작은 실행안은 이미 권한이 있는 운영 담당자가 제한된 조회 결과를 제출하는 것이다. 추가 계정 권한이 꼭 필요하면 담당자가 **지정 리소스·metadata 행위·짧은 유효 기간**만 포함한 변경안을 작성해 사용자 승인을 받아야 한다. 이 문서는 IAM JSON을 적용하거나 권한을 부여하지 않는다. Describe API가 resource/row 제한을 지원하지 않는 경우 검토된 운영자 대행 결과·제한 조회 경로로 범위를 보존하며 전역 권한을 자동 허용하지 않는다.

실제 보존값이14가 아니거나 없으면 관찰값과 영향을 보고한다. 14로 자동 정정하지 않는다. 기존보다 짧은 보존 적용은 과거 로그 만료에 영향을 줄 수 있으므로 보존 목적/예외·사용자 승인·복구/원본 보관 범위를 별도로 정해야 한다. runbook의 IAM 부착·app-alarms 실행·container 재생성·모의로그 put 단계는 이번 읽기 확인에 포함하지 않는다.

## verify-email과 연결되는 변경안

[별도 Nginx 검토안](fe-auth-contract/NGINX-FOLLOWUP.md)은 SPA 직접 경로와 access query/Referer 제외다. 운영 access parser가 JSON을 지원하는지·최초 요청과 internal redirect 로그를 합성 marker로 확인할 계획이며 실token/학생값을 쓰지 않는다.

가짜 upstream 오류502에서 error log에 합성 query가 남았으므로 full exposure 인수는 미완료다. 후보 접근 형식만으로 원본 Docker/local cache/원격 수집/backup을 모두 보호했다고 주장하지 않는다. token fragment 계약은 FE229의 query 소비자 변경·호환 시험과 함께 합의해야 하며 BE만 바꾸지 않는다. 원문 저장 전 보호 경로도 실제 저장 위치·권한·운영 변경 승인을 먼저 검토한다. 사후 중앙 마스킹 뒤 원본이 남는 구성은 완전한 해결이 아니다.

운영 담당 한승준은 타팀 회신의 지정/제안이며 사용자 확정과 실제 접근 설정은 확인하지 못했다. 적용/읽기 담당·책임·기간을 부모/사용자가 확정해야 한다. 실메일 담당과 유효 `EDUPILOT_MAIL_BASE_URL`의 FE origin 역시 미확정이다. 지금 기능 ON·security 설정·권한·보존을 변경하지 않는다.

## AI reasoning 평가와 Spring 책임

비용 모델·reasoning 포함 신뢰할 상한·평가 budget과 기관 월budget은 AI 담당/사용자 결정이 필요하다. Spring HTTP timeout은 비용 상한 증거가 아니다. #500 offline monetary ledger는 평가 보조이며 서비스 전체 기관 quota 완료가 아니다.

Spring은 기존 인증/권한·현재 동의/계정 gate·요청 DTO·snapshot/version·AI 오류의 비동기 FAILED/failureCode/fallback 전달 경계를 검증한다. 미완료 전역 age/외부AI 동의 경계를 성공으로 만들지 않는다. 실제 모델 호출·가격/예산 임의 결정·권한/보안 변경·전체 Spring↔AI↔FE 인수는 실행하지 않았다.
