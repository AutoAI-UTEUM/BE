# GitHub 이슈·PR 협업 작업 흐름

| 항목 | 내용 |
| --- | --- |
| 상태 | 팀 운영 초안 |
| 마지막 갱신 | 2026-07-20 |
| 적용 범위 | EduPilot Backend · AI Service · Frontend 협업 |

> Backend 저장소는 `AutoAI-EduPilot/edupilot-be`를 사용한다. `owner/frontend-repo`는 Frontend 저장소명이 확정되면 실제 이름으로 바꾼다.

## 1. 목적

EduPilot은 Spring Main Service와 FastAPI AI Service를 같은 Backend 저장소에서 관리하고, Frontend는 별도 저장소에서 관리한다. 이 문서는 다음을 일관되게 추적하기 위한 기준이다.

- 여러 서비스가 함께 구현하는 전체 기능
- 역할별 실제 작업과 담당자
- Frontend ↔ Main Service ↔ AI Service API 계약
- Pull Request와 이슈의 관계
- 통합 테스트와 기능 완료 여부
- 배포 작업과 개발 완료의 구분

## 2. 저장소 구성

```text
backend-repo
├─ main-service/          # Spring Backend
├─ ai-service/            # FastAPI AI Service
├─ 전체 기능 부모 이슈
├─ API 계약 및 기술 논의
└─ 통합·배포 관련 이슈

frontend-repo
├─ Frontend 코드
├─ FE 세부 작업 이슈
└─ FE Pull Request
```

운영 원칙:

- Backend 저장소를 전체 기능과 API 계약 논의의 기준 저장소로 사용한다.
- Frontend 저장소는 FE 코드, FE 전용 작업 이슈, FE PR을 관리한다.
- 여러 파트가 연결되는 기능은 Backend 저장소에 부모 이슈를 먼저 만든다.
- 실제 작업은 담당자·저장소·PR·차단 상태를 독립적으로 추적해야 할 때만 하위 이슈로 나누고 GitHub Sub-issue 관계로 연결한다.
- Frontend 하위 이슈는 Frontend 저장소에 생성한 뒤 Backend 부모 이슈에 연결한다.
- Project board는 가능하면 조직 단위로 생성하여 두 저장소의 이슈를 함께 관리한다.
- 최종 API 계약의 기준은 이슈 본문이 아니라 승인된 OpenAPI/Swagger와 `docs/api-spec.md`다.

## 3. 저장소별 이슈 작성 기준

### Backend 저장소

- 전체 기능 부모 이슈
- Spring Main Service 작업
- FastAPI AI Service 작업
- Main Service ↔ AI Service 연동
- FE ↔ Main Service API 계약
- 인증·인가, DB, migration
- 배포와 환경 설정
- 통합 테스트
- 여러 파트에 영향을 주는 버그
- 개발을 실제로 막거나 여러 팀의 합의가 필요한 기술적 의사결정

### Frontend 저장소

- FE 화면과 컴포넌트 구현
- FE 내부 상태 관리
- FE 전용 버그
- FE만으로 끝나는 리팩터링
- 접근성, 반응형, 화면 스타일 수정
- Backend 계약 변경 없이 완료되는 작업

FE 작업이 전체 기능의 일부라면 Frontend 이슈를 Backend 부모 이슈의 Sub-issue로 연결한다.

## 4. Epic과 하위 이슈

### Backend 저장소에 Epic을 만들 작업

- Frontend, Main Service, AI Service 중 두 파트 이상이 연결됨
- API 계약이 필요함
- 여러 PR로 나뉨
- 통합 테스트가 별도로 필요함
- 한 담당자의 완료만으로 전체 기능이 끝나지 않음

Epic은 목표, 범위, 제외 범위, 핵심 하위 작업, 완료 조건, 관련 문서 링크만 담는다. 상세 API JSON, 전체 예외 목록, 상태 전이는 OpenAPI와 `docs/feature-spec.md`, `docs/api-spec.md`에서 관리한다.

### 별도 Sub-issue를 만들 기준

다음 중 하나에 해당할 때만 Epic 체크박스를 실제 이슈로 분리한다.

- 담당자 또는 저장소가 다르다.
- 독립 PR이 필요하다.
- 다른 작업을 막거나 별도 완료 상태를 추적해야 한다.
- 계약 승인 또는 통합 테스트가 독립 산출물이다.

역할이 다르다는 이유만으로 모든 `[Contract]`, `[Main]`, `[AI]`, `[FE]`, `[Integration]` 이슈를 자동 생성하지 않는다.

예시:

```text
backend-repo#120 [Epic] AI 학습 턴: 페이지 설명·질의응답·스트리밍
│
├─ backend-repo#121 [Contract] 학습 질문 API 계약
├─ backend-repo#122 [BE/AI] 학습 질문 전체 서버 흐름 구현
└─ frontend-repo#45 [FE] 학습 세션 질문 UI 구현
```

### 이슈 하나로 충분한 작업

- Main Service 내부의 작은 수정
- AI Service 내부 프롬프트/파서 수정
- FE만 변경하는 작은 UI 작업
- 하나의 담당자와 하나의 PR로 완료 가능
- 별도 공통 계약이나 통합 테스트가 필요하지 않음

## 5. Project 필드

Project board를 사용할 경우 상태와 우선순위는 라벨이 아니라 Project 필드를 기준으로 관리한다.

### Status

```text
Todo
In Progress
Blocked
Done
```

| 상태 | 적용 시점 |
| --- | --- |
| Todo | 이슈 생성 후 작업 시작 전 |
| In Progress | 담당자가 정해지고 실제 작업을 시작함 |
| Blocked | 다른 결정, 계약, 이슈 또는 PR 때문에 진행할 수 없음 |
| Done | 완료 조건을 충족하고 이슈를 닫음 |

`Blocked`로 변경할 때는 실제 `blocked by` 관계를 연결하고 다음 형식으로 댓글을 남긴다.

```markdown
현재 작업이 Blocked 상태입니다.

원인:
- 응답 API의 references 필드가 확정되지 않았습니다.

해제 조건:
- AutoAI-EduPilot/edupilot-be#121 API 계약 Approved
```

### Priority

```text
High
Normal
Low
```

| 값 | 기준 |
| --- | --- |
| High | 다른 작업을 막거나 MVP 핵심 흐름에 필요 |
| Normal | 일반 기능과 개선 작업 |
| Low | 후순위 개선, 선택 기능, 정리 작업 |

Project Status와 Priority를 쓰지 않는 경우에만 `status:*`, `priority:*` 라벨 사용을 검토한다. 두 방식을 동시에 사용하지 않는다.

## 6. 라벨

라벨은 저장소별로 별도 생성해야 한다. Backend와 Frontend 저장소에서 같은 이름을 쓰더라도 자동 동기화되지 않는다.

### 작업 영역

```text
area: frontend
area: main-service
area: ai-service
area: integration
area: infra
area: docs
```

관련 영역이 여러 개면 `area:*` 라벨을 복수 적용할 수 있다.

### 작업 유형

```text
type: feature
type: bug
type: refactor
type: chore
type: docs
```

작업 유형은 원칙적으로 하나만 적용한다.

- `area: docs`: 문서 영역에 영향을 주는 작업
- `type: docs`: 변경 자체가 문서 작업

## 7. 이슈 제목

```text
[Feature] 학습 질문 기능 구현
[Epic] AI 학습 턴: 페이지 설명·질의응답·스트리밍
[Contract] 학습 질문 턴 API 계약
[Main] 학습 세션 생성 API 구현
[AI] 페이지 설명 에이전트 구현
[FE] 퀴즈 선택 화면 구현
[Integration] Spring-AI 턴 처리 통합 테스트
[Infra] 개발 서버 Docker Compose 구성
[Bug] 퀴즈 재제출 시 점수 중복 저장
[Docs] 학습 턴 API 계약 갱신
[Decision] 퀴즈 재제출 정책 확정
[Security] 제출 전 정답 비노출 테스트
[CI] 백엔드 테스트 파이프라인 구성
[CD] dev 자동 배포 구성
[DB] 학습 세션 스키마 migration
```

여러 영역이 겹치는 작업은 `[BE/AI]`, `[FE/Infra]`처럼 슬래시로 병기할 수 있다. 위 목록에 없는 새 접두사가 필요하면 이 절에 추가한 뒤 사용한다.

## 8. 표준 작업 흐름

```text
1. 기능과 요구사항 논의
2. Backend 저장소에 Epic 생성
3. API 계약 Draft 작성
4. 독립 추적이 필요한 작업만 Sub-issue로 생성
5. 담당자, Project Status, Priority 지정
6. 각 담당자가 feature 브랜치 생성
7. 구현과 테스트
8. PR 생성 후 Related to로 이슈 참조
9. 리뷰와 CI 통과
10. develop 병합
11. 필수 하위 이슈 종료
12. 전체 통합 테스트
13. Epic 완료 조건 확인
14. Epic 종료
15. 배포는 별도 release/deployment 이슈로 관리
```

## 9. Epic 템플릿

```markdown
## 목적

사용자 관점에서 이 기능으로 달성하려는 결과를 작성합니다.

## 범위

-

## 제외

-

## 하위 작업

- [ ]
- [ ]
- [ ]

담당자·저장소·PR·차단 상태를 독립적으로 추적할 항목만 Sub-issue로 만들고 링크합니다.

## 관련 문서

- 요구사항:
- 기능 명세:
- API/OpenAPI:
- 상세 작업 계획:

## 완료 조건

- [ ] 필수 하위 이슈가 모두 완료되었다.
- [ ] API 계약이 승인되고 문서에 반영되었다.
- [ ] FE ↔ Main Service 연동이 확인되었다.
- [ ] Main Service ↔ AI Service 연동이 확인되었다.
- [ ] 핵심 통합 테스트가 통과했다.
- [ ] 실패 응답과 주요 예외 상황이 확인되었다.
```

## 10. 하위 작업 이슈 템플릿

```markdown
## 목표

-

## 상위 Epic

- Epic:
- API 계약:

## 작업 범위

-

## 작업 목록

- [ ]
- [ ]
- [ ]

## 계약 및 참고 문서

- API:
- 요구사항:
- 설계 문서:

## 테스트

- [ ] 단위 테스트
- [ ] API/계약 테스트
- [ ] 통합 테스트
- [ ] 수동 확인

## 완료 조건

- [ ] 구현이 완료되었다.
- [ ] 관련 테스트가 통과했다.
- [ ] 필요한 문서가 갱신되었다.
- [ ] PR이 develop에 병합되었다.
```

## 11. API 계약 이슈 템플릿

````markdown
## 목표

-

## 계약 상태

- [ ] Draft
- [ ] Review
- [ ] Approved

## 호출 흐름

Frontend → Main Service → AI Service

## 외부 API

- Method:
- Endpoint:
- 인증:
- Content-Type:

### Request

```json
{}
```

### Response

```json
{}
```

### Error

| HTTP | Error Code | 조건 |
| --- | --- | --- |
| 400 |  |  |
| 401 |  |  |
| 409 |  |  |
| 500 |  |  |

## 내부 AI API

- Method:
- Endpoint:
- Timeout:
- Request:
- Response:
- Error:

## 영향 범위

- FE 화면:
- Main Service:
- AI Service:
- DB:
- 문서:

## 기준 문서

- OpenAPI:
- `docs/api-spec.md`:
- `docs/screen-api-map.md`:

## 완료 조건

- [ ] Frontend 담당자가 검토했다.
- [ ] Main Service 담당자가 검토했다.
- [ ] AI Service 담당자가 검토했다.
- [ ] OpenAPI와 관련 문서에 반영되었다.
````

이슈 본문의 JSON은 논의 초안이다. 승인된 OpenAPI와 API 문서를 최종 계약으로 사용한다.

## 12. 버그 이슈 템플릿

```markdown
## 문제

-

## 실제 동작

-

## 기대 동작

-

## 재현 방법

1.
2.
3.

## 발생 환경

- 환경: local / dev / prod
- 브랜치 또는 버전:
- 사용자/세션 조건:
- 브라우저 또는 OS:

## 증거

- 로그:
- 스크린샷:
- 관련 traceId:
- 관련 API:

민감정보와 토큰은 첨부하지 않습니다.

## 영향 범위

- [ ] Frontend
- [ ] Main Service
- [ ] AI Service
- [ ] Database
- [ ] Infra

## 원인

조사 후 작성합니다.

## 완료 조건

- [ ] 문제를 재현하는 테스트 또는 검증 절차가 있다.
- [ ] 원인이 확인되었다.
- [ ] 수정 후 재현되지 않는다.
- [ ] 관련 회귀 테스트가 통과한다.
- [ ] 필요한 문서가 갱신되었다.
```

## 13. PR 템플릿

이 절이 PR 필수 내용의 **단일 기준**이다. [Git Flow](git-flow.md)와 [협업 가이드](../CONTRIBUTING.md)는 이 절을 참조하며 별도 목록을 두지 않는다.

```markdown
## 변경 요약

-

## 변경 이유 / 연결 요구사항 ID

-

## 테스트 방법과 결과

- [ ] 단위 테스트
- [ ] API/계약 테스트
- [ ] 통합 테스트
- [ ] 수동 확인

## 영향 범위

- API: 있음 / 없음
- DB/migration: 있음 / 없음
- 환경 변수: 있음 / 없음
- 배포: 있음 / 없음
- 문서: 있음 / 없음

## 화면 변경

- 캡처 또는 검증 방법 (없으면 "해당 없음")

## 남은 TBD / 후속 작업

-

## 관련 이슈

Related to #이슈번호
Related to AutoAI-EduPilot/edupilot-be#부모이슈번호

## 확인 사항

- [ ] 관련 없는 변경이 섞이지 않았다.
- [ ] 민감정보와 비공개 정답 데이터가 포함되지 않았다.
- [ ] 필요한 문서가 갱신되었다.
```

## 14. PR과 이슈 연결 규칙

### `Related to`

`Related to`는 GitHub 공식 자동 종료 키워드가 아니라 팀 표기 규칙이다. 이슈 번호 참조를 남기지만 이슈를 자동으로 닫지 않는다.

```text
Related to #123
Related to AutoAI-EduPilot/edupilot-be#123
```

사용 기준:

- `develop` 대상 일반 작업 PR
- 하나의 PR만으로 전체 이슈가 끝나지 않는 경우
- Frontend PR에서 Backend 부모 이슈를 참조하는 경우

Backend 저장소 PR:

```text
Related to #122
Related to #120
```

Frontend 저장소 PR:

```text
Related to #45
Related to AutoAI-EduPilot/edupilot-be#120
```

### 자동 종료 키워드

```text
Closes
Fixes
Resolves
```

세 키워드는 GitHub 기능상 동일하게 자동 종료를 수행한다. 팀에서는 읽기 쉬운 의미로 다음처럼 구분할 수 있다.

- `Closes`: 기능 또는 일반 작업 완료
- `Fixes`: 버그 수정 완료
- `Resolves`: 논의 또는 요구사항 해결

자동 종료는 PR이 해당 저장소의 **default branch**를 대상으로 할 때만 동작한다. `main`이라는 이름 자체가 조건은 아니다.

```text
Closes #123
Fixes AutoAI-EduPilot/edupilot-be#123
```

여러 파트가 연결된 부모 기능 이슈에는 개별 서비스 PR에서 `Closes`를 사용하지 않는다.

## 15. 이슈 종료 기준

### 역할별 하위 이슈

다음 조건을 모두 만족하면 닫는다.

- 해당 구현 완료
- 관련 테스트 통과
- 필요한 문서 반영
- PR이 `develop`에 병합
- 하위 이슈의 완료 조건 충족

`develop` 대상 PR에서는 `Related to`를 사용하고, 병합 후 이슈를 수동으로 닫는 것을 기본으로 한다.

### 부모 기능 이슈

다음 조건을 모두 만족하면 닫는다.

- 필수 하위 이슈 완료
- API 계약 승인
- FE ↔ Main Service 연동 확인
- Main Service ↔ AI Service 연동 확인
- 통합 테스트 통과
- 부모 이슈 전체 완료 조건 충족

하위 PR 하나가 병합되었다는 이유로 부모 이슈를 닫지 않는다.

### 배포 이슈

배포는 별도 `release` 또는 `deployment` 이슈로 관리한다. 기능 이슈의 완료 정의에 운영 배포가 포함된 경우에만 배포 후 기능 이슈를 닫는다.

## 16. 실행 예시: PDF 페이지 기반 학습 질문

### 1단계: 부모 이슈

```text
backend-repo#130
[Feature] 현재 PDF 페이지 기반 학습 질문 기능

(§4 예시의 #120~122와 다른 기능이므로 다른 번호를 사용한다.)

Status: Todo
Priority: High

Labels:
area: frontend
area: main-service
area: ai-service
area: integration
type: feature
```

목표:

```text
사용자가 현재 PDF 페이지에 관해 질문하면
해당 페이지 문맥을 반영한 AI 답변을 받을 수 있다.
```

### 2단계: 하위 이슈

§4의 분리 기준에 따라 독립 추적이 필요한 것만 하위 이슈로 만든다. Main과 AI 작업이 같은 저장소·연속 작업이면 통합 이슈 하나로 관리하고, 통합 테스트는 부모 이슈 완료 조건으로 확인한다.

```text
backend-repo#131 [Contract] 학습 질문 API 계약
backend-repo#132 [BE/AI] 학습 질문 전체 서버 흐름 구현
frontend-repo#46 [FE] 학습 세션 질문 UI 구현
```

담당자·저장소가 달라 독립 PR·차단 추적이 필요할 때만 `[Main]`/`[AI]`/`[Integration]`으로 더 쪼갠다(역할이 다르다는 이유만으로 자동 분할하지 않는다 — §4).

### 3단계: API 계약 승인

예정 외부 API:

```http
POST /api/sessions/{sessionId}/turns
```

```json
{
  "requestId": "question-request-001",
  "eventType": "USER_QUESTION",
  "payload": {
    "message": "편차가 무슨 뜻이야?"
  }
}
```

API 계약이 승인되기 전 관련 구현은 필요에 따라 `Blocked`로 표시한다. 승인 후 Contract 이슈를 닫고 구현 이슈를 `In Progress`로 전환한다.

### 4단계: 역할별 작업과 PR

Backend(Main+AI 통합 이슈):

```text
branch: feature/132-learning-question-flow
PR base: develop

Related to #132
Related to #130
```

Frontend:

```text
branch: feature/46-learning-chat
PR base: develop

Related to #46
Related to AutoAI-EduPilot/edupilot-be#130
Related to AutoAI-EduPilot/edupilot-be#131
```

### 5단계: 통합 테스트

```text
Given
- 로그인된 사용자가 PDF 3페이지의 활성 학습 세션에 있다.

When
- 사용자가 "편차가 무슨 뜻이야?"라고 질문한다.

Then
- FE가 Main Service를 호출한다.
- Main Service가 인증과 세션 소유권을 검증한다.
- Main Service가 AI Service에 현재 페이지 문맥을 전달한다.
- QaAgent가 페이지 근거로 답변한다.
- 질문과 답변이 DB에 저장된다.
- FE 채팅창에 답변이 표시된다.
```

실패 시나리오:

- 토큰 없음 → 401
- 다른 사용자의 세션 → 403 또는 정책에 따른 404
- 빈 질문 → 400
- AI timeout → 504
- AI 응답 스키마 오류 → 502
- 중복 요청 → 중복 메시지 저장 방지

### 6단계: 종료

1. 역할별 PR이 `develop`에 병합된다.
2. 각 하위 이슈의 완료 조건을 확인하고 수동으로 닫는다.
3. 통합 테스트 이슈를 완료한다.
4. API 계약과 문서 반영을 확인한다.
5. 모든 조건이 충족되면 부모 이슈를 닫는다.
6. dev/prod 배포는 별도 배포 이슈로 추적한다.

## 17. 이슈 없이 바로 PR 가능한 작업

- 단순 오타
- 의미가 바뀌지 않는 주석 수정
- README 한 줄 수정
- 명백한 작은 스타일 수정
- 동작과 계약에 영향을 주지 않는 단순 정리

작은 변경이라도 API, DB, 보안, 사용자 동작에 영향을 주면 이슈를 생성한다.

## 18. 저장소 생성 후 설정 체크리스트

- [ ] Backend 저장소와 Frontend 저장소의 실제 이름을 문서에 반영
- [ ] 조직 Project 생성 후 두 저장소 이슈 자동 추가 설정
- [ ] Status: Todo / In Progress / Blocked / Done
- [ ] Priority: High / Normal / Low
- [ ] 두 저장소에 공통 `area:*`, `type:*` 라벨 생성
- [ ] Epic, 하위 작업, API 계약, 버그 Issue Form 생성
- [ ] Pull Request template 생성
- [ ] `main`, `develop` branch protection/ruleset 설정
- [ ] Project 자동화: 이슈 종료 시 Done
- [ ] Backend 부모 이슈에 다른 저장소 Sub-issue 연결 권한 확인
- [ ] OpenAPI와 API 문서의 승인·변경 절차 합의

실제 등록용 8개 Epic은 [GitHub Epic 초안](issues/README.md)을 사용하고, 상세 흐름과 예외는 [상세 작업 분해 계획](issue-plan.md)을 참고한다.
