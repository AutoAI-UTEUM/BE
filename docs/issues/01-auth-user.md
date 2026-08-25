# [Feature] 회원가입·로그인·사용자 인증 구현

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [간결한 Epic 초안](epics/02-auth-user.md)을 사용합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·예외·검증 참고 |
| Status | Todo |
| Priority | High |

권장 라벨:

```text
area: frontend
area: main-service
area: integration
type: feature
```

## 목표

사용자가 계정을 만들고 로그인한 뒤 JWT access token으로 자신의 정보와 보호된 EduPilot 기능에 접근할 수 있게 한다.

## 연결 요구사항

- `AUTH-001` 회원가입
- `AUTH-002` 로그인
- `AUTH-003` 내 정보 조회
- `AUTH-004` 만료·위조 토큰 거부
- `AUTH-005` refresh token — 이번 부모 이슈 포함 여부는 `DEC-004` 결정에 따름

## 사용자 흐름

1. 사용자가 이메일, 비밀번호, 이름과 `LEARNER | INSTRUCTOR` 역할로 가입한다.
2. 사용자가 이메일과 비밀번호로 로그인한다.
3. Main Service가 자격 증명을 검증하고 JWT를 발급한다.
4. Frontend가 인증이 필요한 API에 Bearer token을 전송한다.
5. 사용자가 자신의 정보를 조회한다.

## 범위

### 포함

- 회원가입, 로그인, 내 정보 API
- 공개 가입 역할 선택과 역할 포함 응답·JWT 계약
- 이메일 중복과 입력 검증
- 비밀번호 단방향 해시
- JWT access token 생성·검증
- Spring Security 인증 필터/인가 기반
- 인증·권한 실패 공통 오류
- 로그인/회원가입 FE 화면과 API 연동

### 제외

- OAuth/social login
- 비밀번호 찾기·이메일 인증
- ADMIN 상세 관리 화면
- 강사 전용 기능·차등 권한(#102)
- refresh token은 `DEC-004`에서 Must로 승격된 경우에만 포함

## 작업 후보 — 필요할 때만 Sub-issue 생성

- `[Contract]` Auth/User 외부 API와 오류 계약
- JWT 만료·refresh token 정책 확정 — 실제 차단 시에만 별도 `[Decision]` 이슈 생성
- `[Main]` User 엔티티·Repository·회원가입 구현
- `[Main]` 로그인·JWT 발급·Spring Security 구성
- `[Main]` 내 정보 조회와 소유자 기준 구현
- `[FE]` 회원가입·로그인·인증 상태 처리
- `[Integration]` Auth 정상·실패 흐름 통합 테스트
- `[Security]` 위조·만료·권한 부족 회귀 테스트

## 외부 API 초안

```http
POST /api/auth/signup
POST /api/auth/login
GET  /api/users/me
```

## 선행 의존성

- [프로젝트 기반과 공통 오류 계약](00-foundation.md)
- `DEC-004` JWT 정책

## 주요 예외

- 중복 이메일
- 잘못된 이메일/비밀번호 형식
- 잘못된 자격 증명
- 만료·위조 JWT
- 삭제/비활성 사용자
- 누락·지원하지 않는 가입 역할
- 인증이 필요한 API의 토큰 누락

## 보안 규칙

- 비밀번호 원문을 저장하거나 로그에 남기지 않는다.
- `passwordHash`, JWT secret, access token을 API 응답/로그에서 보호한다.
- 인증과 리소스 소유권 검증을 같은 개념으로 취급하지 않는다.
- 구체적인 비밀번호 정책과 JWT 만료 시간은 승인된 결정에 따른다.

## 완료 조건

- [ ] 회원가입·로그인·내 정보 API 계약이 승인됐다.
- [ ] 비밀번호가 검증된 단방향 해시로 저장된다.
- [ ] 정상 로그인으로 access token이 발급된다.
- [ ] 보호 API가 유효한 토큰만 허용한다.
- [ ] 만료·위조·누락 토큰이 합의된 오류로 거부된다.
- [ ] Frontend에서 회원가입과 로그인 흐름이 동작한다.
- [ ] 인증·인가 핵심 테스트가 통과한다.
- [ ] Swagger와 오류 코드 문서가 실제 구현과 일치한다.
