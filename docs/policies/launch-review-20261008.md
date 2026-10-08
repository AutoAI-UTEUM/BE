# 아동 포함 출시 정책 초안의 게시 전 검토표 — 2026-10-08

브랜치 한 줄: `feature/479-policy-review-drafts` — 승인된 아동 포함·팀 검토 범위에 정책 초안을 맞추고 공식 법령·미정 결정·FE/메일 운영 증거를 구분한다. Related to #415, #478, #479, #519, #524, #526, #540.

**검토용 문서이며 정책 채택·법률 자문 완료·게시/시행/배포 승인이 아니다.** 런타임 기준은 PR541 exact `58df7c95f11fa10c3b70a97e4a6fa9ecd152ed2e`다. [약관1.0](terms-1.0-draft.md)·[처리방침1.0](privacy-1.0-draft.md)의 아동 가입 제외 문구와 [과거 골격](../policy-draft.md)의 미성년자 해당 없음·메일 본문 비저장 설명을 현 범위와 맞췄다. runtime·migration·기본 설정과 실제 정책 문서는 바꾸지 않았다.

## 기존 결정과 별도 검토

| 이미 승인/완료된 사실 | 적용 범위와 유지할 경계 |
| --- | --- |
| 만14세미만 포함 첫 출시·업체 없는 TEAM_REVIEW | 미정 정책이 해결되기 전 비활성/확인 전 제한 유지. 웹 체크·링크·메일 도착만으로 승인하지 않음 |
| KST 현재 연도−출생 연도≤14 대상/≥15 비대상 | 보수적인 제품 조건. 법정 만 나이·민법 미성년 계약 요건과 구분; 기존 cohort 예외 유지 |
| 아동 제공 미확인/반려 보호자 성명·연락처 최초 수집부터 최대5일 | 재발급·보완으로 연장하지 않음. 법정 일률 기간이나 승인 증거 기간으로 설명하지 않음 |
| 일반 소유자 요청 PDF·렌더30일 | 그 삭제 목적만의 결정. 탈퇴·backup·guardian·메일·증거에 자동 적용하지 않음 |
| 같은 지정 회신함/검토 계정, 기존 no-reply@uteum.com 발신자 | 주소·ID는 비공개 참조 유지. 새 계정·승격·Reply-To 연결을 자동 생성하지 않음 |
| DEV main/ai 로그 각각14일·지정 계정 ACTIVE ADMIN/API readback | [사용자 완료 보고](../qa/operations-acceptance-20261007.md) 보존. 재조회/재승격 요청 없음; 다른 환경/기록과 현재 reviewer binding·정책 activation은 별도 |
| 성인 LOCAL LEARNER 합성1계정·승인 exact plus 주소·서비스 메일4건/각1-ID | SDK identity1/TEST1 예산은 소진. 이 문서가 서버 변경/시험 운영창을 승인하지 않음 |

## 최신 공식 출처와 해석의 한계

2026-10-08 공식 현재 조문을 대조했다. 개인정보 보호법은 **2026-09-11 시행 법률 제21445호**, 시행령은 **2026-09-11 시행 대통령령 제36671호**다. 민법은 **2026-03-17 시행 법률 제21454호**, 약관법 연계조문은 **2024-08-07 시행 법률 제20239호** 표시를 확인했다. 아래는 초안 검토 기준이며 실제 요건 충족 판정이 아니다.

| 공식 근거 | 초안에 반영할 구분 |
| --- | --- |
| [법 제15조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0015&lsiSeq=283839&urlMode=lsScJoRltInfoR)·[제16조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0016&lsiSeq=283839&urlMode=lsScJoRltInfoR)·[제22조](https://law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0022&lsiSeq=283839&urlMode=lsScJoRltInfoR) | 동의·비동의 처리의 목적/항목/근거를 구분하고 최소 수집 필요를 검토. PRIVACY 체크/약관 허용 문장만으로 모든 개인정보 동의가 성립하지 않음 |
| [법 제22조의2](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=02&joNo=0022&lsiSeq=283839&urlMode=lsScJoRltInfoR)·[영 제17조의2](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=02&joNo=0017&lsiSeq=289537&urlMode=lsScJoRltInfoR) | 동의가 필요한 아동 처리의 법정대리인 동의·확인. 최소 보호자 성명/연락처 직접 수집 예외는 아동 계정·DOB 전체 수집 근거가 아님 |
| [영 제17조](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lspttninfSeq=182173)·[제17조의2](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=02&joNo=0017&lsiSeq=289537&urlMode=lsScJoRltInfoR) | 명확한 동의와 아동이 이해할 설명을 검토. 이메일 동의 내용 발신/명시 회신과 주소 통제·도착을 구분; 관계 확인 충분성 미판정 |
| [법 제21조](https://law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0021&lsiSeq=283839&urlMode=lsScJoRltInfoR)·[제30조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0030&lsiSeq=283839&urlMode=lsScJoRltInfoR) | 목적 종료/불필요 정보 파기와 법정 별도 보존을 구분. 논리 탈퇴·삭제 원장·DB 정리는 실제 파일/외부 사본 파기 완료가 아님 |
| [법 제28조의8](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=08&joNo=0028&lsiSeq=283839&urlMode=lsScJoRltInfoR) | 국외 제공/위탁/보관의 유형별 근거, 계약·고지/동의 요건과 실제 국가/수신자/항목/기간/거부 영향 검토 |
| [민법 제4조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0004&lsiSeq=284415&urlMode=lsScJoRltInfoR)·[제5조](https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=00&joNo=0005&lsiSeq=284415&urlMode=lsScJoRltInfoR) | 성년19세 및 미성년 법률행위의 동의/예외/취소 문제는 개인정보 만14세·제품 연도 기준과 별개 |
| [약관법 제3조](https://www.law.go.kr/lsLinkCommonInfo.do?lsJoLnkSeq=1029708921)·[제7·9·10·12·13·14조](https://law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1025032399) | 읽기 쉬운 명시/설명, 면책·일방 변경/중지·부작위 동의·관할 등을 검토. 현재 약관에는 침묵/계속사용 자동동의 조항 없음; 새로 추가하지 않음 |
| [PIPC 현재 처리방침 작성지침(2026.4)](https://pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=D010030000.Updated&nttId=12018) | 2026-04-23 현재 안내서 게시글·첨부 제목 확인. PDF 본문 전체를 검토한 것으로 표시하지 않음 |

법무/개인정보 담당자의 최종 검토와 실제 계약·운영 증거는 남아 있다. 약관 제12조의 운영자 고의·과실 책임/법정 권리 보존 문구를 유지했고, 제6조의 교육 판단 책임이 운영자 책임 전부 전가로 해석되지 않는지 검토한다. 약관7/30일·방침30일 사전 고지 문구도 미게시 초안 제안이며 모든 변경에 법적 충분한 기간이라고 확정하지 않는다. 자동 채점의 실제 영향과 적용되는 자동화된 결정·설명/이의/인적 개입 요건은 별도 검토 대상이다.

## 사용자·정책 담당자에게 남은 검토 묶음

| 항목 | 결정/증거가 필요한 값 | 현재 상태 |
| --- | --- | --- |
| P01 게시 metadata와 책임 창구 | 운영자 법적 명칭·대표/사업자/주소·문의·개인정보 담당, 유형별 제목/version/effectiveAt/requiresConsent, 게시 담당·FE 노출/재동의 일정 | TERMS1.0/PRIVACY1.0은 파일명 후보. 시행일/필수 여부 미정;0.9는 비필수 placeholder |
| P02 가입 단계 처리 | 성인/아동 각각 동의 전 이메일·DOB·인증/계정 항목의 최소 범위·수집 시점·처리 근거와 그 입증 | 근거 적용 가능성 미판정. 보호자 연락처 수집 예외로 포괄 허용하지 않음 |
| P03 실제 관계 확인 | 현재 신청 차수의 명시 회신·관계 판단 최소 자료·보완/반려 기준·수단·외부 원본 미보관 범위 | 미정. 신분증/가족관계증명서 원본 일괄 수집을 새로 채택하지 않음 |
| P04 고지·동의 범주 | 아동 쉬운 안내, SERVICE와 선택 EXTERNAL_AI의 실제 항목/목적/거부 영향, 법상 동의 범주, noticeVersion/digest/configurationDigest | 운영 최종 문구/버전 미정. 웹 의사 표시·일반 정책 체크를 최종 guardian 승인으로 표시하지 않음 |
| P05 기간·파기 | 승인 유효기간·증거 최대 보유·목적 종료 시 조기 정리, 회신함/첨부/제공자/backup 담당·기한·방법·실패 추적 | 링크1h/신청4d/승인90d/증거90d는 미채택 제안. 승인된5일·일반PDF30일과 별개 |
| P06 계정 탈퇴와 기록 | 항목별 잔존 근거·기간·분리보관, 학습/공동 제출·평가와 실제 파일/사본 정리, 강사 소유 강의실 종료/이전 | 미정. 교육기록·분쟁이라는 일반 문구만으로 보존하지 않음; 물리 삭제OFF 유지 |
| P07 제공자·국외 처리 | 실제 AWS/메일/Google/xAI 법인·국가/리전/연락처·항목·보유/API/Files/ZDR·삭제/거부 조건과 이전 유형별 근거 | 실제 계약/계정 자료 미검토. xAI30일/ZDR·미국·계약 법인을 확정 문구에서 제거하고 TBD로 둠 |
| P08 미성년 이용계약·약관 | 민법상 미성년 계약 동의/예외·취소, 고지/변경·이용 제한/종료·AI 책임·자동화 결과 이의 기준 | 별도 검토. 제품 gate 확대/변경이나 새 책임 면제를 이 문서로 채택하지 않음 |

확정 전에는 `[[...]]`를 지우거나 합성 fixture 숫자를 운영값으로 채우지 않는다. 검토 내용이 실제 코드의 제한·수집·파기 범위와 맞지 않으면 게시에 앞서 독립 구현 이슈/검증으로 해결한다. 이미 승인된 아동 포함/TEAM/KST·계정/발신자·5일/30일·메일 예산/주소 선택은 다시 묻지 않는다.

## FE·배포·시험 준비와 분리한 완료 조건

PR541의 설정ON + 필수 문서0개는 `SIGNUP_POLICY_NOT_READY503`, 준비 후 동의 오류는 `POLICY_CONSENT_REQUIRED400`이다. 기술 계약은 한 종류만 필수여도 그 종류의 동의를 허용하며, 두 문서 모두 필수 강제는 별도 범위다. 실제 정책 게시·effectiveAt 도달·FE 선택과 기록을 확인해야 하며, 이 초안 수정은 운영 정책 등록이 아니다.

[FE 실제 빌드·readiness 준비](../qa/fe-auth-contract/FE-READINESS-20261008.md)는 소스/mock/served artifact/실제 UI 증거를 구분한다. [메일 시험 중단 경로 검토](../qa/mail-trial-interruption-review-20261008.md)는 기존 안전한 경로와 미채택 운영 대안을 구분한다. 현재9회 재생성 승인안의 운영 제어·비발송 상태를 임의로 바꾸지 않는다.

[DEV 전환·복구 인계 양식](../qa/dev-deployment-recovery-handoff-20261008.md)은 실제 후보 image/유효 설정·전체worker·schema/quota 및 private 복구 근거를 한 운영 원장으로 준비한다. 미조회 값은 null/NOT_ATTESTED이며 현재 실행을 승인하지 않는다.

- [ ] P01~P08 중 게시/활성화에 필요한 항목을 담당자가 검토·승인하고 문구/metadata와 대조
- [ ] 실제 정책/FE artifact·정상 UI·운영 고지/관계 확인/파기 책임을 확인
- [ ] 별도 승인된 DEV 전환·private 복구 지점과 effective Spring/worker/배포 후 quota 조건 충족
- [ ] 정상 경로 인수와 실제 수신/파기/복구 결과를 mock·source·계획 결과와 구분

이번 후속은 문서 준비다. 서버/DB/실메일·운영 정책 게시·FE 변경/회신·배포를 수행하지 않았으며, 오늘 별도 승인 경로로 진행 중인 FE 회신을 중복 전송하지 않는다.
