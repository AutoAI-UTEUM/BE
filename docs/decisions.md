# 결정 대기 목록

| 항목 | 내용 |
| --- | --- |
| 상태 | Open |
| 마지막 갱신 | 2026-08-03 |

확정된 선택은 날짜, 결정자, 이유를 기록하고 관련 문서를 함께 갱신합니다. 마감일은 팀 일정 확정 후 입력합니다.

`DEC-001` 같은 값은 이 문서 안에서 결정을 추적하기 위한 ID이며 GitHub 이슈 번호가 아닙니다. 기본적으로 관련 Epic의 `결정 필요` 체크박스로 관리합니다. 여러 팀의 합의가 필요하거나 실제 개발을 막는 항목만 별도 `[Decision]` 이슈로 만들고, 이 표에 GitHub 이슈 링크를 추가합니다.

**현재 별도 Open 상태로 등록된 DEC는 없습니다.** DEC-001~035의 결정 기록이 있으며, 리포트 후속 검토 항목은 DEC-033의 잔여 TBD 목록에서 추적합니다. 새 결정이 필요해지면 이 표 형식으로 다시 등재합니다.

| ID | 결정 항목 | 현재 후보/질문 | 영향 | 소유자 | 목표 시점 |
| --- | --- | --- | --- | --- | --- |

## 확정된 기본안

### DEC-002 — Python 버전 · Grok 모델 선정 (v2)

| 항목 | 내용 |
| --- | --- |
| 상태 | 확정 (AI 담당 결정, 3인 패널 검토 반영) |
| 소유 | 고영빈 (Agent Server) |
| 결정일 | 2026-07-23 (v2: 패널 검토 조건 반영 개정) |
| 관련 | DEC-006 (LLM provider = Grok/xAI), 에이전트 명세 §4.5, ai-integration-contract.md §5·§6, test-strategy.md |

#### 1. 결정 사항

##### D1. Python — 3.14.x

- pydantic v2 전용을 전제로 한다 (v1 호환 계층은 3.14 비호환 — 공식 확인됨. 새 코드에서 v1 API 사용 금지, 의존성의 v1 사용 여부를 lockfile 확정 시 확인).
- CI는 3.14.x 단일 버전으로 고정한다.
- Fallback 조건: 스켈레톤 단계에서 전체 의존성 설치 + 테스트 통과를 검증하고, 핵심 의존성 하나라도 3.14에서 막히면 3.13으로 하향한다. 검증 완료 시 이 조항은 소멸.

##### D2. 모델 — 전 에이전트 공통, grok-4.5 버전 고정 + 표류 감지

- MVP는 단일 모델을 전 에이전트가 공유한다. 에이전트별 모델 이원화는 하지 않는다.
- dated 버전(`grok-4.5-<date>`)으로 고정하고 `MODEL_NAME` env로 관리한다. 단, grok-4.5는 출시 초기라 dated 식별자 발행 여부가 미확인이므로:
  - dated 미발행 시 대체 경로: alias(`grok-4.5`) 고정 + 채점 golden 세트를 모델 표류 감지기로 운용.
- **버전 고정만으로는 안정성이 담보되지 않는다.** xAI는 dated 버전까지 은퇴시키고 은퇴된 슬러그를 에러 없이 다른 모델로 무언 리다이렉트한 전례가 있다(2026-05-15, 8개 모델 일괄 은퇴). 따라서:
  - 매 응답의 실제 `model` 필드를 고정값과 대조하고, 불일치 시 경보 로그를 남긴다(런타임 assertion).
  - xAI deprecation 공지 모니터링을 운영 루틴에 포함한다(소유: AI 담당, 주 1회).

##### D3. 용도별 차등 — `reasoning_effort`로 (temperature는 보조)

| 용도 | reasoning_effort | 근거 |
| --- | --- | --- |
| 오케스트레이터 Plan | **low~medium** | 짧은 structured output. high로 두면 자유 발화 턴의 첫 토큰이 플래너 완료(high TTFT 중앙값 ~17초) 뒤로 밀려 채팅 UX 실격 |
| 설명 · QA · 퀴즈 생성 (스트리밍) | low~medium | 반응성 우선 |
| 채점 · 평가 · 진단 (비대화형) | high | 판단 깊이 우선, 지연 허용 |

- 지연 예산: 자유 발화 턴의 첫 answer_delta까지 p50 5초 이내를 목표로 하고, 구현 첫 주에 effort별 TTFT를 실측해 표를 확정한다.
- 사실관계: grok-4.5는 reasoning 비활성화 불가(단, 이는 grok-4.5에 한정된 사실 — grok-4.3은 `reasoning_effort: none` 지원). `presencePenalty`/`frequencyPenalty`/`stop`은 reasoning 모델에서 요청 시 에러.
- temperature: 공식 문서의 비호환 파라미터 목록에 없음(지원 가능성 높음). 구현 첫 주 스모크 테스트(동일 채점 입력 N=10회, 점수 분산 비교로 반영 여부 판정)로 확인 후, 지원 시 채점 계열에 최저값을 추가 적용한다. 미지원이어도 D4가 결정성을 담보하므로 계획 영향 없음.

##### D4. 채점 결정성(§4.5)의 담보 방식 — 구조로

- §4.5의 "결정성"은 "동일 입력 → 비트 단위 동일 출력"(LLM 불가)이 아니라 **"루브릭 기준 일관 채점 + 검증 통과"**로 해석한다. 이 재해석은 명세 소유자(팀) 합의로 명세에 역반영한다. `[액션: 팀 회의 안건]`
- 담보 장치:
  1. MCQ/OX: Backend 결정론 채점 (LLM 미개입)
  2. SHORT/ESSAY: structured outputs(json_schema)로 채점 JSON 스키마 강제
  3. ESSAY는 출제 단계에서 전용 스키마(modelAnswer + rubric 항목·가중치, weight 합계 검증) 강제 → 채점은 루브릭 항목별 점수 산출, 총점은 코드에서 합산
  4. 검증 계층: questionId 매칭, 점수 범위 검증(범위 초과는 clamp가 아니라 재시도 → 실패 처리), verdict-점수 일관성(CORRECT ≥ 0.8·maxScore, PARTIAL 0.2~0.8, WRONG ≤ 0.2 — 구현 시 확정), 문항 수 일치. 위반 시 재시도 후 실패, 부분 결과 반환 금지.
- 구조가 담보하는 것과 못 하는 것의 구분: 위 장치는 형식적 유효성과 집계 재현성을 담보하고 채점 분산을 축소한다. "일관 채점" 자체는 측정으로 확인한다 — **golden 답안 세트(정답/부분정답/오답 각 5개) 반복 채점(N=10)에서 문항 점수 표준편차 ≤ 0.1**을 수용 기준으로 하고, live 스모크에 포함한다.

##### D5. 에이전트별 LLM 프로필 config 선반영

- `AgentLlmProfile { model, reasoningEffort, maxTokens, temperature? }`를 settings로 관리. 지금은 전 에이전트가 같은 모델을 가리키되, 이후 이원화는 코드 변경 없이 config 변경만으로 가능하게 한다.
- maxTokens 기본값: reasoning 토큰이 max_tokens를 잠식해 출력이 절단되면 스키마 파싱 실패로 직결되므로, 채점·Plan 계열은 여유값(예: 16K)으로 설정하고 첫 주 실측으로 조정한다.
- 계약의 `config.model`(Backend 오버라이드)과의 우선순위: **AgentLlmProfile이 항상 우선**, `config.model`은 allowlist에 있는 값만 허용(그 외 거부) — 버전 고정이 요청으로 우회되는 것을 차단.

#### 2. 비용 전제와 이원화 임계값

- 추정(보수적 가정: 세션 30턴 중 LLM 경유 22턴, 호출당 입력 ~6K·출력 ~4K 토큰, 턴당 2회 호출): 세션당 약 $1.2, 월 300세션 시 약 $360.
- 재검토 트리거: **월 LLM 비용 $150 초과 시**(팀 확정 필요 — 제안값) 이원화 검토 개시. 이원화 대상은 grok-4.3($1.25/$2.50, 캐시 입력 $0.20), 설명·QA를 내릴 경우 약 55% 절감 추정.
- 판단 데이터: `done.data.usage` + `/grade` 응답 usage(추가 필요) + reasoningTokens 필드 수집. `[액션: 계약 usage 스키마 보강]`

#### 3. 검토했으나 채택하지 않은 대안

| 대안 | 기각 사유 |
| --- | --- |
| alias만 사용 (표류 감지 없이) | 모델 자동 업데이트 시 채점 기준 표류를 감지 수단 없이 수용하게 됨 |
| 채점 = temperature 최저 (원안) | reasoning 모델에서 반영 여부 미검증. D3·D4로 대체, 스모크 확인 후 보조 적용 |
| 역방향 이원화 (저가 모델 기본 + 채점만 4.5) | 후보였던 grok-4.1-fast 등 fast 계열이 2026-05 일괄 은퇴로 부재. 현실 대안은 grok-4.3 하나이며, MVP 테스트 매트릭스 단순화를 위해 단일 모델 유지, §2 임계값으로 전환 시점 관리 |
| Python 3.13 | 3.14 안정·호환 확인됨. 단 D1 fallback 유지 |

#### 4. 후속 조치 (구현 첫 주)

- [ ] 3.14 의존성 전체 검증 (통과 시 D1 fallback 조항 소멸)
- [ ] grok-4.5 dated 버전 발행 여부 확인 → 발행 시 env 반영 / 미발행 시 alias+감지기 경로 확정
- [ ] 응답 `model` 필드 대조 assertion 구현
- [ ] effort별 TTFT 실측 → D3 표 확정 (자유 턴 p50 5초 예산 검증)
- [ ] temperature 스모크 테스트 (N=10 분산 비교) → 결과 추기
- [ ] AgentLlmProfile + maxTokens 기본값 스켈레톤 반영
- [ ] usage 수집 시작 (§2 판단 데이터)

#### 5. DEC-002 범위 밖 — 연계 결정 필요 (계약 문서로 이관)

- `/internal/ai/grade` 타임아웃(비스트리밍, heartbeat 불가) 및 재시도 횟수/예산
- 재시도와 turnId 멱등성 충돌 해소 (실패 턴은 같은 turnId 재호출 허용 = replay, 성공 턴만 DUPLICATE_TURN)
- 계약 §7 `MODEL_NAME` 기본값·§3.4 usage 예시를 고정 버전 체계로 정합화, usage에 reasoningTokens·grade usage 추가
- §4.5 재해석의 명세 역반영 (팀 합의)

#### 6. 재검토 조건

- xAI의 grok-4.5 가격/정책 변경 또는 deprecation 공지 (감지: AI 담당 주 1회 모니터링)
- 월 LLM 비용 $150 초과 (→ grok-4.3 이원화 검토)
- 응답 model 필드 불일치 경보 발생 (무언 리다이렉트 감지 시 즉시 재검토)
- TTFT 실측이 p50 5초 예산을 초과 (→ D3 effort 배치 재조정)

### DEC-007 — PK/외부 ID 전략

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: 전 테이블 기본 키는 **BIGINT AUTO_INCREMENT**를 사용하고, 외부 API 식별자도 동일 값을 노출한다.
- 이유: 단순하고 JPA·인덱스 효율이 좋다. ID 추측(enumeration) 리스크는 소유권 검증 + 404 은닉 정책이 방어한다.
- 대안과 trade-off: UUID(v7)는 노출 안전성이 장점이나 인덱스 비대·가독성 저하. MVP 규모에서 이점이 작다.
- **이후 개선안**: 외부 공개 API·공유 링크가 생기면 노출용 public ID(UUID/난수 slug) 컬럼을 추가하고 내부 BIGINT와 매핑하는 방식으로 확장한다. 기존 스키마 변경 없이 컬럼 추가만으로 가능하다.
- 후속 변경 문서: database.md §2 컬럼 원칙, requirements §4 비기능

### DEC-009 — 퀴즈 재제출 정책

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — FE UX는 계약 리뷰에서 공유
- 선택: **MVP는 한 퀴즈당 1회 제출 제한**. 재제출 요청은 `QUIZ_ALREADY_SUBMITTED`(409)로 거부한다. 스키마의 `attempt_no`는 유지하되 1로 고정해 이후 확장 시 migration 없이 전환 가능하게 한다.
- 이유: 정답 유출 경로(1차 제출의 verdict/feedback으로 정답 역산 후 재제출 만점)를 원천 차단하고 채점·평가 데이터의 단순성을 유지한다.
- 대안과 trade-off: attempt 허용은 학습 반복에 유리하나 정답 보호 규칙 설계가 선행돼야 한다. 실수 제출은 FE 제출 전 확인 모달로 완화한다.
- **이후 개선안**: 재제출을 허용하는 확장 시 반드시 함께 정의할 것 — ① 재제출 시 verdict/feedback 공개 시점(예: 최종 제출 후에만 정답 공개) ② 점수 처리(최고점 vs 최신) ③ attempt 상한. 이 규칙 없이 attempt만 여는 것을 금지한다.
- 후속 변경 문서: feature-spec §8, requirements QUIZ-007, database.md quiz_submissions 주석

### DEC-010 — 퀴즈 통과 기준

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — 값은 Product·AI와 운영 중 조정
- 선택: **고정 비율 60%** — `passed = (score / maxScore) >= 0.6`. 값은 설정(`EDUPILOT_QUIZ_PASS_RATIO`, 기본 0.6)으로 관리한다. 이 기준 미달이 저득점 진단 파이프라인(`/internal/ai/diagnosis`)의 트리거다.
- 이유: MVP에서 유형·난이도별 차등은 근거 데이터가 없어 과설계다. 설정으로 빼두면 코드 변경 없이 조정 가능하다.
- 대안과 trade-off: 유형별 차등(예: OX는 높게)은 정밀하나 초기 근거 부족.
- **이후 개선안**: 운영 데이터(유형별 평균 점수·진단 진입률)가 쌓이면 유형/난이도별 차등 기준으로 확장한다. 확장 시 quiz_type별 설정 맵으로 전환한다.
- 후속 변경 문서: api-spec §6 제출 응답, feature-spec §9 통과 기준, README §6 환경 변수

### DEC-008 — 페이지 진행 모델

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: MVP는 세션 단일 `pageStatus`를 유지한다. 페이지 이동 시 새 페이지 상태는 `NOT_EXPLAINED`로 초기화하고 과거 설명 원문은 채팅 이력으로 복원한다. 진도율 근거에 한해 성공한 설명 완료 페이지를 `SessionPageRecord`로 누적하며, 페이지별 전체 상태 모델(`SessionPageProgress`)은 도입하지 않는다.
- 이유: 런타임 상태 전이·복원 로직은 단순하게 유지하면서도 현재 페이지 근사 없이 설명 완료된 고유 페이지 수를 결정적으로 집계할 수 있다.
- 대안과 trade-off: 페이지별 전체 상태 모델은 방문·학습 상태를 풍부하게 보존하지만 테이블과 전이 복잡도가 증가한다. 완료 근거만 `(session_id, page_number)` 1행으로 유지하고 재설명 시각을 갱신해 필요한 진도 데이터만 저장한다.
- 후속 변경 문서: domain-model §4 pageStatus, feature-spec §4, api-spec §5

### DEC-024 — 활성 세션 재사용

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — FE UX는 계약 리뷰에서 재확인
- 선택: 같은 자료로 `POST /api/sessions` 호출 시 기존 `ACTIVE` 세션이 있으면 새로 만들지 않고 그 세션을 반환한다(재사용). 응답에 `reused` 필드로 구분을 제공한다. "처음부터 다시"는 기존 세션 삭제(`DELETE`) 후 생성으로 해결한다.
- 이유: 자료당 학습 맥락(대화·평가 큐·pageStatus)이 하나로 유지되고 목록에 중복 ACTIVE가 쌓이지 않는다. 목록(SESSION-008)·삭제(SESSION-009) API가 있어 이어하기/새로 시작 UX가 모두 성립한다.
- 대안과 trade-off: 항상 신규 생성은 구현이 단순하나 맥락 분산·목록 혼란을 만든다. 409 거부는 FE 왕복이 늘어난다.
- 부가 확정: 세션 `COMPLETED → ACTIVE` 재개는 MVP에서 불가(완료 세션은 열람만, 재학습은 새 세션). 메시지 조회 페이지네이션은 커서 방식으로 확정.
- 후속 변경 문서: api-spec §5 세션 생성·complete·messages, feature-spec §4, domain-model §4

### DEC-005 — PDF 저장소

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: MVP는 로컬 볼륨(Docker volume) 저장으로 시작하되, 코드가 물리 경로를 알지 못하도록 저장소 어댑터 인터페이스 뒤에 격리하고 DB에는 `storage_key`만 저장한다. FE의 PDF 접근은 Spring의 인증된 다운로드 스트리밍으로 제공한다.
- 이유: 단일 호스트 MVP에서 구현·비용 최소. 어댑터 격리로 이후 전환 비용을 낮춘다.
- 대안과 trade-off: S3는 내구성·presigned URL이 강점이나 AWS 구성(DEC-019) 선행이 필요해 초기 채택을 보류.
- **S3 전환 계획**: AWS 전개(DEC-019 확정) 시 어댑터 구현체를 S3로 교체한다. 이때 FE 다운로드는 Spring이 권한 확인 후 발급하는 **presigned URL**(유효기간 있는 서명 링크, 예: 10분)로 변경해 파일 바이트가 Spring을 거치지 않게 한다. `storage_key` 체계는 전환 시에도 유지한다.
- 후속 변경 문서: api-spec §4 자료 상세, database.md §2, backend-plan §11

### DEC-006 — PDF 텍스트 추출 책임

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) + AI 담당 합의
- 선택: **FastAPI가 추출을 실행하고 Spring이 저장·상태 전이를 소유**한다. 흐름: Spring이 업로드 저장(PROCESSING) → 백그라운드에서 내부 API `POST /internal/ai/extract`로 PDF를 멀티파트 전송 → FastAPI가 페이지별 텍스트를 추출해 배열로 반환 → Spring이 `material_pages` 저장 후 READY/FAILED 전이. LLM provider는 **Grok API(xAI)** 를 사용하며, 에이전트 문맥의 기본 근거는 이 추출 텍스트다. xAI Files 원본 업로드·첨부 전환은 DEC-035가 단계적으로 확장한다.
- 이유: Python 추출 생태계를 활용하면서 "FastAPI는 영속 데이터를 직접 만들지 않는다"는 아키텍처 원칙을 유지한다. 추출은 LLM 판단이 없는 결정적 전처리라 하이브리드 원칙(DEC-022)과 충돌하지 않는다. Grok 파일 첨부(attachment_search)는 페이지 단위 문맥 제어가 약해 자체 추출이 설계와 정합.
- 대안과 trade-off: Spring 내 추출(PDFBox)은 경계가 단순하나 팀 결정(Python 측 추출)과 상이. FastAPI 직접 DB 저장은 원칙 위반으로 배제.
- 후속 변경 문서: api-spec §8 내부 API 표, feature-spec §3, Epic3 이슈 구조([AI] 추출 이슈 필수)

### DEC-016 — 업로드 제한

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: 최대 파일 크기 **45MB**, 최대 **300페이지**, Content-Type 확인 + 매직 바이트(`%PDF-`) 검사 + 손상 파일 거부(`INVALID_PDF_FILE`). 크기 초과는 `FILE_TOO_LARGE`(413)이며 Spring multipart 설정과 일치시킨다. 제한값은 환경 변수(`EDUPILOT_UPLOAD_MAX_MB` 등)로 관리한다.
- 이유: 45MB는 Grok 파일 첨부 상한(48MB)보다 작아 원본 첨부 경로를 열어도 안전하고, 서버 메모리·추출 시간을 보호한다.
- 대안과 trade-off: 더 큰 상한은 대용량 강의 자료를 수용하지만 추출·전송 비용이 커진다. 값은 운영 데이터를 보고 조정한다.
- 후속 변경 문서: api-spec §4 업로드, README §6 환경 변수

### DEC-025 — 페이지 텍스트 API 노출

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: `GET /api/materials/{materialId}/pages/{pageNumber}`는 **운영 FE에 노출하지 않는다**. 개발/디버깅 프로파일에서만 활성화하고, 추출 텍스트는 AI 문맥 전용으로 사용한다.
- 이유: 추출 텍스트 무단 유출은 저작권 리스크가 크고, FE가 이 API를 사용하는 화면이 없다(FE는 PDF 원본 뷰어 사용).
- 대안과 trade-off: 운영 노출은 디버깅 편의가 있으나 유출 표면만 넓힌다. 필요 시 관리자 전용으로 재검토.
- 후속 변경 문서: api-spec §2 표·§4, screen-api-map §1, feature-spec §3

### DEC-001 — Spring Boot 버전

- 상태: Accepted (조건부)
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: Spring Boot 4.1.x 최신 패치 + Java 21.
- 이유: 2026-07 기준 최신 안정 버전이며 OSS 지원 기간(2027-07-31)이 가장 길다. 4.0은 2026-12 OSS 지원 종료, 3.5는 이미 종료.
- 대안과 trade-off: 4.0.x는 검증 기간이 길지만 지원 종료가 임박. 초기 세팅에서 핵심 의존성(springdoc-openapi, JJWT 등) 호환 문제가 발생하면 4.0.x 최신 패치로 하향한다.
- 후속 변경 문서: README §4 기술 스택, backend-plan §1

### DEC-003 — Migration 도구

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: Flyway (Community).
- 이유: SQL 파일 기반이라 database.md의 DDL 초안 이전이 쉽고 Spring Boot 통합·학습 곡선이 최소. rollback은 forward-fix 원칙이라 Liquibase의 선언적 rollback 이점이 작다.
- 대안과 trade-off: Liquibase는 DB 독립성이 강점이나 MySQL 고정 프로젝트에서 관리 비용만 추가. Flyway Community의 지원 MySQL 버전 범위는 채택 시 확인한다.
- 후속 변경 문서: README §4, database.md 헤더, backend-plan §4

### DEC-004 — JWT 정책

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — FE 연동 세부는 구현 전 FE와 재확인
- 선택: access token 만료 1시간(FE 메모리 보관, localStorage 금지) + refresh token 만료 14일(HttpOnly·Secure·SameSite=Lax 쿠키, 회전 + 재사용 감지 시 전체 폐기). 서버는 refresh 해시를 DB에 저장해 로그아웃·강제 폐기를 지원한다.
- 이유: XSS로부터 refresh를 보호하고 탈취 피해를 access 수명(1시간)으로 제한. 쿠키 채택은 DEC-027 CORS credentials 정책과 한 묶음으로 정합.
- 대안과 trade-off: refresh 미도입은 만료 UX가 나쁘고, body 반환·FE 저장은 XSS 노출면이 커진다.
- 후속 변경 문서: api-spec §3 로그인 응답, requirements AUTH-005, error-code 갱신 흐름

### DEC-014 — 내부 API 인증

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend)
- 선택: 네트워크 격리 + 정적 service token 2중 방어. FastAPI는 Docker 내부 네트워크에만 바인딩하고, Spring은 모든 내부 호출에 `X-Internal-Token`(환경 변수 `EDUPILOT_INTERNAL_TOKEN` 주입) 헤더를 첨부하며 FastAPI가 검증한다.
- 이유: 단일 호스트 Docker Compose 규모에서 충분한 방어이며 구현 부담이 작다.
- 대안과 trade-off: mTLS는 인증서 운영 부담이 MVP에 과함 — 다중 호스트 전개 시 재검토. 무인증은 설정 실수 한 번에 내부 API 위조가 가능해 배제.
- 후속 변경 문서: api-spec §8 내부 API 필수 정책, README §6 환경 변수

### DEC-021 — SSE 인증 방식

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — FE 구현 방식은 구현 전 FE와 재확인
- 선택: fetch 기반 스트림. FE는 EventSource 대신 `Accept: text/event-stream`으로 fetch를 호출해 ReadableStream을 파싱하고, 기존 `Authorization: Bearer` 헤더를 그대로 사용한다. 재연결·`Last-Event-ID`는 FE가 처리한다(fetch-event-source 패턴).
- 이유: 기존 Bearer 인증 체계를 재사용해 추가 서버 작업(쿼리 토큰 발급 등)이 불필요하고, access 토큰 메모리 보관 정책(DEC-004)과 정합.
- 대안과 trade-off: 단기 서명 쿼리 토큰은 EventSource 자동 재연결을 살리지만 발급 API·URL 노출 마스킹이 추가된다. 쿠키 인증은 권한 모델이 꼬인다.
- 후속 변경 문서: api-spec §9 SSE 계약, screen-api-map §5

### DEC-027 — CORS 정책

- 상태: Accepted
- 결정일: 2026-07-21
- 결정자: 한승준 (Backend) — 운영 오리진은 배포 도메인 확정 시 추가
- 선택: Spring 전역 `CorsConfigurationSource` 단일 설정. 허용 오리진은 환경 변수 주입(local `http://localhost:5173`, 와일드카드 금지), 메서드 GET/POST/PATCH/DELETE/OPTIONS, 헤더 `Authorization`·`Content-Type`, `allowCredentials=true`(DEC-004 refresh 쿠키 채택), preflight 캐시 3600초.
- 이유: 컨트롤러별 `@CrossOrigin` 산재를 막고 환경별 오리진을 설정으로 관리. credentials 사용 시 명시 오리진이 필수라 와일드카드를 금지한다.
- 대안과 trade-off: 오리진 와일드카드는 설정이 쉽지만 credentials와 병용 불가·보안상 부적합.
- 후속 변경 문서: backend-plan §2, README §6 환경 변수

### DEC-013 — AI 응답 스트리밍 전송 방식

- 상태: Accepted — 세부 계약 포함 전체 확정 (2026-07-23, AI 담당 주도 합의)
- 결정일: 2026-07-10
- 결정자: 프로젝트 담당자
- 선택: Frontend와 Spring 사이의 AI 응답 스트리밍은 SSE를 기본 방식으로 사용한다.
- 이유: 설명·QA처럼 서버에서 클라이언트로 전달되는 단방향 이벤트 스트림에 적합하고 HTTP 기반 인증·중계 구조를 유지할 수 있다.
- 세부 계약 확정([API 명세](api-spec.md) §9): 이벤트 6종(`status`/`thought_summary`/`content_delta`/`ui_action`/`completed`/`error` — completed·error는 정확히 1회·마지막), heartbeat 10초 comment, 취소는 별도 API 없이 fetch abort → 상류 취소, `Last-Event-ID` 재연결 미지원(재조회로 재동기화), 최종 저장은 completed 검증 후 1회. 인증은 DEC-021(fetch + Bearer).
- 대안과 trade-off: WebSocket은 양방향 실시간 통신이 필수로 바뀌는 경우 별도 결정 후 검토한다.
- 후속 변경 문서: [API 명세](api-spec.md) §9에 스트림 URL 초안(`GET /api/sessions/{sessionId}/stream`) 반영

### DEC-022 — AI 호출 주체 원칙 (하이브리드)

- 상태: Accepted
- 결정일: 2026-07-20
- 결정자: 프로젝트 담당자
- 선택: 자유 학습 턴(질문, 설명, 퀴즈 유형 선택, 진단 답변, 교정 후 질문)은 `/internal/ai/turn` 단일 진입점으로 전달하고 에이전트 선택은 FastAPI Orchestrator가 담당한다. 퀴즈 제출 후의 결정적 파이프라인([SHORT/ESSAY만] 채점 → 내부 평가 → 저득점 시 진단)만 Spring이 전용 내부 API(`grade`, `quiz-assessment`, `diagnosis`)를 이벤트 타입·점수 기준 규칙으로 순차 호출한다. 오개념 교정과 메모리 후보·승격 전용 엔드포인트는 두지 않고 turn 도구로 흡수한다.
- 이유: 에이전트 명세서의 시나리오(9.5, 9.6, 9.8)가 후처리를 이미 결정적 순서로 기술하고 있고, 채점·평가의 재현성 요구에는 LLM 계획 단계가 불필요하다. 메모리 승격·교정 선택처럼 LLM 판단이 본질인 기능은 turn에 남겨 Orchestrator 거버넌스를 보존한다.
- 대안과 trade-off: turn 단일 통일은 Plan·Policy 체계를 온전히 유지하지만 제출마다 LLM 계획 비용·지연이 발생하고 채점 재현성과 긴장한다. 전용 엔드포인트 중심은 지연이 최소지만 Orchestrator·메모리 거버넌스가 형해화된다.
- 후속 변경 문서: README 핵심 원칙, [시스템 아키텍처](architecture.md) §2, [API 명세](api-spec.md) §8, [에이전트 시스템 명세](agent-system-spec.md) §6·§7

### DEC-023 — 학습자 메모리 조회 스코프

- 상태: Accepted
- 결정일: 2026-07-20
- 결정자: 프로젝트 담당자
- 선택: `GET /api/users/me/memory`는 `materialId` 쿼리 파라미터를 필수로 받아 자료 스코프의 메모리 요약을 반환한다.
- 이유: 저장 모델이 `learner_memories` `UK(user_id, material_id)`로 자료별이므로 조회 API도 같은 스코프를 가져야 한다.
- 대안과 trade-off: 전 자료 목록 반환은 화면 요구가 확정되지 않았고 응답 비대화 우려가 있어 보류. 자료 범위를 넘어선 전역 프로필은 별도 검토(DEC-012 연계).
- 후속 변경 문서: [API 명세](api-spec.md) §7, [화면-API 매핑](screen-api-map.md), README API 초안

### DEC-026 — 자료 접근 모델 (소유자 전용)

- 상태: Partially Superseded — 개인 자료의 소유자 전용 원칙은 유지하고, 승인된 강의실 멤버의 공개 자료 학습 접근은 DEC-030으로 확장
- 결정일: 2026-07-20
- 결정자: 프로젝트 담당자
- 선택: MVP에서 학습 자료는 업로드한 본인만 조회·학습할 수 있다. "접근 가능한 자료"는 "본인이 업로드한 자료"를 의미한다.
- 이유: 타인 PDF 노출에 따른 저작권·보안 리스크를 차단하고, issues/02의 "타인 자료 접근 차단" 테스트 기준과 정합하다. 인가 검증과 목록 쿼리 구현이 단순해진다.
- 대안과 trade-off: 전체 공유 모델은 콘텐츠 풀이 풍부해지지만 저작권 검토·공유 권한 모델이 선행돼야 하므로 MVP 이후 검토(DEC-017 관리자 범위와 연계).
- 후속 변경 문서: [요구사항 명세](requirements.md) §1·MATERIAL-002, [API 명세](api-spec.md) §2 권한 열

### DEC-028 — 회원 탈퇴·자료 삭제 (MVP 포함)

- 상태: Accepted
- 결정일: 2026-07-23
- 결정자: 프로젝트 담당자
- 선택: 회원 탈퇴와 자료 삭제를 MVP 기능으로 포함한다.
  - **자료 삭제 `DELETE /api/materials/{materialId}`**: 논리 삭제(`status=DELETED`) — 목록·조회·세션 생성에서 제외. 소유자 전용(DEC-026, 404 은닉 동일 적용). 해당 자료의 ACTIVE 세션이 있으면 `MATERIAL_HAS_ACTIVE_SESSION`(409)으로 거부한다(세션 완료/삭제 후 재시도). 완료된 세션·퀴즈·평가 기록은 보존한다. storage 파일은 즉시 삭제하지 않는다.
  - **회원 탈퇴 `DELETE /api/users/me`**: 요청 본문의 비밀번호 재확인 필수. `status=DELETED` 전환과 동시에 개인 식별 정보를 즉시 익명화한다 — email → `deleted_{id}` 형식, name → 고정 문구, password_hash 무효화. 익명화로 이메일 UK 충돌 없이 재가입을 허용한다. refresh token은 전부 폐기하고(DEC-004) access token은 만료를 대기한다. 소유 자료·세션은 함께 논리 삭제하며, 퀴즈 제출·평가·메모리 레코드는 익명 상태로 보존한다. 복구는 MVP 미지원 — FE 확인 모달로 실수를 완화한다(DEC-009 패턴).
- 이유: 사용자가 자신의 데이터를 정리할 수 있어야 한다는 팀 결정. 논리 삭제·기록 보존 원칙이 세션 삭제(SESSION-009)·평가 전량 보존(DEC-011)과 같은 패턴이라 저장 모델의 일관성이 유지되고, 즉시 익명화가 개인정보 최소 보유와 재가입 허용을 동시에 만족한다.
- 대안과 trade-off: 물리 삭제는 개인정보 관점에서 명확하나 FK 연쇄·감사 이력 소실 문제가 있어 배치 단계로 미룬다. 재가입 차단(이메일 UK 유지)은 단순하나 사용자 권리를 과도하게 제한한다.
- **이후 개선안**: ① 탈퇴 유예 기간(예: 7일 내 복구) ② 보존 레코드·storage 파일의 기간 만료 후 물리 삭제 배치 ③ 서비스 공개 운영 시 개인정보 처리방침 문서화. 이 규칙들은 운영 전환 전에 확정한다.
- 후속 변경 문서: [요구사항 명세](requirements.md) AUTH-006·MATERIAL-006, [API 명세](api-spec.md) §2·§3·§4, [에러 코드](error-code.md), [도메인 모델](domain-model.md) User 상태

### DEC-015 — API versioning (무버전 `/api` 유지)

- 상태: Accepted
- 결정일: 2026-07-23
- 결정자: 프로젝트 담당자
- 선택: 외부 API base path는 **`/api` 무버전을 유지**한다. 변경 정책 — breaking change는 FE와 합의 후 FE·BE 동시 배포로 반영하고, OpenAPI 문서를 계약의 단일 기준으로 삼는다.
- 이유: 클라이언트가 자사 FE 하나뿐이라 URL 버전의 실익이 없고, 전 문서·이슈 양식이 이미 `/api` 기준으로 작성돼 있다.
- 대안과 trade-off: `/api/v1` 선도입은 외부 공개에 유리하나 MVP에서는 관리 비용만 늘어난다.
- **이후 개선안**: 외부 공개 API·서드파티 클라이언트가 생기는 시점에 `/api/v1`을 도입한다. DEC-007 개선안(public ID)과 같은 트리거로 묶어 함께 진행한다.
- 후속 변경 문서: [API 명세](api-spec.md) §1 base path 주석

### DEC-017 — 관리자 범위 (MVP 미구현, role 예약)

- 상태: Accepted
- 결정일: 2026-07-23
- 결정자: 프로젝트 담당자
- 선택: **MVP에서 관리자 기능(API·화면)을 구현하지 않는다.** `role=ADMIN` enum과 인가 체계만 예약으로 유지하고, 운영상 필요한 조치(문제 자료 차단 등)는 DB에서 수동 처리한다.
- 이유: 기획안이 관리자를 보조 사용자(Could)로 규정하며, MVP 핵심 가치(학습 흐름)와 무관한 구현을 줄인다.
- 대안과 trade-off: 최소 관리자 API 선구현은 운영 편의가 있으나 인가·감사 설계가 선행돼야 해 MVP 범위를 넘는다.
- **이후 개선안**: 최소 2기능(사용자 목록·상태 변경, 자료 강제 비활성)부터 시작한다. 관리자 API에도 DEC-025(페이지 텍스트 비노출)·정답/루브릭 보호 원칙을 동일 적용하고, 관리자 행위 감사 로그를 함께 설계한다.
- 후속 변경 문서: [요구사항 명세](requirements.md) §1, [프로젝트 목표](project-goals.md)

### DEC-018 — TEACHER·LMS 도메인 (제외 확정)

- 상태: Partially Superseded — 계정 역할 제외 부분은 DEC-029, 강의실 최소셋 제외 부분은 DEC-030으로 대체. Course·Lecture·Assignment·Notification 도메인 제외 결정은 유지
- 결정일: 2026-07-23
- 결정자: 프로젝트 담당자
- 선택: **Course/Lecture/Assignment/Notification 도메인을 MVP·차기 범위에서 제외한다.** 스키마 예약(빈 테이블·미사용 컬럼)도 두지 않는다. 기존 `TEACHER` 역할 제외 결정은 DEC-029의 `INSTRUCTOR` 공개 계정 역할 도입으로 대체한다.
- 이유: 기획안 제외 목록의 방향을 그대로 종결하는 것이다. BIGINT PK·enum 확장이 쉬워 선제 예약의 이점이 없고 과설계만 남는다.
- 대안과 trade-off: 도메인 선예약은 확장 시 migration을 줄이지만, 요구가 확정되지 않은 상태의 스키마는 재작업 가능성이 더 크다.
- **이후 개선안**: Course/Lecture/Assignment/Notification 도메인 제외 결정은 #102에서 별도 범위를 승인하기 전까지 유지합니다.
- 후속 변경 문서: [요구사항 명세](requirements.md) §1, [프로젝트 목표](project-goals.md), [도메인 모델](domain-model.md)

### DEC-029 — 공개 계정 역할 LEARNER·INSTRUCTOR

- 상태: Accepted — [GitHub #100](https://github.com/AutoAI-EduPilot/BE/issues/100) 결정 기록
- 결정일: 2026-07-31
- 결정자: 팀 — 승준 역할 정책 승인, 이감재의 "가입 시 역할 선택 UI 확정" 확인
- 선택:
  - 공개 계정 역할은 `LEARNER`, `INSTRUCTOR`로 구분하고 회원가입 시 사용자가 직접 선택합니다.
  - `ADMIN`은 내부 관리용 예약 역할로 유지하며 공개 회원가입에서는 거부합니다.
  - 기존 `USER` 데이터는 migration으로 `LEARNER`로 전환합니다.
  - 이번 결정의 구현 범위는 역할 저장, JWT claim, signup·login·내 정보 API 계약까지입니다.
  - 역할 저장·JWT 계약은 공통으로 유지하되, 강의실 최소셋의 차등 권한은 DEC-030을 적용합니다.
- 이유: FE가 가입 시 역할 선택을 선반영했고 팀이 강사 계정 역할 도입 방향을 선택했습니다. 역할 계약을 먼저 명시하되 미확정 LMS 도메인을 함께 선행 구현하지 않도록 경계를 분리합니다.
- 대안과 trade-off: 역할을 구분하지 않고 단일 `USER`로 유지하면 현재 구현은 단순하지만 FE 계약과 향후 강사 기능의 주체가 불명확합니다. 가입 후 관리자 승인 방식은 권한 상승 통제가 강하지만 승인 운영 요구가 없어 MVP에서는 자기 선택 방식을 사용합니다.
- 호환성: 배포 전에 발급된 `role=USER` access token은 `TOKEN_INVALID`가 되며 refresh 또는 재로그인으로 `LEARNER` 토큰을 다시 발급받아야 합니다. 기존 DB 사용자는 V8 migration에서 `LEARNER`로 변환합니다.
- 후속 변경 문서: [요구사항 명세](requirements.md), [프로젝트 목표](project-goals.md), [도메인 모델](domain-model.md), [API 명세](api-spec.md), [데이터베이스](database.md), [화면-API 매핑](screen-api-map.md)

### DEC-030 — 강의실 최소셋·자료 접근·진도 계약

- 상태: Accepted — [GitHub #126](https://github.com/AutoAI-EduPilot/BE/issues/126) 계약, FE 검토 완료
- 결정일: 2026-08-02
- 결정자: 프로젝트 담당자, Frontend 담당자
- 선택:
  - 강의실 개설·초대 참여·주차 자료·즉시 공지·캘린더 파생 조회를 MVP에 포함합니다. DEC-018의 강의실 제외 부분만 대체하며 Course·Lecture·Assignment·Notification, 통계·리마인더·예약 게시·CUSTOM 일정은 계속 제외합니다.
  - 강의실은 `INSTRUCTOR`가 소유하고 관리합니다. `LEARNER`와 본인이 소유하지 않은 강의실에 참여한 `INSTRUCTOR`는 승인 멤버로서 공개된 주차와 연결 자료를 조회하고 그 자료로 본인 통합학습 세션을 생성·진행할 수 있습니다. 소유권을 숨겨야 하는 강의실 접근은 `CLASSROOM_NOT_FOUND`, 역할 부족은 `ACCESS_DENIED`를 사용합니다.
  - `LEARNER`와 `INSTRUCTOR`의 기존 개인 PDF 업로드를 유지하고 예약 역할 `ADMIN`의 기존 개인 업로드 계약도 변경하지 않습니다. 강의실 업로드·기존 자료 연결·연결 해제는 해당 강의실 소유 `INSTRUCTOR`만 수행합니다. 전역 자료 목록은 본인 소유 자료만 반환하고 강의실 자료는 주차 API에서 발견합니다.
  - 동일 사용자의 동일 자료 학습 이력은 개인 학습과 여러 강의실에서 공유합니다. `learning_sessions`에 `classroom_id`를 추가하지 않으며, 강의실 진도는 공개 주차에 연결된 고유 자료의 사용자×자료 설명 완료 이력을 합산합니다. 다른 사용자의 이력은 공유하지 않습니다.
  - 강의실 진도율은 공개 주차에 연결된 고유 READY 자료를 대상으로 `고유 (material_id, page_number) 설명 완료 수 ÷ 고유 자료 page_count 합 × 100`을 정수 반올림합니다. 같은 자료의 여러 주차 연결은 한 번만 계산하고, 이력 또는 유효한 분모가 없으면 0입니다.
  - 주차 상태는 저장하지 않고 `release_at`과 현재 UTC 시각을 비교해 파생합니다. `NULL`은 즉시 공개, 미래는 `SCHEDULED`, 도래 시각 이후는 `PUBLISHED`입니다.
  - `currentWeek`은 `Asia/Seoul`의 오늘을 기준으로 `min(weekCount, floor((today-startDate)/7)+1)`로 계산하고, 시작 전은 1, 종료 후는 `weekCount`입니다. `weekCount`는 `ceil((endDate-startDate+1일)/7)`로 서버가 계산합니다.
  - 주차 번호는 `1 <= weekNumber <= weekCount`입니다. 기존 최대 주차보다 작아지도록 `endDate`를 줄이는 요청은 `CLASSROOM_WEEK_RANGE_CONFLICT`로 거부합니다. `startDate`는 생성 후 변경하지 않습니다.
  - PATCH에서 필드 생략은 변경 없음, `releaseAt: null`은 즉시 공개, `description: null`은 설명 삭제를 의미합니다.
  - 강의실 색상은 `BLUE | GREEN | PURPLE | ORANGE | RED | GRAY`를 사용합니다. FE 표시값은 각각 `#3B82F6`, `#22C55E`, `#8B5CF6`, `#F97316`, `#EF4444`, `#64748B`입니다.
  - 참여 요청은 `(classroom, user)`당 한 행입니다. 거절 후 재요청은 같은 행을 `PENDING`으로 바꾸고 `requested_at`을 갱신하며 `processed_at`을 비웁니다. 멤버 탈퇴·강퇴는 MVP에서 지원하지 않습니다.
  - 자료 연결 해제 또는 공개 취소 후 다른 소유권·공개 주차 접근 경로가 없으면 신규 자료 조회·파일 조회·세션 생성과 기존 세션의 추가 학습 턴을 차단합니다. 기존 세션·메시지·퀴즈 기록은 보존합니다. 완료 강의실은 명시적 상태 전환으로만 만들고 기존 멤버에게 공개 자료 조회와 본인 통합학습을 유지하되 강의실 관리 쓰기는 거부합니다.
- 이유: 강의실은 리포트·강의실 통계의 권한 경계와 자료 범위를 제공하면서도 기존 사용자×자료 학습 모델과 AI snapshot 계약을 재사용할 수 있습니다. 강의실별 세션 복제를 피하고 설명 완료 이력을 자료 학습 성취의 공통 근거로 사용합니다.
- 대안과 trade-off: 세션에 `classroom_id`를 저장하면 강의실별 학습 출처를 엄격히 분리할 수 있지만 동일 자료의 중복 세션·진도와 API 변경이 발생해 MVP에서 제외합니다. 학습자 개인 업로드 금지는 기존 자유 학습 흐름을 깨므로 채택하지 않습니다.
- 후속 변경 문서: [API 명세](api-spec.md), [데이터베이스](database.md), [에러 코드](error-code.md), [도메인 모델](domain-model.md), [화면-API 매핑](screen-api-map.md)

### DEC-031 — 별도 시험 생성·응시·채점 계약

- 상태: Accepted — [GitHub #133](https://github.com/AutoAI-EduPilot/BE/issues/133)에서 프로젝트 담당자가 권장안을 승인했습니다. AI 담당자의 사전 설계 확인은 승인 게이트에서 제외하되 AI Service v0.6 구현·계약 테스트·재기동 검증은 후속 완료 게이트로 유지합니다. 제출 후 정답·해설 공개(D4)는 Deferred이며, 확정 전까지 비공개를 적용합니다.
- 결정일: 2026-08-03
- 결정자: 프로젝트 담당자. grade optional 필드와 정수 `quizId`는 본 결정으로 확정하며 AI 담당자는 구현·검증 결과를 보고합니다.
- 선택:
  - **D1 — 출제**: MVP는 강사 직접 출제만 지원합니다. AI 시험 초안 생성(#135)은 Phase C로 이월하며 `exam_questions.source` 같은 선행 확장 컬럼을 두지 않습니다.
  - **D2 — 재응시**: `allow_retake=false`가 기본입니다. 허용 시 모든 시도를 `attempt_no` 순서로 보존합니다. 운영 화면의 최신 제출은 상태와 무관한 `MAX(attempt_no)`, 성적·리포트 대표값은 DEC-032에 따라 `MAX(attempt_no WHERE status=GRADED)`로 파생합니다.
  - **D3 — 주관식 루브릭**: SHORT의 `referenceAnswer`와 ESSAY의 `modelAnswer`는 필수이고 rubric은 선택입니다. `null`과 빈 배열은 모두 미입력으로 취급해 grade 호출 시 `[{"criterion":"모범 답안 부합도","weight":1.0}]`을 주입합니다. DRAFT에는 불완전한 weight 합도 저장할 수 있고, 공개 시 입력된 rubric의 weight 합이 1.0인지 검증합니다.
  - **D4 — 정답·해설 공개**: 정책 확정 전에는 제출 후에도 정답, 해설, 모범 답안과 rubric을 학생 응답에 포함하지 않습니다. 공개 전환은 별도 결정으로 추가하며 기존 공개 문항 조회 경로에는 조건부 정답 직렬화를 넣지 않습니다.
  - **D5 — AI 채점**: SHORT/ESSAY는 기존 `/internal/ai/grade`를 재사용합니다. 시험은 `pageContext`와 `learnerMemoryDigest`를 생략하고, 동일 답안의 채점이 학습자 메모리에 따라 달라지지 않게 합니다. grade `quizId`에는 숫자 `examId`를 사용하며 wire 타입 변경은 후속 계약 버전에서 다룹니다. 응답이 있는 SHORT와 ESSAY를 유형별로 묶어 각 최대 1회 호출하고, 한 유형 호출이 실패해도 나머지 유형은 계속 호출해 성공 결과를 보존합니다.
  - **D6 — 미응답**: 누락 답안은 `answer=NULL`, `score=0`, `verdict=WRONG`, `feedback=NULL`로 저장하고 AI 채점 요청에서 제외합니다. 전 문항 미응답 제출도 유효한 0점 시도로 보존합니다.
  - **D7 — 채점 실패**: AI 대상 답안은 채점 완료 전과 실패 시 `score`, `verdict`, `feedback`을 `NULL`로 둡니다. 제출 총점·정규화 점수도 완전한 채점 전에는 `NULL`이며, 결정적 채점 결과와 미응답 결과는 유지합니다.
  - **D8 — 빈 DRAFT**: 문항이 없는 DRAFT와 `total_score=0`을 허용합니다. 공개 시점에만 문항 1개 이상, 양수 총점, 유형별 비공개 정답과 rubric 불변식을 검증합니다.
  - 시험 상태는 `DRAFT → PUBLISHED → CLOSED` 단방향입니다. 공개는 PUBLISHED에서, 마감은 CLOSED에서만 멱등입니다. DRAFT 마감은 `EXAM_NOT_PUBLISHED`, CLOSED 공개는 `EXAM_NOT_EDITABLE`로 거부합니다.
  - 완료 강의실은 신규 학습 활동인 시험 생성·수정·공개·제출만 `CLASSROOM_COMPLETED`로 차단합니다. 기존 PUBLISHED 시험의 마감과 DRAFT 시험의 물리 삭제는 정리 작업으로 허용합니다.
  - 동일 제출의 네트워크 재시도는 같은 `requestId`를 사용해 기존 제출을 반환하고, 재응시는 반드시 새 `requestId`를 발급합니다.
  - 응답이 있는 SHORT/ESSAY 문항이 하나도 없으면 AI를 호출하지 않고 결정적 결과만으로 `GRADED`를 확정합니다. 실제 AI 호출이 하나 이상 발생하고 일반 채점 오류가 하나라도 생긴 경우에만 `GRADING_FAILED`로 저장합니다.
  - 최초 동기 채점 계약에서는 `AI_REQUEST_INVALID`을 보상 삭제 후 `INTERNAL_SERVER_ERROR`(500)로 반환하기로 했습니다. 비동기 채점 전환 이후의 처리는 DEC-032가 대체합니다.
- 이유: 별도 시험은 통합 학습 퀴즈와 데이터를 분리하면서 기존 결정적 채점과 GraderAgent 검증을 재사용해야 합니다. 공개 전 편집 자유도, 채점 실패의 정확한 표현, 전 시도 보존을 보장해야 리포트의 최신·누적 추세가 왜곡되지 않습니다.
- 대안과 trade-off: 정답·해설 즉시 공개는 학습 피드백이 빠르지만 재응시 시험의 정답 노출 문제가 있어 보류했습니다. AI 출제 초안은 편의성이 있으나 ReportAgent보다 우선하지 않아 Phase C로 이월합니다.
- 후속 변경 문서: [API 명세](api-spec.md) §6.2, [데이터베이스](database.md), [도메인 모델](domain-model.md), [에러 코드](error-code.md), [화면-API 매핑](screen-api-map.md), [AI 연동 계약](ai-integration-contract.md) v0.6

### DEC-032 — 시험 비동기 채점·복구와 성적 대표값

- 상태: Accepted — 시험 도메인 구현 후 발견된 고아 `SUBMITTED` 복구와 응답 지연 문제를 보완합니다. 신규 구현 이슈 번호는 원격 이슈 등록 후 연결합니다.
- 결정일: 2026-08-03
- 결정자: 프로젝트 담당자
- 선택:
  - 응답 있는 SHORT/ESSAY가 있으면 제출을 `SUBMITTED`로 커밋하고 동일한 `ExamSubmissionResponse` 봉투를 HTTP 202로 즉시 반환합니다. MCQ/OX 전용 또는 주관식 전부 미응답은 기존대로 즉시 `GRADED`, HTTP 200입니다. FE는 HTTP 코드가 아니라 본문의 `status`로 분기합니다.
  - `SUBMITTED` 응답은 총점·정규화 점수·채점 시각뿐 아니라 이미 계산된 MCQ/OX의 문항별 `score`, `verdict`, `feedback`도 null로 마스킹합니다. 본인 `answer`, `maxScore`, `questionId`는 유지하며 문항별 결과는 `GRADED | GRADING_FAILED`에서만 공개합니다. 이는 재응시 허용 시험에서 객관식 정오답 선공개로 생기는 정보 이득을 막기 위함입니다.
  - 제출 커밋 뒤 bounded executor(core 4, max 4, queue 100, AbortPolicy)에 직접 전달합니다. worker는 5분 lease를 조건부 claim하고 `status=SUBMITTED AND grading_lease_token=:token`일 때만 terminal 결과를 반영해 늦은 worker 덮어쓰기를 막습니다.
  - scheduler는 30초마다 최대 100건을 처리합니다. `SUBMITTED.updated_at`을 마지막 채점 시도 시작 시각으로 사용하며 30분 컷오프에서 첫 두 번은 재큐잉하고 세 번째는 `GRADING_FAILED`로 종결합니다. 카운트와 상태 변경은 기존 CAS 조건을 유지하며 active lease보다 우선합니다. 강사는 실패 제출을 저장 답안으로 재채점할 수 있고 이때 카운트를 0으로 초기화합니다. 일반 AI 오류와 잡힌 worker 예외는 즉시 실패 처리합니다.
  - 비동기 worker가 `AI_REQUEST_INVALID`을 받으면 재시도하지 않고 `GRADING_FAILED`로 종결하며 ERROR 로그로 Spring-AI 계약 결함을 구분합니다. 원 POST에 500을 반환하거나 이미 커밋된 제출을 보상 삭제하지 않습니다. 이 항목은 DEC-031의 동기 처리 규칙을 대체합니다.
  - 같은 `requestId`는 기존 상태를 반환합니다. 최신 제출이 `SUBMITTED`이면 새 requestId를 거부하고, `GRADING_FAILED`는 `allowRetake`와 무관하게 응시권을 소모하지 않아 새 requestId로 다음 attempt를 만들 수 있습니다.
  - 운영 조회·polling·제출 제한의 최신 시도는 상태와 무관한 `MAX(attempt_no)`입니다. 성적·리포트 대표 제출은 `MAX(attempt_no WHERE status=GRADED)`이며 실패 시도는 제외합니다. 예를 들어 1회차 `GRADED` 80점 뒤 2회차 `GRADING_FAILED`이면 대표 성적은 1회차 80점입니다. GRADED 시도가 없는 학생은 점수·성취도 집계에서 제외합니다.
- 이유: 외부 AI 호출을 요청 트랜잭션과 분리하면서 프로세스 종료·executor 포화·늦은 worker에도 제출을 회수할 수 있어야 합니다. 채점 실패는 시스템 장애이므로 이미 확정된 학생 성적을 지우거나 응시권을 영구 소모해서는 안 됩니다.
- 대안과 trade-off: 동기 채점은 구현이 단순하지만 요청 지연과 고아 제출 복구가 어렵습니다. 단일 30분 절대 컷오프는 executor 적체와 일시 장애를 영구 실패로 만들 수 있어, 제한된 3개 채점 창과 강사 재채점 API를 추가하고 기존 lease·CAS 구조는 유지합니다.
- 후속 변경 문서: [API 명세](api-spec.md) §6.2, [데이터베이스](database.md), [도메인 모델](domain-model.md), [에러 코드](error-code.md), [화면-API 매핑](screen-api-map.md), [리포트 설계](report-agent-design.md)

### DEC-033 — 리포트 범위·평가 정책

- 상태: Accepted — [GitHub #117](https://github.com/AutoAI-EduPilot/BE/issues/117) 결정
- 결정일: 2026-08-03
- 결정자: 프로젝트 담당자, Main Service 담당자
- 선택:
  - MVP 리포트 생성·조회·질의응답은 강의실 관리 `INSTRUCTOR` 전용입니다. 학생 본인 조회는 허용하지 않으며, 문구 수위와 심리적 영향을 검토한 뒤 도입 여부를 다시 결정하는 명시적 TBD로 둡니다.
  - 분석 범위는 **전체 기간**과 **주차 선택** 두 종류입니다. 세션 단위와 시험 단위의 개별 선택은 TBD입니다.
  - 누적 지표와 비교할 최근 window는 **14일**입니다.
  - 생성 진행 확인은 시험 비동기 채점과 같은 **HTTP 202 응답 + status polling** 패턴을 사용합니다. SSE 진행 이벤트는 TBD입니다.
  - 기본 평가 기준은 초안 10종에서 `학습 자신감`을 제외한 **9종**입니다. 자기보고 데이터가 없어 항상 `INSUFFICIENT_DATA`가 되기 때문이며, 자기보고 기능을 도입할 때 다시 추가합니다. 기본 weight는 균등하고 criterion별 최소 근거는 **2건**입니다.
  - 종합 단계는 **우수 / 양호 / 보통 / 보완 필요**의 4단계입니다. 종합 점수와 단계는 Spring이 충분한 항목만 대상으로 결정적으로 계산합니다.
  - 페이지 진도는 `progressDataAvailable=true`로 포함합니다. V9 `session_page_records`가 성공한 `EXPLAIN_CURRENT_PAGE` 턴이 `EXPLAINED`로 완료된 페이지만 기록하므로 설계 §3.2의 설명 완료 근거 요건을 충족합니다. 기록은 `TurnPersistenceService`, 집계는 `SessionPageRecordRepository.countDistinctByUserIdAndMaterialId`, 진도율은 `LearningProgressService`를 재사용합니다.
  - 별도 시험 도메인의 병합·배포가 완료됐으므로 리포트는 처음부터 별도 시험을 포함하고 Phase 1과 Phase 2를 통합 착수합니다.
  - 학생 리포트의 version·previous report·trend는 scope별 체인으로 관리합니다. FULL은 FULL끼리, WEEK는 같은 주차끼리 연결하며 V25 이전 혼합 체인의 역사적 연결·trend는 보정하지 않습니다.
  - 리포트 snapshot과 리포트 QA는 무기한 보관하고 학생 탈퇴 시 기존 `UserWithdrawalHook` 패턴에 연동해 삭제합니다. 보관 기한 단축은 TBD입니다.
  - evidence는 공개 가능한 label과 최소 fact만 노출합니다. 전체 원문·정답·루브릭은 노출하지 않습니다.
  - 리포트 생성용 read timeout은 구현 이슈에서 `edupilot.ai.report-read-timeout=180s` 프로퍼티로 신설합니다.
  - 잔여 TBD는 SSE 진행 이벤트, 세션·시험 단위 범위 선택, 학생 본인 조회, 보관 기한 단축, 강의실 전체 경향 리포트의 최소 인원입니다.
- 이유: 현재 강의실·별도 시험·페이지 설명 완료 근거를 재사용하면 추정 진도나 미확정 시험 계약 없이 강사 지도용 리포트를 시작할 수 있습니다. 자기보고가 없는 평가 기준과 과도한 원문 노출은 데이터 품질·심리·보안 위험이 있어 제외합니다.
- 대안과 trade-off: 학생 조회와 세밀한 범위 선택, SSE, 장기 보관 단축은 사용성과 운영 효율을 높일 수 있지만 문구 정책·개인정보·계약 복잡도 검토가 선행돼야 합니다. 학습 자신감을 행동 데이터로 추론하면 구현은 가능하지만 근거 없는 심리 판단이 되므로 채택하지 않습니다.
- 후속 변경 문서: [요구사항 명세](requirements.md), [리포트 설계](report-agent-design.md), [리포트 작업 분해](issues/13-report-agent.md)

### DEC-034 — 리포트 내부 AI 계약 5건

- 상태: Accepted — [GitHub #118](https://github.com/AutoAI-EduPilot/BE/issues/118) 중 내부 AI 계약 5건 확정. 외부 Report API 계약은 계속 진행합니다.
- 결정일: 2026-08-03
- 결정자: 프로젝트 담당자, Main Service 담당자
- 선택:
  - `reportId`와 `generationId`의 wire 타입은 Spring 내부 타입과 무관하게 **string**으로 고정하며 union 타입을 허용하지 않습니다.
  - evidence 상한은 **200개**입니다. Spring `ReportSnapshotBuilder`가 결정적 선별 규칙으로 상한 안의 evidence를 구성하는 1차 방어를 담당하고, AI Service는 `max_length=200` 초과 요청을 HTTP 422로 거부하는 2차 방어를 담당합니다.
  - criteria 상한은 **20개**입니다. Spring criterion CRUD가 강의실당 활성 기준 20개 초과 등록을 거부하는 1차 강제 지점이고, AI Service의 `max_length=20`은 이중 안전망입니다.
  - `previousReport`는 직전 **1개 version**의 `criterionKey`, `score`, `status`만 전달하며 narrative는 포함하지 않습니다.
  - `trend`는 Spring이 점수 이력으로 결정적으로 계산해 저장합니다. AI 요청·응답 스키마에는 포함하지 않으며, 도메인의 `ReportCriterionResult.trend`는 Spring 계산값의 저장 컬럼으로 유지합니다.
- 이유: 단일 wire 타입과 양쪽 상한 검증으로 계약 분기와 프롬프트 비대화를 막고, 이전 narrative의 자기복제 및 AI 추세 재계산을 차단합니다.
- 대안과 trade-off: 숫자·문자열 union ID는 Spring 구현 선택을 노출해 소비자 검증을 복잡하게 합니다. AI가 trend를 생성하면 서술 유연성은 높지만 동일 이력의 재현성이 떨어지므로 채택하지 않습니다.
- 후속 변경 문서: [리포트 설계](report-agent-design.md), [리포트 작업 분해](issues/13-report-agent.md). 실제 DTO·AI 계약 반영은 #121 및 후속 구현 이슈에서 수행합니다.

### DEC-035 — PDF 원본의 xAI Files 단계 전환

- 상태: Accepted — 설계자 승인, Phase 1 [#303](https://github.com/AutoAI-UTEUM/BE/issues/303), Phase 3 [#311](https://github.com/AutoAI-UTEUM/BE/issues/311)
- 결정일: 2026-08-25
- 결정자: 프로젝트 설계자, AI Service 담당자
- 선택:
  - LLM이 PDF 원본을 직접 읽는 구조로 단계적으로 전환하되, 기존 페이지 텍스트 추출은 페이지 근거의 앵커·폴백으로 유지합니다.
  - Phase 1은 `EDUPILOT_XAI_FILES_ENABLED` kill switch가 켜진 경우에만 추출 성공 원본을 xAI Files에 업로드합니다. 기본값은 `false`이며 응답의 nullable `xaiFileId`와 `warnings[{type,message}]`로 결과를 전달합니다.
  - 업로드 실패나 xAI 파일 제한 48MiB 초과는 `FILE_UPLOAD_FAILED` warning으로 강등하고 텍스트 추출 성공 응답은 HTTP 200을 유지합니다.
  - `DELETE /internal/ai/files/{fileId}`는 kill switch와 무관하게 동작합니다. 삭제 성공과 이미 없는 파일(404)은 204로 멱등 처리하고, 그 밖의 provider 오류는 502 `FILE_DELETE_FAILED`(`INTERNAL`, `retryable=true`)로 반환합니다.
  - Spring은 `xaiFileId`를 자료에 저장하고 자료 삭제 시 정리 훅을 호출합니다. Phase 3에서는 턴 context에 nullable `xaiFileId`를 전달하고 AI Service가 Explainer·QaAgent의 실제 LLM 호출에 첨부합니다. Phase 5에서는 QuizAgent와 개요 생성까지 첨부를 확대하되 퀴즈는 현재 페이지 단일, 개요는 전달된 pages 범위를 앵커로 유지합니다. Plan·결정적 안내·Repair·Note에는 첨부하지 않으며 `includeCurrentPage=false`이면 사용하지 않습니다.
  - 첨부 호출은 xAI Responses API의 `input_file.file_id`를 사용하고 `store=false`를 강제합니다. 추출된 현재 페이지 텍스트와 질문은 범위 앵커이며 원본 PDF는 해당 범위의 세부 근거 확인용입니다.
  - 기존 ACTIVE·READY 자료의 소급 업로드는 텍스트를 다시 추출하지 않는 `POST /internal/ai/files`로 수행합니다. 명시적 API는 `/extract` 자동 업로드 kill switch와 독립이며, Spring의 별도 기본 OFF bounded backfill이 작업량을 통제합니다. claim·저장을 짧은 row-lock 트랜잭션으로 분리하고 외부 호출 중에는 트랜잭션을 유지하지 않습니다. 실패 시 READY를 보존하고 backoff를 적용하며 경합으로 저장하지 못한 ID는 베스트에포트 삭제합니다.
- 이유: 원본의 시각·레이아웃 정보를 이후 LLM 입력에서 활용할 수 있는 기반을 만들면서도, provider 업로드 장애 때문에 이미 성공한 결정적 텍스트 추출과 자료 등록이 실패하지 않도록 단계와 실패 경계를 분리합니다.
- 대안과 trade-off: 즉시 원본 첨부만 사용하면 페이지 단위 근거 제어와 provider 장애 폴백을 잃습니다. 텍스트 추출만 유지하면 시각·레이아웃 정보 활용이 제한됩니다. 양쪽을 병행하면 저장·삭제 수명주기 관리가 추가되지만 kill switch와 멱등 삭제로 운영 위험을 제한합니다.
- 후속 변경 문서: [AI 통합 계약](ai-integration-contract.md) §0·§2·§3·§6.1·§6.6·§7, [API 명세](api-spec.md) §8, [에러 코드](error-code.md), [에이전트 명세](agent-system-spec.md). 캡션 축소 여부는 원본 첨부가 적용되지 않는 폴백·채점·doc-chat 경로를 포함해 별도 운영 이슈에서 판단합니다.

### DEC-036 — 개요 section 경계 기반 퀴즈 제안

- 상태: Accepted — 설계자 승인, [#319](https://github.com/AutoAI-UTEUM/BE/issues/319)
- 결정일: 2026-08-25
- 결정자: 프로젝트 설계자, Backend 담당자, AI Service 담당자
- 선택: Spring이 현재 페이지 설명 완료와 텍스트 200자 이상을 먼저 확인한 뒤, READY 개요가 1페이지부터 자료 마지막 페이지까지 연속 coverage이면 `sections[].endPage`에서만 퀴즈를 제안합니다. 개요 없음/PENDING/FAILED와 구버전 불완전 READY 개요는 기존 200자 규칙으로 fallback합니다. 조회 정책은 별도 `QuizProposalPolicy`가 소유하고 `UiActionResolver`는 결정된 boolean만 받아 순수 위젯 매핑을 유지합니다. 제안 시점과 무관하게 AI 퀴즈 출제 범위는 현재 페이지 단일입니다.
- 이유: 매 페이지 퀴즈 제안으로 흐름이 자주 끊기는 문제를 줄이면서, #316 이전에 저장된 불완전 개요가 퀴즈 제안을 영구 차단하지 않도록 안전한 폴백을 보존합니다.
- 대안과 trade-off: 모든 READY 개요를 무조건 신뢰하면 legacy gap 때문에 경계가 사라질 수 있고, section 전체를 출제 범위로 쓰면 아직 설명하지 않은 페이지가 섞일 수 있어 채택하지 않습니다. 경계 판정은 overview 단건 조회가 추가되지만 설명 완료·텍스트 임계 통과 시에만 발생합니다.
- 후속 변경 문서: [API 명세](api-spec.md) §5 W3, [기능 명세](feature-spec.md) §7. wire 계약과 DB migration은 변경하지 않습니다.

### DEC-037 — AI checkpoint 기반 퀴즈 제안과 coverage 출제

- 상태: Accepted — 설계자 승인, [#338](https://github.com/AutoAI-UTEUM/BE/issues/338)
- 결정일: 2026-08-28
- 결정자: Backend 담당자, AI Service 담당자
- 선택: READY 개요의 유효한 `quizCheckpoints`를 section 종료 추론보다 우선합니다. Spring은 현재 페이지가 `triggerPage`일 때만 퀴즈를 제안하고 checkpoint 모드에서는 200자 게이트를 적용하지 않습니다. 유형 선택 턴은 기존 nullable `quizContext` 계약에 coverage 전 페이지의 캡션 병합 텍스트를 오름차순으로 넣고 전체 12,000자 뒤쪽을 결정적으로 절단합니다. AI 출력 coverage는 이 범위와 같아야 하며 퀴즈 엔티티의 `pageNumber`는 현재 triggerPage로 유지합니다. checkpoint가 없거나 저장 JSON 검증에서 탈락하면 DEC-036의 기존 200자·section 정책으로 fallback합니다.
- 방어 경계: Spring은 수신·저장 `quizCheckpoints`의 개수, 범위, trigger 일치, 중복·순서·겹침, section 경계를 재검증합니다. 위반 시 원문을 로그에 남기지 않고 위반 유형만 WARN으로 기록하며 개요 전체가 아니라 checkpoint 계획만 absent로 강등합니다. 구버전 READY 개요 중 계획이 없는 행은 기존 개요 backfill batch에 포함해 점진적으로 재생성합니다.
- 이유: 짧은 전환 페이지의 글자 수가 아니라 자료 전체 흐름을 읽은 AI 계획으로 학습 중단 시점을 정하고, 실제 출제 근거도 그 시점까지 학습한 연속 범위와 일치시키기 위해서입니다.
- 대안과 trade-off: section 끝과 200자 게이트를 계속 사용하면 단순하지만 의미 있는 짧은 trigger를 놓칩니다. checkpoint를 무조건 신뢰하면 저장 JSON 변조·구버전 데이터가 출제 범위를 오염시킬 수 있어 Spring 정규화와 기존 정책 fallback을 함께 유지합니다.
- 후속 변경 문서: [AI 통합 계약](ai-integration-contract.md) §3.1·§6.6, [API 명세](api-spec.md) §5 W3·§8, [기능 명세](feature-spec.md) §7, [DB 명세](database.md). API·DB 스키마와 환경 변수는 변경하지 않습니다.

### DEC-019 — AWS 구성 (단일 EC2 + Docker Compose)

- 상태: Accepted
- 결정일: 2026-07-23
- 결정자: 한승준 (Backend/Infra) — 세부 스펙은 dev 프로비저닝 시 조정
- 선택: **EC2 1대(t3.small~medium)에서 Docker Compose로 전체 스택을 실행**한다 — Nginx + Spring + FastAPI + MySQL(컨테이너 + EBS 볼륨). Nginx만 80/443을 공개하고 FastAPI·MySQL은 Docker 내부 네트워크에 비공개로 둔다(DEC-014 정합). 업로드 파일은 EC2 볼륨 마운트로 저장한다(DEC-005 storage_key 어댑터 정합). FE는 같은 Nginx에서 정적 서빙하며 `/api`를 리버스 프록시한다. 도메인 1개 + Let's Encrypt(certbot)로 HTTPS를 구성한다 — refresh 쿠키(HttpOnly·Secure)와 SSE에 HTTPS가 필요하다.
- 이유: 팀 프로젝트 규모에서 비용·운영 난이도를 최소화하면서 재현 가능한 배포(Compose 단일 정의)를 얻는다. 로컬 Compose와 dev 구성이 같은 파일 체계를 공유해 환경 차이가 줄어든다. FE 동일 오리진 서빙으로 CORS·쿠키 이슈도 최소화된다.
- 대안과 trade-off: RDS 분리는 백업·가용성이 강점이나 비용이 즉시 2배 이상이고 MVP 트래픽에 과설계. S3+CloudFront FE 배포는 확장성이 좋으나 CORS·쿠키 도메인 관리가 복잡해진다. ECS/K8s는 필요성이 검증되지 않았다.
- **이후 개선안**: 트래픽·안정성 요구 발생 시 단계 확장 — ① RDS 분리(백업 자동화) → ② S3 파일 저장 전환(DEC-005 개선안과 동시 진행, presigned URL) → ③ FE S3+CloudFront → ④ ECS 전환. 각 단계는 독립적으로 진행 가능하다.
- 후속 변경 문서: [배포·운영 상세 계획](issues/12-deployment.md), README §4 기술 스택

### DEC-020 — 라이선스 (비공개 유지)

- 상태: Accepted
- 결정일: 2026-07-23
- 결정자: 팀
- 선택: **저장소를 비공개(private)로 유지하고 라이선스 파일을 두지 않는다.** 오픈소스로 공개하지 않는다.
- 이유: 팀 결정으로 코드 공개 계획이 없다. 비공개 저장소는 라이선스 없이도 저작권이 팀에 유보되며, 불필요한 라이선스 파일은 공개 의사로 오해될 수 있다.
- 대안과 trade-off: MIT 공개는 포트폴리오 활용에 유리하나 공개 전 저작권 콘텐츠·비밀값 전수 점검이 선행돼야 한다.
- **이후 개선안**: 공개로 전환하는 경우 ① 라이선스는 MIT를 우선 검토 ② PDF 강의 자료 등 저작권 콘텐츠와 비밀값·커밋 이력 전수 점검 ③ 의존성 라이선스 고지 확인을 선행 조건으로 한다. 전환 시 이 DEC를 Superseded로 갱신한다.
- 후속 변경 문서: README §4(구현 전 확정 필요 목록에서 제거), CONTRIBUTING(공개 전환 시)

### DEC-011 — 평가 큐 (QuizAssessment 보관·전달 정책)

- 상태: Accepted
- 결정일: 2026-07-23
- 결정자: 한승준 (Backend) — 스냅샷 계약은 AI(고영빈)와 계약 리뷰에서 공유
- 선택: **DB(`quiz_assessments`)는 삭제 없이 전량 보존**하고, "큐"는 스냅샷 전달용 조회 윈도우로 재정의한다. ① turn 스냅샷의 `recentAssessments`는 **현재 세션 기준 최근 N=5개**(프롬프트 비대화 방지, `IDX(session_id, created_at)` 사용). ② 메모리 승격 판단용 조회는 별도로 **user×material 교차 세션 최근 M=20개**를 사용한다(`quiz_submissions` 조인으로 user 스코프 확보 — 비정규화 컬럼은 두지 않음).
- 이유: 큐를 물리 삭제로 구현하면 감사·승격 근거가 소실된다. 승격 판단은 세션을 넘는 반복 패턴이 근거여야 하므로(LEARN-005) 세션 스코프 윈도우와 승격용 교차 세션 조회를 분리해야 한다. MVP 데이터량에서 정리 작업은 불필요하다.
- 대안과 trade-off: 고정 크기 큐(오래된 레코드 삭제)는 저장 공간에 유리하나 근거 소실·감사 불가. `quiz_assessments`에 `user_id` 비정규화는 조회가 단순해지지만 정합성 관리 비용이 생겨 MVP에서는 조인을 유지한다.
- **이후 개선안**: 보관 기간·정리(아카이빙) 정책은 운영 데이터가 쌓인 뒤 DEC-028(데이터 보관·삭제·익명화)과 함께 결정한다. 조인 성능이 문제가 되면 그때 `user_id` 비정규화 또는 요약 테이블을 검토한다.
- 후속 변경 문서: [데이터베이스](database.md) §1·§6·§8, [API 명세](api-spec.md) §8 스냅샷 구조, [에이전트 시스템 명세](agent-system-spec.md) 스냅샷·메모리 관련 절

### DEC-012 — 학습자 메모리 승격 기준

- 상태: Accepted
- 결정일: 2026-07-23
- 결정자: 한승준 (Backend) — Product·AI 관점은 계약 리뷰에서 공유
- 선택: **독립 근거 2회 이상**일 때만 승격한다. 서로 다른 출처(퀴즈 평가/진단/QA 패턴) **또는** 서로 다른 세션·시점에서 동일 패턴이 2회 이상 관측된 후보만 승격 대상이다. 절차는 3중 게이트로 고정한다 — ① `learner_memory_candidates`에 후보 저장(`evidence_refs_json`에 근거 참조 누적) → ② Orchestrator가 `PROMOTE_MEMORY` 도구 선택 → ③ Policy가 "독립 근거 2회 이상 + confidence 0.7 이상" 규칙을 검증 통과시킨 경우에만 statePatch로 승격(Spring이 낙관적 잠금으로 반영). confidence 0.7 미만 후보는 승격 대상에서 제외한다.
- 이유: 원안 명세서 원칙("단일 질문·단일 퀴즈 결과만으로 장기 메모리를 확정하지 않는다")을 검증 가능한 규칙으로 구체화한 것이다. LLM 판단(Orchestrator)과 결정적 검증(Policy)을 분리해 과잉 승격을 차단한다.
- 대안과 trade-off: 3회 이상 기준은 더 보수적이나 MVP 데이터량에서 승격이 거의 발생하지 않아 개인화 검증이 불가능해진다. LLM 단독 판단은 유연하나 재현성·감사가 어렵다.
- **감사 이력(MVP)**: 별도 이력 테이블 없이 `learner_memory_candidates`로 처리한다 — 승격 시 후보를 삭제하지 않고 `status=PROMOTED`로 보존하여 `evidence_refs_json` + 상태 전이 기록이 이력 역할을 한다.
- **이후 개선안**: 메모리 항목별 변경 이력·롤백이 필요해지면 별도 이력 테이블(`learner_memory_revisions` 등)을 도입한다. 자료 범위를 넘는 전역 프로필은 DEC-023 대안 검토와 함께 별도 결정한다.
- 후속 변경 문서: [데이터베이스](database.md) §1 candidates·§8, [에이전트 시스템 명세](agent-system-spec.md) Policy 규칙, [요구사항 명세](requirements.md) LEARN-005

## 결정 기록 형식

```markdown
### DEC-XXX — 제목

- 상태: Accepted / Superseded
- 결정일: YYYY-MM-DD
- 결정자: ...
- 선택: ...
- 이유: ...
- 대안과 trade-off: ...
- 후속 변경 문서: ...
```

## #27 서면 확정 (2026-07-27)

- **A1 — 제한적 Plan 보정**: Policy는 `page`를 스냅샷
  `currentPage`로, `detailLevel`을 이벤트 payload 값으로 교정할 수 있습니다.
  도구별 여분 args 키는 통지 없이 제거합니다.
- **A2 — 보정 불가·거부 범위**: 이벤트-도구 불일치, 결정적 파이프라인
  전용 도구, `FOLLOW_UP`의 `qaThreadDigest` 부재, 스냅샷과 다른
  `threadRef`는 보정하지 않고 거부합니다.
- **A3 — 보정 감사 정보**: 실행 결과의 선택 필드
  `adjustments[]{field, from, to, reason}`로 보정을 남깁니다. `reason`은
  Spring이 enum 검증하지 않는 자유 문자열이며 초기값은
  `PAGE_MISMATCH_CORRECTED`,
  `EVENT_PAYLOAD_MISMATCH_CORRECTED`입니다.
- **A4 — QA thread 소유권**: `threadRef`는 Spring이 `qa-{id}` 형식으로
  발급합니다. `START_NEW` statePatch에는 `mode`만 포함하고,
  `FOLLOW_UP`은 요청 스냅샷의 `qaThreadDigest.threadRef`를 그대로
  반환합니다.
- **B1 — uiActions 소유권**: AI Service의 `uiActions`는 예약 필드이며
  항상 빈 배열입니다. 비어 있지 않은 값은 Spring이 무시하고 경고하며,
  사용자 위젯은 Spring이 생성합니다.
- **B2 — 위젯 복원 규칙**: Spring은 API 명세 §5의 W1~W7 중 마지막 상태
  전이 1개에 대해서만 위젯을 생성합니다. 재진입은 서버 발급 위젯만
  복원하고, FE 로컬 `QUIZ_TYPE_SELECT` 표시 중 재진입하면 W3으로
  복원합니다. W5는 마지막 페이지에서 완료 API로 분기합니다.
- **B3 — 재시도 소유권**: provider 어댑터 자동 재시도는 두지 않습니다.
  SCHEMA는 Orchestrator의 1회 재생성으로 소진하고, Spring은
  `retryable=true`인 오류만 최대 1회 재시도합니다.
- **B4 — turn 시간 예산**: Plan과 Agent 호출을 합친 turn 총 시간을
  180초 이하로 제한합니다. 호출별 남은 시간 전달과 예산 분배는
  스트리밍 이슈 #25에서 구현합니다.
