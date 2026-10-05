# DEV 로그 메타데이터 조회 계획 (#479)

2026-09-26의 DEV CloudWatch 14일 보존 보고는 과거 증거다. 현재 live DEV, prod, 개별 로그 만료 시각은 아직 확인되지 않았다. 이번에는 원문 로그·학생 대화·전체 env를 읽지 않고 **DEV main/ai의 그룹/보존 설정과 Docker logging/rotation metadata**만 좁혀 확인하는 계획을 준비한다. SES GET AccessDenied는 CloudWatch 권한 판정 근거가 아니다.

기존 DEV 접근 권한의 운영자가 현재 대상의 full container ID·Compose project/service를 먼저 확인한다. `ACTUAL_DEV_MAIN_FULL_ID`/`ACTUAL_DEV_AI_FULL_ID`는 placeholder이며 현재 대상 확인 없이 실행하지 않는다. 여러 후보·project 불일치·prod 대상이면 중단한다. 기존 mail 시험용 main 컨테이너 증거와 맞추면 별도 발견 조회를 줄일 수 있다.

```bash
# selected metadata only; Config.Env와 전체 LogConfig/inspect를 출력하지 않는다.
date -u '+observedAt=%Y-%m-%dT%H:%M:%SZ'
docker inspect --format '{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.HostConfig.LogConfig.Type}}|{{index .HostConfig.LogConfig.Config "awslogs-region"}}|{{index .HostConfig.LogConfig.Config "awslogs-group"}}|{{index .HostConfig.LogConfig.Config "max-size"}}|{{index .HostConfig.LogConfig.Config "max-file"}}' ACTUAL_DEV_MAIN_FULL_ID
docker inspect --format '{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.HostConfig.LogConfig.Type}}|{{index .HostConfig.LogConfig.Config "awslogs-region"}}|{{index .HostConfig.LogConfig.Config "awslogs-group"}}|{{index .HostConfig.LogConfig.Config "max-size"}}|{{index .HostConfig.LogConfig.Config "max-file"}}' ACTUAL_DEV_AI_FULL_ID
```

저장소 DEV 예제는 서울의 `/edupilot/dev/main`, `/edupilot/dev/ai`다. 위 실제 driver/region/group이 이 값과 정확히 일치할 때만 다음 두 metadata GET을 사용한다. 다르면 실제 값을 먼저 보고하고 조회 대상을 다시 고정한다. 임의 prod 탐색·default region·전체 로그 그룹 나열로 확대하지 않는다.

```bash
date -u '+observedAt=%Y-%m-%dT%H:%M:%SZ'
aws logs describe-log-groups --region ap-northeast-2 \
  --log-group-name-prefix /edupilot/dev/main --no-cli-pager \
  --query "logGroups[?logGroupName == '/edupilot/dev/main'].{logGroupName:logGroupName,retentionInDays:retentionInDays}" --output json
aws logs describe-log-groups --region ap-northeast-2 \
  --log-group-name-prefix /edupilot/dev/ai --no-cli-pager \
  --query "logGroups[?logGroupName == '/edupilot/dev/ai'].{logGroupName:logGroupName,retentionInDays:retentionInDays}" --output json
```

결과는 `조회 시각 UTC → service/container 증거 → driver → region/group → retentionInDays → rotation max-size/max-file → read status`로 인계한다. `retentionInDays` 필드가 없으면 해당 그룹의 보존기간 미설정으로 기록한다. IAM 오류·빈 결과·driver option 누락을 14일/0건/삭제 완료로 해석하지 않는다. awslogs driver에서 json-file 전용 max-size/max-file 누락은 CloudWatch 보존 설정과 별개다. 값은 조회 시점 설정이며 14일의 모든 이벤트가 즉시 물리 삭제됐다는 증거가 아니다. 정확한 삭제/만료 시각은 이 메타 조회로 증명하지 않는다.

금지되는 확대 작업은 원문 `get-log-events`/`filter-log-events`/Logs Insights, log dump/export, 학생 대화 조회, IAM grant, retention 설정 변경, logging driver/rotation 변경, prod 조회다. 이 문서는 **조회 계획만 준비**하며 실제 조회 결과가 아니다. 기존 권한 GET이 거부되면 그 정확한 API와 거부를 기록하고 권한을 늘리지 않는다. paid AI 대안/비용 검토는 별도이며 유료 호출은 0건이다.

AWS 공식 API의 [DescribeLogGroups](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_DescribeLogGroups.html)와 [보존기간 변경·삭제 지연 설명](https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutRetentionPolicy.html)을 기준으로 설정값과 이벤트 삭제 완료를 구분한다. PutRetentionPolicy 링크는 해석 참고이며 변경 실행 제안이 아니다.
