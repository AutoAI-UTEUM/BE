# EduPilot 배포·롤백 운영 가이드

이 문서는 이슈 #45와 후속 이슈 #88 범위의 Main Service, AI Service, MySQL, Nginx
구성과 dev 배포 절차를 설명합니다. Frontend 빌드·배포는 후속 작업입니다.

## 1. 요구 사항

- Docker Engine
- Docker Compose 2.24.4 이상 (`!reset`을 사용하는 운영 오버레이)
- 로컬 실행 시 8080 포트
- dev 서버 실행 시 80/443 포트와 제한된 관리용 22 포트

실제 비밀번호, JWT secret, 내부 토큰, GHCR token, 인증서는 저장소에 커밋하지
않습니다.

## 2. 로컬 실행

저장소를 clone한 후 루트 환경 예제를 복사합니다.

```bash
cp .env.example .env
```

`.env`의 예제 비밀번호와 토큰을 로컬 전용 값으로 교체합니다. JWT secret은 최소
32바이트의 무작위 값을 Base64로 인코딩해 사용합니다.

Compose 문법과 최종 설정을 먼저 확인합니다.

```bash
docker compose --env-file .env config --quiet
docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml config --quiet
```

서비스를 시작하고 상태를 확인합니다.

```bash
docker compose up -d --build
docker compose ps
curl --fail http://localhost:8080/api/health
docker compose logs main-service
```

로그에서 Flyway V1~V7 적용과 Main Service 기동 성공을 확인합니다.

기본 구성은 MySQL 포트를 호스트에 공개하지 않습니다. Workbench가 필요한 로컬
환경에서만 `docker-compose.yml`의 아래 주석을 해제합니다.

```yaml
ports: ["127.0.0.1:3306:3306"]
```

외부 인터페이스 전체가 아닌 loopback에만 바인딩해야 합니다.

## 3. 업로드 경로와 영속성 검증

Main Service의 실제 Spring 설정은 다음 환경 변수를 읽습니다.

```yaml
edupilot:
  storage:
    root-directory: ${EDUPILOT_STORAGE_DIR}
```

Compose는 다음 두 값을 모두 `/var/lib/edupilot/storage`로 고정합니다.

- Main Service의 `EDUPILOT_STORAGE_DIR`
- `uploads` named volume의 컨테이너 mount 대상

둘 중 하나만 변경하면 새 이미지가 기존 업로드 volume과 다른 경로를 사용해 파일이
유실된 것처럼 보일 수 있습니다. 경로 변경 시에는 두 설정과 기존 데이터 이동 계획을
같은 변경으로 다뤄야 합니다.

렌더링된 설정과 컨테이너 환경을 확인합니다.

```bash
docker compose --env-file .env config
docker compose exec main-service sh -c \
  'test "$EDUPILOT_STORAGE_DIR" = /var/lib/edupilot/storage'
```

marker만 생성하지 말고 애플리케이션의 실제 업로드 API로 검증합니다. `jq`가 설치된
환경에서 다음 예시를 사용할 수 있습니다.

```bash
curl --fail --silent \
  -H 'Content-Type: application/json' \
  -d '{"email":"storage-check@example.com","password":"password123","name":"Storage Check","role":"LEARNER"}' \
  http://localhost:8080/api/auth/signup

ACCESS_TOKEN="$(
  curl --fail --silent \
    -H 'Content-Type: application/json' \
    -d '{"email":"storage-check@example.com","password":"password123"}' \
    http://localhost:8080/api/auth/login |
  jq -r '.data.accessToken'
)"

UPLOAD_RESPONSE="$(
  curl --fail --silent \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -F 'title=Storage persistence check' \
    -F 'file=@/absolute/path/to/test.pdf;type=application/pdf' \
    http://localhost:8080/api/materials
)"
MATERIAL_ID="$(printf '%s' "$UPLOAD_RESPONSE" | jq -r '.data.materialId')"

docker compose exec main-service sh -c \
  'test "$EDUPILOT_STORAGE_DIR" = /var/lib/edupilot/storage &&
   find "$EDUPILOT_STORAGE_DIR/materials" -type f -name "*.pdf" -print'
```

AI profile이 비활성 상태이므로 비동기 PDF 텍스트 추출은 실패할 수 있지만, 업로드
원본은 위 storage 경로에 저장되어야 합니다.

volume을 삭제하지 않고 재기동한 뒤 같은 자료를 다시 내려받습니다.

```bash
docker compose down
docker compose up -d

curl --fail \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "http://localhost:8080/api/materials/$MATERIAL_ID/file" \
  --output downloaded-test.pdf
```

`docker compose down -v`는 MySQL과 업로드 named volume을 삭제하므로 영속성 검증이나
일반 운영 중에는 실행하지 않습니다.

## 4. 이미지와 healthcheck 검증

Main Service healthcheck는 런타임 이미지 안의 `curl`을 사용합니다. Dockerfile은
Temurin 21 JRE 단계에서 `curl`과 `tzdata`를 명시적으로 설치합니다.

```bash
docker compose exec main-service curl --version
docker inspect --format '{{.State.Health.Status}}' edupilot-main-service-1
curl --fail http://localhost:8080/api/health
```

`curl --version`과 health 상태 `healthy`, 외부 health 응답 200을 모두 확인해야 합니다.

AI Service는 Python 3.14·uv 기반 멀티스테이지 이미지로 빌드하며, 런타임
healthcheck는 `curl` 대신 Python `urllib.request`로 `/health`를 확인합니다. 로컬에서는
`ai` profile을 명시할 때만 기동하고 외부 포트는 공개하지 않습니다.

```bash
docker build --tag edupilot-ai-service:local ai-service
docker compose --env-file .env --profile ai config
```

출력의 `ai-service`에는 build와 healthcheck가 있고 `ports`는 없어야 합니다. 운영
오버레이를 병합하면 profile 게이트와 build가 제거되고
`ghcr.io/autoai-uteum/ai-service:${TAG}` 이미지를 사용해야 합니다.

## 5. 최초 1회 dev 서버 준비

### 5.1 EC2와 Docker

1. Ubuntu LTS EC2를 생성하고 영속 EBS volume을 사용합니다.
2. 보안 그룹은 80/443과 관리용 22만 허용합니다. 22는 관리자 고정 IP로 제한합니다.
3. Docker Engine과 Docker Compose plugin을 설치합니다.
4. 배포 사용자에게 Docker 실행 권한을 부여하고 다시 로그인합니다.
5. 배포 디렉터리를 만들고 배포 사용자가 소유하게 합니다.

기본 배포 경로 예시는 `/opt/edupilot`입니다.

```bash
sudo install -d -o "$USER" -g "$USER" /opt/edupilot
cd /opt/edupilot
```

루트 `.env.example`을 참고해 서버 전용 `.env`를 만들고 권한을 제한합니다.

```bash
chmod 600 /opt/edupilot/.env
```

서버 `.env`에서는 다음 값을 dev 환경에 맞게 설정합니다.

- `SPRING_PROFILES_ACTIVE=prod`
- `EDUPILOT_DB_*`
- `EDUPILOT_CORS_ALLOWED_ORIGINS=https://YOUR_DEV_DOMAIN`
- `EDUPILOT_JWT_SECRET`
- `EDUPILOT_INTERNAL_TOKEN`
- `XAI_API_KEY`
- `EDUPILOT_AI_BASE_URL`
- `EDUPILOT_DOMAIN`
- `EDUPILOT_STORAGE_DIR=/var/lib/edupilot/storage`

### 5.2 DNS와 최초 인증서

도메인의 A 레코드를 EC2 public IP에 연결하고 전파를 확인합니다. 운영 Compose의
Nginx는 인증서가 있어야 시작되므로, 최초 1회는 임시 Nginx와 동일한 named volume을
사용해 webroot 방식으로 인증서를 먼저 발급합니다.

```bash
docker volume create edupilot-certbot-www
docker volume create edupilot-certbot-certs

docker run --detach --name edupilot-cert-bootstrap \
  --publish 80:80 \
  --volume edupilot-certbot-www:/usr/share/nginx/html \
  nginx:stable

docker run --rm \
  --volume edupilot-certbot-www:/var/www/certbot \
  --volume edupilot-certbot-certs:/etc/letsencrypt \
  certbot/certbot certonly \
  --webroot --webroot-path /var/www/certbot \
  --domain YOUR_DEV_DOMAIN \
  --cert-name edupilot \
  --email YOUR_EMAIL \
  --agree-tos --no-eff-email

docker rm --force edupilot-cert-bootstrap
```

`YOUR_DEV_DOMAIN`과 `YOUR_EMAIL`은 실제 값으로 대체하되 저장소 파일에는 기록하지
않습니다. 인증서는 `edupilot-certbot-certs` named volume에 저장됩니다.

### 5.3 GitHub 설정

GitHub `dev` Environment와 다음 Secrets를 등록합니다.

| Secret | 용도 |
| --- | --- |
| `DEV_SSH_HOST` | EC2 주소 |
| `DEV_SSH_USER` | 배포 사용자 |
| `DEV_SSH_KEY` | 배포용 SSH private key |
| `GHCR_TOKEN` | private GHCR push/pull용 `packages:write` token |

다음 값은 Repository Variables로 등록합니다.

| Variable | 예시 |
| --- | --- |
| `DEV_DOMAIN` | `dev.example.com` |
| `DEV_DEPLOY_PATH` | `/opt/edupilot` |
| `GHCR_USERNAME` | GHCR token 소유 GitHub 사용자 |

## 6. dev 배포

`deploy-dev.yml`은 `develop` 브랜치에서 `workflow_dispatch`로만 실행됩니다.

1. 대상 `develop` SHA를 확인합니다.
2. Actions의 `Deploy dev`를 수동 실행합니다.
3. Main Service와 AI Service 이미지에 SHA와 `latest` 태그가 push됐는지 확인합니다.
4. SCP, Compose pull/up, smoke test가 순서대로 성공했는지 확인합니다.
5. `https://DEV_DOMAIN/api/health`가 200인지 다시 확인합니다.
6. `https://DEV_DOMAIN/api/health/ready` 응답의 `aiService`가 `UP`인지 확인합니다.

워크플로는 Compose와 Nginx 설정만 전달하며 서버의 `.env`를 덮어쓰지 않습니다.

### 6.1 운영 primary host 전환

운영 primary host는 `https://www.uteum.com`입니다. 운영 서버 `.env`와 외부 콘솔은
다음 계약을 함께 만족해야 합니다.

- `EDUPILOT_DOMAIN="www.uteum.com uteum.com"`처럼 primary와 apex를 모두 Nginx
  `server_name`에 포함합니다. apex 연결 자체가 차단되는 회선에서는 HTTP redirect가
  실행될 수 없으므로 redirect를 복구 수단으로 사용하지 않습니다.
- 인증서는 `www.uteum.com`과 `uteum.com`을 모두 SAN으로 발급·갱신합니다.
- 운영 CORS는
  `EDUPILOT_CORS_ALLOWED_ORIGINS=https://www.uteum.com,https://uteum.com`으로
  제한합니다. 기존 DuckDNS host는 CORS, Nginx `server_name`, 인증서 SAN과 smoke
  URL에서 모두 제외합니다.
- refresh 쿠키는 같은 Nginx의 `/api`에 요청하는 host-only 쿠키를 유지합니다.
  `Domain=.uteum.com`으로 범위를 넓히지 않습니다. apex에서 로그인했던 사용자는
  www에서 한 번 다시 로그인해야 합니다.
- GitHub prod Environment의 `PROD_DOMAIN`은 `www.uteum.com`으로 설정합니다. prod
  workflow는 배포 전에 이 값을 검증하고 www 기준 health, CORS, HTTP 404 smoke를
  수행합니다.

## 7. 롤백

배포 전 정상 동작한 이전 git SHA를 기록합니다. 애플리케이션 롤백은 이전 이미지
태그로 재기동합니다.

```bash
cd /opt/edupilot
PREVIOUS_TAG=previous-git-sha

ENVIRONMENT=dev TAG="$PREVIOUS_TAG" docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml pull main-service
ENVIRONMENT=dev TAG="$PREVIOUS_TAG" docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml up -d main-service nginx

curl --fail "https://YOUR_DEV_DOMAIN/api/health"
```

`latest`가 아니라 검증된 SHA를 사용합니다.

### Flyway 원칙

- 애플리케이션 기동이 곧 Flyway migration 적용입니다.
- 기존 migration 파일은 배포 후 수정하지 않습니다.
- migration 실패 시 DB를 억지로 이전 버전으로 되돌리거나 rollback migration을
  만들지 않습니다.
- 원인을 수정한 새 forward-fix migration을 추가하고 다시 배포합니다.
- 애플리케이션 이미지만 롤백할 때는 이전 코드가 현재 schema와 호환되는지 먼저
  확인합니다.

## 8. 장애 대응과 백업

현재 상태와 최근 로그를 확인합니다.

```bash
ENVIRONMENT=dev TAG=current-git-sha docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml ps
ENVIRONMENT=dev TAG=current-git-sha docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml logs --tail=200 main-service
ENVIRONMENT=dev TAG=current-git-sha docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml logs --tail=200 mysql nginx
```

DB 변경 배포 전에는 논리 백업을 생성합니다.

```bash
ENVIRONMENT=dev TAG=current-git-sha docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot \
    --single-transaction --all-databases' > "mysql-$(date +%Y%m%d-%H%M%S).sql"
```

실행 중인 MySQL data directory를 단순 `tar`로 복사하지 않습니다. EBS snapshot이나
volume 단위 백업은 DB 일관성을 확보한 후 수행합니다.

인증서는 주기적으로 갱신하고 Nginx를 reload합니다.

```bash
docker run --rm \
  --volume edupilot-certbot-www:/var/www/certbot \
  --volume edupilot-certbot-certs:/etc/letsencrypt \
  certbot/certbot renew --webroot --webroot-path /var/www/certbot

ENVIRONMENT=dev TAG=current-git-sha docker compose --env-file .env \
  -f docker-compose.yml -f docker-compose.prod.yml exec nginx nginx -s reload
```

## 9. 이번 작업 이후 수동 확인

AI Service Docker 이미지와 Compose·Deploy dev 계약의 저장소 반영은 완료됐습니다.
병합 후 실제 dev 환경에는 다음 확인이 남습니다.

- 서버 `.env`에 실제 `XAI_API_KEY` 추가
- develop 병합 후 Actions의 `Deploy dev` 실행
- `https://dev.uteum.com/api/health/ready`에서 전체 상태와 `aiService`가 `UP`인지 확인
- EC2와 보안 그룹 생성
- DNS 연결과 certbot 최초 발급
- GitHub Secrets·Variables·dev Environment 등록
- dev workflow 최초 실행
- HTTPS smoke test
- 이전 SHA rollback 1회 리허설
- MySQL 백업 파일 복구 가능 여부 확인
