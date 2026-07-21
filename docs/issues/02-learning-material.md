# [Feature] PDF 학습 자료 업로드·조회·페이지 문맥 구축

> 상세 작업 분해 자료입니다. 실제 GitHub 부모 이슈는 [간결한 Epic 초안](epics/03-material-pdf.md)을 사용합니다.

| 계획 항목 | 값 |
| --- | --- |
| 문서 용도 | 구현 범위·예외·검증 참고 |
| Status | Todo |
| Priority | High |

권장 라벨:

```text
area: frontend
area: main-service
area: ai-service
area: integration
area: infra
type: feature
```

## 목표

인증된 사용자가 PDF 학습 자료를 업로드하고 목록·상세를 조회하며, 세션과 AI 기능이 사용할 페이지 수와 페이지별 학습 문맥을 안정적으로 제공받게 한다.

## 연결 요구사항

- `MATERIAL-001` PDF 업로드
- `MATERIAL-002` 목록·상세 조회
- `MATERIAL-003` 페이지 수와 페이지별 문맥
- `MATERIAL-004` 잘못된/제한 초과 파일 거부
- `MATERIAL-005` 저장·추출 실패 상태 관리

## 사용자 흐름

1. 사용자가 제목과 PDF 파일을 업로드한다.
2. Main Service가 권한·형식·크기 정책을 검증한다.
3. 원본 파일과 메타데이터를 저장한다.
4. 페이지 수와 페이지별 텍스트를 추출한다.
5. 처리 완료 후 자료를 `READY` 상태로 전환한다.
6. 사용자는 목록·상세에서 학습 가능한 자료를 확인한다.

## 범위

### 포함

- `LearningMaterial`, `MaterialPage` 영속 모델
- PDF 저장소 추상 경계와 인증된 접근
- 업로드·목록·상세·페이지 문맥 API
- 처리 상태: 최소 `PROCESSING`, `READY`, `FAILED`
- 페이지 수와 텍스트 추출
- 파일 제한과 오류 처리
- FE 업로드·목록·상세 화면 연동

### 제외

- PDF 편집
- OCR 고도화와 이미지 기반 완전 추출
- 강의/Course 계층
- 공개 파일 공유

## 작업 후보 — 필요할 때만 Sub-issue 생성

- PDF 저장소와 인증 다운로드 방식 확정
- 페이지 텍스트 추출 책임과 비동기 처리 방식 확정
- `[Contract]` Material 외부 API·처리 상태 계약
- `[Main]` LearningMaterial/MaterialPage schema와 migration
- `[Main]` PDF 업로드·검증·저장 구현
- `[Main]` 목록·상세·페이지 문맥 조회 구현
- `[AI/Worker]` 합의된 페이지 텍스트 추출 구현
- `[FE]` 자료 업로드·목록·상세·처리 상태 UI
- `[Integration]` 업로드부터 READY/FAILED까지 통합 테스트
- `[Security]` 잘못된 파일과 타인 자료 접근 테스트

## 외부 API 초안

```http
POST /api/materials
GET  /api/materials
GET  /api/materials/{materialId}
GET  /api/materials/{materialId}/pages/{pageNumber}
```

페이지 텍스트 API를 운영 FE에 노출할지는 보안·저작권·필요성을 검토한 후 승인한다.

## 선행 의존성

- [프로젝트 기반](00-foundation.md)
- [인증과 사용자](01-auth-user.md)
- `DEC-005` PDF 저장소
- `DEC-006` 텍스트 추출 책임
- `DEC-016` 업로드 제한

## 주요 예외

- PDF가 아니거나 손상된 파일
- 파일 크기/페이지 제한 초과
- 저장소 업로드 실패
- 텍스트 추출 실패
- 아직 처리 중인 자료로 세션 생성 시도
- 존재하지 않는 페이지
- 타인 자료에 대한 비인가 접근

## 완료 조건

- [ ] 저장소·텍스트 추출·업로드 제한 결정이 승인됐다.
- [ ] PDF 업로드와 처리 상태 계약이 승인됐다.
- [ ] 유효한 PDF가 저장되고 페이지 수가 기록된다.
- [ ] 페이지별 학습 문맥을 조회할 수 있다.
- [ ] 잘못된 파일과 제한 초과 파일이 거부된다.
- [ ] 처리 실패 원인이 안전한 오류 상태로 기록된다.
- [ ] 타인 자료 접근이 차단된다.
- [ ] FE에서 처리 중·완료·실패 상태를 표시할 수 있다.
- [ ] 업로드·조회·실패 통합 테스트가 통과한다.
