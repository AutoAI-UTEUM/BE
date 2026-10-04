# FE231 설정 소비자와 미합의 fragment 경계 (2026-10-04)

**역사적 시점:** 아래 미지원·답변 대기 기록은 FE231 `21f4ad2d`의 검토 결과다. 후속 FE232 `5592042c`에서 fragment-only 구현과 회신을 확인했고 최신 source/소비자 결과는 [FE-PR232-FRAGMENT.md](FE-PR232-FRAGMENT.md)에 분리했다. 이 문서의 당시 결과와 metadata를 새 FE에 적용하지 않는다. BE/메일 전환·별도 활성화 인수는 여전히 별도다.

12:41 UTC 작업 재개 지시에 따라 기존 가입·이메일 검사를 반복 확장하는 대신 설정의 실패 경계를 추가했다. 선택 BE base는 `e949fbecbe8f6cd414ee9f18605d1a15dca2fa6e`, main-service tree는 동일한 `20a4c0c7c55b44d8b792ce282e622eddb4d573d9`다. 읽기 전용 FE는 [PR231](https://github.com/AutoAI-UTEUM/FE/pull/231) merge/보고된 DEV head `21f4ad2d30f13bafd05bfcc289515c2caac98810`이며 PR head는 `3a9abe566d3926da69404192b35708a269c22ef6`다. 실제 DEV/API는 조회하지 않았다.

이 단위는 `feature/479-fe231-settings-contract`에서 `docs/qa/fe-auth-contract/`만 변경한다. FE231의 기존 실패→재시도·중복 클릭·늦은 성공 GET/PATCH·unmount 테스트는 읽고 재사용하며 FE 전체 테스트를 다시 실행한 것으로 표시하지 않는다. 기존 [FE229 계약 검증](FE-PR229-REVIEW.md)과 [BE API 명세](../../api-spec.md)를 재사용한다.

## 설정의 실제 계약

| 항목 | 현재 BE / FE 소비자 |
| --- | --- |
| 조회 | 본인 Bearer `GET /api/users/me/preferences`, 공통 envelope의 data에 `newMaterialNotification:boolean`, `studyReminder:boolean`, `aiAnswerStyle:CONCISE/NORMAL/DETAILED` 전체 snapshot. |
| 수정 | 본인 Bearer `PATCH /api/users/me/preferences`. BE는 nullable **부분 수정**이며 null/생략 필드는 유지한다. 하나 이상 non-null이 필요하고 `{}`/all-null은 VALIDATION_FAILED400, 알 수 없는 enum은 MALFORMED_REQUEST400. 성공은 전체 snapshot이다. |
| FE 화면 | 확인한 현재 owner의 GET/PATCH snapshot에 변경 field를 합쳐 **전체3필드**를 보낸다. 부분 수정의 wire 계약과 호환되지만 다른 탭/불확실한 전송 결과의 충돌 해결 계약은 아니다. |
| 기본값 | BE의 미설정 기본은 true/true/NORMAL. FE 화면의 초기 표시값 true/false/NORMAL은 확인한 서버 snapshot이 아니며 편집 허가 근거로 사용하지 않는다. |
| 이메일/수신 동의 | 이 세 환경설정은 emailVerification/verifiedAt, learningEmailOptIn, 정책/보호자/나이 승인이 아니다. 설정 관리 경로는 신규 이메일 대기 사용자의 본인 관리 예외에 포함되며 업무 gate를 끄지 않는다. |
| 취소 | FE231은 GET/PATCH에 AbortSignal을 전달하고 owner/generation/lock으로 화면 적용을 제한한다. 이미 도착한 서버 쓰기를 취소하거나 DB rollback을 보장하는 계약은 아니다. |

근거는 [UserController](../../../main-service/src/main/java/io/edupilot/user/UserController.java), [UpdatePreferencesRequest](../../../main-service/src/main/java/io/edupilot/user/dto/UpdatePreferencesRequest.java), [UserService](../../../main-service/src/main/java/io/edupilot/user/UserService.java), [User](../../../main-service/src/main/java/io/edupilot/user/User.java), [기존 UserServiceTest](../../../main-service/src/test/java/io/edupilot/user/UserServiceTest.java)의 `preferencesReturnDefaultsAndSupportPartialUpdates`/`preferencesRejectEmptyPatch`다. 이 Java 테스트는 이번 단위에서 실행하지 않았다. 실제 FE [repository](https://github.com/AutoAI-UTEUM/FE/blob/21f4ad2d30f13bafd05bfcc289515c2caac98810/src/features/auth/userSettingsRepository.ts)와 [SettingsPage](https://github.com/AutoAI-UTEUM/FE/blob/21f4ad2d30f13bafd05bfcc289515c2caac98810/src/app/pages/SettingsPage.tsx)를 대조했다.

## 새로 채운 빈 검사와 남은 경계

- BE nullable 부분 PATCH·전체 응답·false 보존·enum 오류를 실제 FE repository와 합성 envelope로 대조했다. 조회 실패는 오류로 남고 기본값이나 PATCH로 바뀌지 않는다.
- 실제 `savePreferences` closure body를 캡처해 확인한 non-default snapshot의 필드 보존, 서버가 반환한 최종 snapshot 반영, PATCH 실패 뒤 화면 rollback 및 다음 다른 field 저장을 실행했다. 기존 FE UI 테스트에 없는 저장 실패 경로다.
- 계정 전환 및 같은 owner의 새 generation 이후 **늦은 실패**가 새 owner의 화면/lock/toast를 바꾸지 않는 것과 취소 뒤 늦은 rejection을 실행했다. FE231 기존 늦은 성공 테스트와 구분한다.
- NETWORK_ERROR 이후 FE는 화면/확인 snapshot만 이전 값으로 돌린다. 다음 전체 payload에 옛 non-default 값이 들어가는 것을 재현했다. 실제 BE commit 여부는 관찰·시뮬레이션하지 않았다. 응답 유실·취소 후의 저장 성공 여부가 불명확하면 서버 재조회로 기준을 다시 확인할지, 부분 wire PATCH로 제한할지, 충돌 계약을 둘지는 FE/BE 담당의 후속 검토다. 현재 전체 payload/AbortSignal만으로 원격 rollback 또는 동시 탭 보존을 주장하지 않는다.
- 성공 envelope의 data가 부분 객체/잘못된 enum/null이어도 현재 repository는 shape 검증을 하지 않고 그대로 반환한다. 이는 의도적으로 계약을 어긴 합성 응답의 경계 검사이며 현재 BE 정상 응답이 부분/잘못된 enum이라는 발견은 아니다. malformed2xx를 확인 snapshot으로 인정하지 않는 FE 검증 여부를 검토할 수 있다.
- **보호 범위는 preferences다.** 프로필 `saveProfile`은 다른 closure이며 preferences owner/generation/AbortSignal 검사를 쓰지 않는다. 캡처한 프로필 저장의 지연 응답이 원 owner 결과를 updateUser callback으로 전달하는 경계를 확인했다. 실제 AuthProvider/브라우저의 계정 전환은 실행하지 않았으며 FE 담당에게 그 범위의 별도 검토를 요청한다. BE는 요청의 인증된 userId를 사용한다. 이 결과로 다른 계정의 DB를 수정했다고 판단하지 않는다.

현재 계약에 맞는 GET/PATCH method·필드·envelope 소비의 새로운 BE 런타임 결함은 확정하지 않았다. FE/공통 코드·기존 테스트·DB/migration/defaults는 수정하지 않는다. 이 문서는 기존 FE231 범위의 완료를 부정하거나 모든 설정 흐름이 보호된 것으로 확대하지 않는다.

## 이메일 fragment — 담당자 답변 전 제안

`settings-fragment-review.json`의 fragmentAgreement는 **pending**이다. 공유된 안의 담당자 답변이 아직 없으며 전송 형식·전환/호환 기간·혼합 source 처리·최종 SHA와 FE 활성화는 합의되지 않았다. [현재 BE](../../../main-service/src/main/java/io/edupilot/auth/EmailVerificationService.java)는 `/verify-email?token=` 링크를 만든다. FE231의 [early script](https://github.com/AutoAI-UTEUM/FE/blob/21f4ad2d30f13bafd05bfcc289515c2caac98810/index.html)와 [client navigation parser](https://github.com/AutoAI-UTEUM/FE/blob/21f4ad2d30f13bafd05bfcc289515c2caac98810/src/features/auth/emailLinkToken.ts)는 **query만 읽고 hash는 지운다**. 따라서 BE 링크만 fragment로 바꾸는 작업은 현재 FE에서 token을 잃는다.

| 실패/경계 case | 현재 source를 실행한 결과 | 합의 후 채울 인수안 |
| --- | --- | --- |
| fragment-only43자 | token=null, URL scrub, 네트워크 호출 없음 | canonical fragment 문법 및 bootstrap/client navigation 양쪽 지원·명시적 클릭 POST. 현재 지원 완료로 표시하지 않는다. |
| queryA + 다른 fragmentB | queryA를 선택하고 양쪽 URL 흔적을 지움 | source 우선순위 또는 혼합 source 거절을 합의한다. 현 우선순위를 승인된 정책으로 쓰지 않는다. |
| 중복/잘못된 query + 유효 fragment | fragment로 fallback하지 않음 | 중복/형식 실패의 거절·사용자 재요청 안내를 합의한다. |
| 중복/인코딩된 fragment | fragment token 지원 없음 | 중복·빈/잘못된 길이·인코딩 처리 실패 case를 canonical 문법에 맞춰 확정한다. |
| OFF·이탈·새로고침·재발급·만료·replay | fragment parser 실행은 기능 ON/실확인의 증거가 아님 | 기존 OFF 유지·임시 메모리·명시적 POST·본인 상태 재조회 및 기존 BE 오류 계약을 보존하는 인수안을 확정한다. |

새 fragment 검사는 현재 소스의 경계 **재현**이다. 미래 지원을 구현하거나 합의된 기대값으로 통과시킨 검사가 아니다. 실제 navigation/network/접근 로그·메일 전달·브라우저 Referer 인수는 실행하지 않는다. Nginx·로그는 메인 통합 담당의 별도 범위며 공개 상세 댓글·Discord 전송도 하지 않는다.

## 실행과 한계

```powershell
$env:FE_AUTH_SETTINGS_SOURCE_ROOT = 'C:\Users\russe\Documents\Codex\2026-10-04\task\FE-pr231-readonly'
node --disable-warning=ExperimentalWarning --test --test-reporter=spec docs/qa/fe-auth-contract/settings-contract.test.mjs docs/qa/fe-auth-contract/fragment-boundary.test.mjs
```

새 검사20개는 settings15 + fragment5다. `settings-harness.mjs`는 실제 FE repository/API client와 캡처한 저장 함수 body를 VM에서 실행한다. React의 render/effect/계정 전환 자체를 실행하지 않고 refs·setter·지연 응답·window/document는 합성 stand-in이다. profile 검사도 callback 관찰 범위다. BE 부분 수정은 DTO/service/entity/기존 테스트 source로 대조했으며 Java/MySQL 실행이 아니다. 최종 실행 숫자·변경 범위는 [EVIDENCE.md](EVIDENCE.md)에 기록한다.

실API·학생 데이터·실Google/메일/SMS/유료AI·실DB·FE 전체 quality/브라우저·배포/활성화는 미실행이다. 이전 90/90·50/50 결과나 FE231의1013 테스트 보고를 이 새 실행의 결과로 합산하지 않는다.
