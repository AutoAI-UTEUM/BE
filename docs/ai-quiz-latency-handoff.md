# 퀴즈·설명 지연 개선 — AI 구현 및 Spring·FE 연동 초안

기준: 2026-09-29, develop `4bed3bcd3cffbfe9460e0ac99e4e0a2b8f0d4d95`.
연동 추적: [#445](https://github.com/AutoAI-UTEUM/BE/issues/445).
상태: **AI 코드·오프라인 검증 완료. 실 xAI 검증·Spring·FE 연동 완료 아님.**
코드 병합·dev 배포 증적은 연결 PR과 Deploy dev Actions에서 확인한다. 코드 배포와 기능 활성화는 별개다.
2026-09-29 Spring 회신: A(계획 저장·전달) → B(QA 추가 제안) → C(문항 선전달) 순서로 연동한다.
AI 내부 필드·null 의미·공개 이벤트 예시는 [정본 계약 §3.1.1·§5.1.1·§6.6](ai-integration-contract.md)에 명문화했다.
저장 버전·외부 이벤트 매핑 등 DEC-041 잔여 항목은 협의하며, C는 Spring·FE 준비와 실모델 형식 검증 후 활성화한다.

## 1. 무엇을 바꿨는가

| 개선 | AI 변경 | 기존 호출자 |
| --- | --- | --- |
| 퀴즈 5문항 | 새 통합학습 퀴즈 생성 전용 스키마에서 정확히 5문항 강제 | 기존 5~10문항 수신 계약 안에 들어옴 |
| 페이지별 기본 계획 | 개요 생성에서 모든 페이지의 `suggestQuiz`를 사전 판단, 설명 턴에서 재사용 | 옵션·계획 없으면 기존 런타임 판단 유지 |
| 대화 기반 추가 제안 | 일반·후속 QA의 기존 Planner가 필요 시 퀴즈 제안 | 명시적 capability 없으면 기존 정책 유지 |
| 문항 선전달 | 한 LLM 스트림에서 완성·검증된 문항의 공개 필드만 임시 전달 | capability 또는 NDJSON 없으면 기존 완성본 경로 |

토큰 출력 상한·모델·타임아웃을 낮추지 않았다. 설명·QA 본문을 축약하지 않는다.
기존 저장 퀴즈 `QuizGeneration`의 5~10문항 수용, 별도 시험 초안은 그대로다.
문항별 별도 LLM 호출, 전체 퀴즈를 미리 생성하는 백그라운드 작업은 추가하지 않았다.

## 2. 사전 계획: 섹션 중간의 중요한 페이지도 포함

Spring이 기존 `/internal/ai/outline` 요청에 `includePageQuizPlan: true`를 선택적으로 보낸다.
이 경우 `pages`는 1..totalPages 전체가 빠짐없이 있어야 한다(누락·중복·범위 오류 422).
기존 `xaiFileId`를 함께 주면 첨부 PDF를 사용한다. 없으면 기존 텍스트 근거로 판단한다.
응답의 기존 요약·sections·quizCheckpoints에 다음 필드가 추가된다.

```json
{
  "pageQuizPlan": [
    {"pageNumber": 1, "suggestQuiz": false, "reason": "표지"},
    {"pageNumber": 2, "suggestQuiz": false, "reason": "개념 도입을 위한 맥락"},
    {"pageNumber": 3, "suggestQuiz": true, "reason": "선형 모델의 가정을 독립적으로 점검"}
  ]
}
```

- 모든 페이지가 오름차순으로 정확히 한 번 있어야 한다. 불완전한 출력은 기존 1회 재생성·총예산 안에서 처리한다.
- 페이지 길이나 section 끝인지로 제한하지 않는다. 핵심 가정·모델·공식 해석·예제를 독립적으로 점검할 수 있으면 중간 페이지도 true다.
- **false와 계획 부재는 다르다.** 턴의 `pageQuizDecision` 생략/null은 계획 없음이고, 유효한 항목의 false는 기본 제안하지 않음이다. false를 section/checkpoint 규칙과 OR해서 다시 true로 만들지 않는다.
- `quizCheckpoints`는 누적 출제 범위를 위한 기존 산물로 유지한다. 새 페이지별 제안의 상한·필터가 아니다.
- 옵션을 생략/false로 보내면 `pageQuizPlan` 자체가 응답에 없고 기존 출력 스키마를 사용한다.

Spring은 계획을 자료 버전과 연결해 저장·재검증하고, 현재 페이지 결정 하나만 턴에 보낸다.

```json
{
  "context": {
    "pageQuizDecision": {"pageNumber": 3, "suggestQuiz": true, "reason": "핵심 가정 확인"}
  }
}
```

- `pageNumber != session.currentPage`면 AI 요청 검증 422. reason은 공백 불가·최대 240자, suggestQuiz는 JSON boolean이다.
- AI는 자료 버전·계획의 생성 시점을 알 수 없다. **변경·삭제된 자료의 오래된 계획을 보내지 않는 책임은 Spring에 있다.** 저장 스키마·버전 식별 방식은 Spring 협의 사항이다.
- 계획이 있고 현재 페이지에 텍스트가 있으며 평가·진단·교정·메모리 후보·QA digest·최근 USER 메시지가 없는 설명 턴은 Plan을 합성한다. 합성 Plan도 기존 Policy를 거친다. 설명 호출만 1회이며 PDF 상세 근거는 설명 에이전트가 계속 읽는다.
- 위 학습자 신호가 있으면 Planner를 유지한다. 단, 페이지별 계획이 있으므로 Planner에는 PDF를 다시 첨부하지 않고 축약 문맥과 기본 결정을 보내 학습자별 보정·메모리 판단만 맡긴다. 실행 설명에는 PDF가 계속 첨부된다.
- 사전 계획이 없으면 기존 동작 그대로다. 구자료의 퀴즈를 갑자기 없애지 않는다. 빈 페이지 고정 안내도 유지한다.
- `NOT_EXPLAINED`가 아닌 재설명에서는 기본 퀴즈 제안을 반복하지 않는다. QA의 학습자별 추가 제안은 별개다.

따라서 **모든 설명이 무조건 1회 호출로 바뀐다고 볼 수 없다.** 평가 이력이 남아 있는 실제 세션은 경량 Planner+설명 2회일 수 있다. 호출 경로는 `planSource`·`plannerAttempts` 로그로 구분한다.

## 3. 일반 질문·후속 질문의 추가 퀴즈

Spring이 지원 준비가 된 요청에만 최상위 capability를 보낸다.

```json
{"capabilities":{"qaQuizProposal":true}}
```

- `USER_QUESTION`의 기존 Planner에서 답변 후 지식 점검이 유익한지 함께 판단한다. 추가 판단 전용 LLM 호출은 없다.
- 유효한 순서는 `[ANSWER_QUESTION]` 또는 `[ANSWER_QUESTION, PROMPT_BINARY_DECISION]`이며 메모리 도구는 기존 규칙 안에서 가능하다.
- `pageQuizDecision.suggestQuiz=false`여도 학습자의 혼동·후속 질문에 근거한 제안은 가능하다. 매 질문·단순 조회·리다이렉트·노트 요청에 무조건 제안하지 않도록 지시한다.
- `includeCurrentPage=false`이거나 현재 페이지 근거가 없으면 추가 퀴즈 제안은 허용하지 않는다. 페이지 비첨부 자유주제 퀴즈는 이번 범위 밖이다.
- 퀴즈와 노트를 동시에 제안한 Plan은 퀴즈 제안만 응답한다. 실제 퀴즈는 동의와 유형 선택 이후 `QUIZ_TYPE_SELECTED`에서 생성한다.
- 응답 위젯은 기존 exact 형태다: `{"type":"BINARY_DECISION","content":"퀴즈를 진행할까요?","yesEvent":"SHOW_QUIZ_TYPE_SELECT","noEvent":"WAIT"}`.
- 옵션 없으면 기존 QA 제안 금지 정책 그대로다. capability 두 필드는 JSON boolean이며 문자열/숫자는 422다.

Spring의 추가 작업:

1. QA/FOLLOW_UP 완료에서도 위 exact 위젯을 수용하도록 event-goal-shape/allowlist 정합화. `turnGoal` 자유 문자열을 enum처럼 검사하지 않는다.
2. 미제출 퀴즈·진단 진행·이미 거절한 동일 제안·요청 소유권 등 실제 세션 상태를 확인한다. AI 스냅샷에는 이 모든 정보가 없으므로 AI만으로 보장할 수 없다. 거절 반복 방지는 프롬프트만 신뢰하지 않는다.
3. 동의한 추가 점검 주제를 다음 생성 요청의 선택 `context.quizContext.learningFocus`(1~1,000자, 공백 불가)에 전달할 수 있다. 기존 coverage/pages와 함께 보낸다. AI는 해당 주제 중심으로 생성하되 coverage 밖으로 넓히지 않는다. 주제 추출·보관·동의 연결은 Spring 후속이다.

추가 LLM **호출 수**는 늘지 않지만 Planner 출력과 위젯 판단 토큰은 소폭 늘 수 있다. “추가 지연 0초”를 보장한 것은 아니다.

## 4. 문항 선전달: 외부 표시 준비가 된 뒤 활성화

Spring이 `Accept: application/x-ndjson`과 다음 옵션을 함께 보낸다.

```json
{"capabilities":{"quizQuestionStream":true}}
```

`QUIZ_TYPE_SELECTED`에만 허용한다(다른 이벤트는 422). JSON 요청에서는 옵션이 있어도 기존 complete_json 완성본 경로다.
기존 여섯 이벤트는 유지하고, 이 명시적 opt-in에서만 `quiz_question`을 추가한다.

```json
{
  "type": "quiz_question",
  "generationId": "generation-example",
  "quizType": "MCQ",
  "title": "선형 모델 점검",
  "coverage": {"startPage": 3, "endPage": 3},
  "questionIndex": 1,
  "questionCount": 5,
  "provisional": true,
  "question": {
    "questionId": "q1",
    "questionText": "선형 모델에서 기울기는 무엇을 나타내나요?",
    "points": 10,
    "choices": [{"choiceId": "a", "text": "입력 변화에 따른 예측값 변화"}, {"choiceId": "b", "text": "항상 고정된 예측값"}]
  }
}
```

- MCQ만 choices가 있으며 OX/SHORT/ESSAY에서는 생략한다. index는 1..5다.
- 정답·해설·referenceAnswer·modelAnswer·gradingCriteria·rubric·usage는 미리보기에 없다. 공개 DTO를 명시적으로 새로 만들어 필드를 제한한다.
- **원시 JSON/텍스트 delta는 외부로 중계하지 않는다.** 한 문항의 private 필드까지 구조 검증한 뒤 공개 부분만 발행한다. 메타데이터의 범위·유형도 선검증한다.
- 공급자에는 기존 텍스트 스트림 경로로 JSON 객체 하나를 요청한다. 메타데이터 먼저, questions 마지막 순서가 필요하며 위반·코드펜스·중복 키·불완전 JSON은 실패다. 마지막에는 전체 퀴즈를 다시 검증한다. 파서 버퍼는 총 1,048,576자로 제한한다.
- 현재 프로토타입은 provider JSON-schema 강제 스트리밍이 아닌 프롬프트+결정적 파서다. 기존 비스트리밍 structured-output 경로보다 출력 형식 준수 위험이 있다. **실 xAI 4유형 품질·실패율 검증 전 활성화 금지.**
- 최종 `completed.result.quiz`에는 기존처럼 Spring 저장용 private 정답이 있다. Spring이 이 내부 DTO를 그대로 FE에 전달하면 안 된다.
- usage는 최종 completed에 한 번만 있다. 문항별 호출/재생성은 없다. 기존 bridge의 첫 응답 전 네트워크 재시도·전체 deadline·취소 정리를 재사용한다.
- 오류는 terminal error, 연결 중단은 취소다. 부분 퀴즈 성공·statePatch·quizId는 만들지 않는다. 미리보기는 삭제해야 한다.

Spring/FE의 추가 작업:

1. Spring: opt-in NDJSON parser에서 새 이벤트를 안전한 공개 DTO로 받고, turn/requestId와 연결해 외부 이벤트로 전달한다. 외부 SSE 이벤트명·필드·재연결 규칙은 팀 합의 후 문서화한다.
2. FE: 공개 문항을 생성 중 미리보기로 표시한다. **최종 검증·DB 저장·정본 quizId 확인 전에는 제출 금지.** 미리 답안 입력을 허용할지는 별도 UX 합의가 필요하다.
3. Spring: 5문항 완성본·식별자·범위·미리보기와의 일치 여부 검증 후 기존 트랜잭션으로 한 번 저장한다. 취소 때 텍스트 일부를 저장하는 기존 기능을 부분 퀴즈에 적용하지 않는다.
4. FE: error/취소/연결 유실 시 임시 문항을 폐기하고 서버의 완료 결과가 있는지 조회한다. generationId는 quizId가 아니다. 새 POST를 자동 반복하지 않는다.
5. 기존 completed의 private 필드 차단, 중복 이벤트·늦은 이벤트·다른 턴 혼입 차단을 회귀 검증한다.

이 변경은 “HTTP 요청을 종료한 뒤 서버에서 영속 백그라운드 작업을 계속 실행”하는 기능이 아니다. **진행 중인 하나의 비동기 스트림에서 완성된 문항을 먼저 전달**한다. 재접속 후 작업 이어받기·백그라운드 큐는 구현하지 않았다.

## 5. 활성화 순서와 검증

1. AI 내부 필드·null 의미·공개 필드는 정본 계약의 예시를 따른다. 팀은 DEC-041의 저장 버전·상태 경계·외부 스트림 매핑 등 잔여 항목을 검토한다.
2. AI 선택 필드 수용 코드 배포. 기존 호출자는 옵션을 보내지 않아 기존 경로 유지(새 생성 문항 수만 5개).
3. Spring 계획 저장/버전 검증/스냅샷 전달과 QA 위젯 수용을 구현한다. FE 미리보기 처리 완료 후에만 `quizQuestionStream`을 보낸다.
4. 기존 자료는 제한된 백필로 계획을 만든다. 계획 생성량과 비용·90초 개요 예산 내 완주율을 확인한다. 실패 자료는 기존 런타임 판단 유지.
5. 동일 PDF·페이지·질문으로 전후 비교: 설명 첫 본문/완료, quiz 첫 문항/전체 완료, provider 호출 수·cached input·토큰·비용·오류율. `firstQuestionMs`는 서버 준비 시각이며 FE 표시 시각이 아니다.

오프라인 검사:

- 기존 동작과 5문항 전용 생성 스키마, 기존 저장 6문항·시험 초안 호환.
- 모든 페이지 계획의 누락·중복·순서, false/부재 구분, 중간 페이지 true, 재설명 중복 금지.
- 학습자 신호 있으면 Planner 유지 + PDF 미첨부, QA/FOLLOW_UP 제안의 호출 수·Policy·위젯 중복 억제.
- 4유형 공개 미리보기·청크 경계/이스케이프·마지막 전체 검증·정답 비노출·최종 usage 1회.
- 첫 문항 뒤 provider 지연·heartbeat·오류·취소 시 하위 iterator 정리, 최종 결과 미확정.
- 전체 pytest/ruff/mypy. 실 xAI 호출·실속도 개선 및 학습 품질은 이 테스트로 증명되지 않는다.

## 6. 코드리뷰 메모와 잔여 위험

- 수정한 결함: 새 테스트 fixture의 불필요한 event payload 필드로 인한 422, 10문항 기대값, 계획이 있는 fileless 자료의 adaptive 판단 누락, 재설명 중복 제안, 무한대 숫자·중복 JSON 키, source dirty 상태에 의존하던 benchmark 단위 테스트.
- benchmark 실행기의 실제 dirty-tree 거부는 유지했고 별도 회귀 테스트로 고정했다. 테스트를 통과시키려고 커밋하거나 실측 게이트를 약화시키지 않았다.
- 구조 검증은 페이지의 교육적 중요성이나 정답의 의미적 정확성을 보장하지 않는다. Optimization/ML 실제 자료로 중요 페이지 누락·과도한 제안·5문항 품질을 확인해야 한다.
- 미리보기는 첫 **완성된 문항**을 기다리므로 모델의 첫 토큰·파일 검색 지연 자체를 없애지는 않는다. 5문항 완성 시간도 실측 전 개선율을 단정할 수 없다.
- 스냅샷에 과거 평가·QA가 남아 있으면 설명 Planner가 유지된다. 더 과감한 생략은 신호 유효기간/페이지 연관성을 Spring과 합의한 뒤 해야 한다.
- Spring·FE 미연동 상태에서는 새 옵션을 보내지 않는다. AI 코드를 먼저 dev에 배포해도 신규 연동 경로는 기본 비활성을 유지한다. 운영 배포·옵션 활성화는 별도 게이트다.

## 7. 이번 검증 결과

- 기준선 844개 → 변경 후 **pytest 917 passed (5.82s)**. 테스트 실행 시간이며 서비스 지연 실측이 아니다.
- `uv run --offline ruff check src tests demo.py`: PASS.
- `uv run --offline ruff format --check src tests demo.py`: PASS (143 files).
- `uv run --offline mypy src tests demo.py`: PASS (142 source files).
- `uv run --offline pytest -q --tb=short`: PASS. 실제 LLM 호출 없이 FakeLlm·기존 provider mock 사용.
- `git diff --check`: PASS. Spring/FE 코드·의존성·모델 설정·토큰 상한·timeout 설정 무변경.
- 브랜치: `codex/page-quiz-plan-and-streaming`. 원래 작업 폴더의 변경을 보존하기 위해 별도 worktree에서 작업했다.
