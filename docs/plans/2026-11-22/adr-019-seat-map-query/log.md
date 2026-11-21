# log — ADR-019 좌석 맵 조회와 실제 상태 불일치 (Q5)

> 명세: `requirement-spec.md`(합의 2026-11-22 · SPEC=1 · auto) · 브랜치 `feat/adr-019-seat-map-query`(main 831f65edb에서 · 로컬)

| 시각(KST) | 사건 | 결과 |
|-----------|------|------|
| 11-22 | 사용자 "전부 마무리하고 19로 가자" → 하네스 PR #9 · #10 병합 · 배포 · smoke 통과 · worktree 정리 → N00e ③ 기동 검사 구조는 건너뛰고(후보로 남김) ④ ADR-019 착수 · 사전 확인: `SeatMapController`(GET /api/schedules/{id}/seats · 캐시 없음 · 1만 석 한 문장 — `SeatMapQuery` 만료 방식별 SQL) · ADR-014 §가정 9(좌석 맵 초당 20번만 잼) · ADR-018 Q/A 8(캐시는 ADR-019 몫) | 작업 폴더 생성 · 인터뷰 |
| 11-22 04:0x | 인터뷰 3회(11문항 — `decisions.md`) · 상태 표시: 사용자 질문(HELD를 합쳐 보여야 하나)에 답(status 한 칸 · 한 문장) → 두 상태 · 값 이름 AVAILABLE/UNAVAILABLE → 사용자 재고("true/false … 응답관점") → 메인 권고 `available` bool로 명세 반영 · 번호 이동 = ADR-020으로 끼움(번호만 · 닫힌 ADR 예외) · 사전 grep: 좌석 상태 쓰기 15곳(9파일) · lazy/derived 시간 만료는 쓰기 없음 → S5(event는 sweep 전용) · 코드에 ADR-020 · 021 참조 없음(docs/adr 59줄 · README · NEXT) | 명세 초안 · 합의 대기 |
| 11-22 04:1x | 명세 합의("합의, auto로 진행") · set-state spec-approved · mode auto · task 01: ADR-020 → 021 · 021 → 022 `git mv` · 참조 치환(`> 번호 이동` 줄 제외 · 바뀐 줄 ADR 9파일 39 + README 4 + NEXT 6) · 바뀐 ADR에 11차 머리줄 · README 표 020 행 · 질문 5 → ADR-019, 020 · 새 ADR-020(가설) · ADR-019 설계 절 · 낱말 표 검색 1건(대조표 → 점검표) · 링크 검사(깨짐 0 — ADR-008 코드 표기 오탐 1) · 커밋 4a513f36e(직전 작업 decisions) · 16a6927bb | task 01 완료 |
| 11-22 04:2x | task 02 재현 스모크(가정 1 · PC · 임시 postgres:16 127.0.0.1:25433 · bootJar(16a6927bb 트리 — 코드는 831f65edb와 같음) · loadtest 프로필 · port 18119 · `seat.compensation.mode=none`(PG 없음 — 첫 기동은 보상 검사가 거부: 의도대로) · 캐시 없음 · 시드 1회차 300석 · 스크립트 scratchpad/adr019/phantom_smoke.py): 100명이 같은 순간 맵 읽기 → 앞 60석 중 맵에서 AVAILABLE인 좌석 하나를 골라 생각 시간 뒤 선점 · 생각 0ms: 201 63 · 409 SEAT_NOT_AVAILABLE 37(37%) · 0~500ms: 59 · 41(41%) · 0~2000ms: 50 · 50(50%) · /internal/consistency 위반 0 · 앱 종료 · 컨테이너 제거 | **가정 1 성립**(캐시 없음에서도 헛선점 — 생각 시간이 길수록 늘어남) |
| 11-22 04:3x | 가정 2 실증(grep 전수 — `product_seat` UPDATE · DELETE · INSERT · TRUNCATE + 엔티티 `ProductSeat.hold/confirm/expireHolds` 호출처 · reconciler-app · reconcile-core는 좌석 쓰기 없음): 맵(available)을 바꿀 수 있는 쓰기 = ① 1석 선점 `ProductSeatRepository.kt:39`(조건부 UPDATE HELD) · `HoldSeatProcess.kt:87`(엔티티 hold) · `LazyHoldProcess.kt:106` ② 묶음 `HoldGroupService.kt:94` · `:109` ③ 해제 `HoldReleaseService.kt:124` ④ 만료 `SweepConditionalBatch.kt:118` · `ExpireHoldsService`(엔티티 expireHolds) · `LazyExpiryBatch.kt:118~` · `LazyHoldProcess.kt:188`(회수) ⑤ 대체 배정 `SubstituteAllocator.kt:54`(AVAILABLE) · `:70`(다른 좌석 RESERVED) ⑥ 조건 없는 확정 `AtomicConfirmProcess.kt:123` · `LazyConfirmProcess.kt:59`(SELECT FOR UPDATE 뒤 SET RESERVED — 만료로 AVAILABLE이 된 좌석이면 맵이 바뀜) ⑦ loadtest 초기화 `LoadtestDataService.kt:54` · `:88` · `:115` — 확정 조건부 UPDATE(`AtomicConfirmProcess.kt:80` · `:159` · `HoldGroupConfirmService.kt:89`)는 HELD → RESERVED라 맵 무변경 | **가정 2 일부 깨짐** — §3 목록에 없던 경로 6종(엔티티 hold · expireHolds · LazyExpiryBatch · 조건 없는 확정 2 · 대체 배정 RESERVED · loadtest) → 갱신 방식 사용자 결정 대기 |
| 11-22 04:4x | 사용자 결정(AskUserQuestion — `decisions.md`) **앱 쓰기 지점 + 감시 테스트**(DB 트리거 · NOTIFY 안 넣음) → 명세 분기 표 · S4 경로 목록을 log 04:3x로 넓힘 · 설계 선검증 착수: 미러(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/adr019/dv · 기준 SHA 16a6927bb + 작업 트리 spec · main-app · reconciler-app · reconcile-core 소스 · ADR-014 · 015 · 017 · 018 · 019 · 020 · README · 모든 log · NEXT · 측정 로그 제외) · 보안 스캔: 키 패턴 · PII · 내부 호스트 0 · `token =` 1(변수 — 오탐) → codex high 백그라운드 | 선검증 중 |
| 11-22 05:0x | 설계 선검증 회수(codex high · 출력 dv/res/codex-design.md · 15KB): 지적 5(높음 4 · 중간 1) + OQ 2 → DV-1~5 전부 채택(설계 반영 — 구현 전이라 재현은 해당 없음 · 각 지적의 재현 모양을 계약 테스트로 옮김) · OQ 2개는 명세 범위 안 해석으로 닫음(아래 ledger) | 설계 반영 |
| 11-22 05:1x | ADR-019 설계 절에 DV-1~4 반영(방향 무관 무효화 · 세대 번호 · 초기화 전체 비움 · 푸시 순서 · Redis는 TTL만) · 명세 S4′ · S6 · S9 보충 · test-contract.md(설정 3 · HTTP · 지표 4 · `SeatMapTestHooks` 2 · 무효화 경로 · 파일 경계 adr019/ ↔ adr019impl/) · 커밋 4166953b4 → blind 워커(Opus · worktree · 4166953b4) ∥ 구현 워커(Opus · 본 작업 트리 · 커밋 안 함) 착수 | 워커 2 실행 중 |
| 11-22 05:4x | blind 워커 회수(Opus · worktree — 받은 HEAD 831f65edb를 ff로 4166953b4에 맞춤 · 시험만이라 영향 없음): adr019/ 11파일 · 메서드 28(실행 40) · 컨텍스트 9 · compileTestKotlin 통과 · 구현 없이 None · Event 15개 중 12 실패(필드 모양 · `SeatMapTestHooks` ClassNotFound — 예상 원인뿐) · 3 통과(404 · 빈 배열 · 푸시 끔 404) · 해석 16(지표 mode 태그 소문자 · TTL 경계 ±1ms · ttl/redis 쓰기는 JDBC 직접 · SSE 끊김은 쓰기 유도 20초 · 조건 없는 확정은 계약 §5에 없어 HTTP 시험 없음 등) · 미실행: ttl · redis · 장애 · push · substitute · loadtest · startup | 구현 회수 대기(복사는 구현 회수 뒤) |
| 11-22 06:0x | 구현 워커 회수(Opus · 376,232 토큰 · 68분): 새 파일 7(SeatMapProperties · seatmap/SeatMapView · Changes · Push · StartupCheck · TestHooks · SeatMapStreamController) · 쓰기 경로 14파일에 커밋 뒤 신호 · 점검표(아래) · 목록 밖 새 경로 없음 · adr019impl 9파일(감시 테스트 SeatMapWriteSitesTest 포함) · 기존 시험 4 조정(Adr014ModeContractBase · CutoverContract · ImplSweep · SweepContract — 맵 헬퍼 = available + DB 저장 상태로 HELD/RESERVED 구별 · DV-5) · k6 s14-flow.js:146 · 변이 확인 2(세대 검사 제거 → DV-1 시험 실패 · 첫 맵 전 변경 버림 → DV-4 실패, 복원) · 전체 main-app 1451 · 통과 1427 · 실패 0 · 스킵 24 · 메인 판정: ⑤-a 헬퍼 DB 조합 = 수용(derived의 저장 AVAILABLE + 보이는 HELD도 맞게 가름 · 허용 목록 밖 파일 무변경) · ⑤-b SSE 404 전용 처리기 = 수용 · 교차 확인 2: SeatMapView 세대 번호(비교 ↔ 적재 사이 무효화도 항목 stamp가 옛 세대라 다음 조회에서 버려짐 — 성립) · HoldSeatProcess 신호는 모든 전략의 tx.execute 안(커밋 뒤만) | 구현 수용 |
| 11-22 06:1x | blind 테스트 본 작업 트리로 복사 → `./gradlew :main-app:test --tests 'com.jun.labs.seatreservation.adr019.*'` 40 · 통과 40 · 실패 0(첫 실행) · code 커밋 747073b81(docs · `.claude/` 제외) → 전체 `./gradlew test` 백그라운드 · 리뷰 루프 1 packet 준비(base 4166953b4 · 리뷰한 SHA 747073b81) | 리뷰 1 준비 |
| 11-22 06:3x | 리뷰 루프 1 packet(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/adr019/rv1 · base 4166953b4 · **리뷰한 SHA 747073b81** · diff 3,409줄 · untracked 없음(.claude/ 워커 worktree · k6 smoke 잔여는 작업 밖) · spec · test-contract 원문 · related-raw) · 미러 = git archive 747073b81 − 모든 log · NEXT · 측정 로그 · k6 results · docs/plans · docs/how-it-works · 보안 스캔: `sk-` 1(disk-measurement — 오탐) · 실험 서버 내부 주소 · 홈 경로(ADR 3 · k6 스크립트) → 미러에서 가림(<lab-host> 등) · 재스캔 0 → codex high ∥ Opus 리뷰어 | 리뷰 1 실행 중 |
| 11-22 07:0x | 리뷰 루프 1 회수: codex high(출력 rv1/res/codex.md · 5KB) 지적 5 · OQ 0 ∥ Opus(193,212 토큰) 지적 5 + OQ 4 → 겹침 합쳐 묶음 R1-A~I(아래 ledger) · 메인 1차 분류: 코드 동작 지적은 리뷰한 SHA 747073b81에서 실행 재현 뒤 채택(A · B · D · F · G · H · I) · E 문서(예외 ①) · C 후보 · OQ2 · OQ3 = 2단계 명세 입력 → 재현 검증 워커(Opus · worktree · 747073b81 — 리뷰어와 다른 워커) 착수 | 재현 중 |
| 11-22 07:1x | 전체 `./gradlew test`(747073b81 · 33분): main-app 1491 · 통과 1467 · 실패 0 · 스킵 24(blind 40 · adr019impl 포함) · mock-pg 24/0 · reconciler-app 6/0(입력 무변경 — up-to-date 결과) · exit 0 | 안전선 ② 통과 |
| 11-22 07:3x | 재현 회수(Opus · 164,534 토큰 · worktree 747073b81): A · B · D · G · H 실행 재현 · F는 1석 선점만으로는 잠재(3/3 통과) · 큰 변경 주입 F2로 재현(Y 약 58초 지연 3/3) · I는 주입으로 기제 재현 · 운영 콜백이 던지는 경로 없음(잠재) → 채택 8(A · B · D · E · F · G · H · I) · 후보 1(C) · 등급과 상관없이 (3)(4)로 가름 → 구현 워커 재개(수정 · 재현 테스트를 회귀로 adr019impl에 옮김 · 새 키 `seat.seat-map.redis-timeout`) | 수정 중 |
| 11-22 08:0x | 수정 회수(구현 워커 재개 · 누적 440,826 토큰): A SeatMapView.kt:126 · B clearAll + SeatMapRedis.deleteByPrefix(SCAN) · D compute/computeIfPresent · F 푸시를 서블릿 비동기 비차단 출력(WriteListener)으로 다시 씀 · 연결별 프레임 1 + 좌석 id 누적 · 막힘 10초 넘으면 그 연결만 닫음(ponytail) · G 좌석 맵 전용 Lettuce 팩토리(redis 모드만 · `seat.seat-map.redis-timeout` 200ms · REJECT_COMMANDS · 공용 StringRedisTemplate · ADR-003 그대로) · H 파일별 쓰기 지점 수 고정(변이 확인: 신호 없는 UPDATE 추가 → 실패 · 한계 주석 2) · I 동기화 HIGHEST_PRECEDENCE + 예외 삼킴(ERROR 로그 · `seat.seatmap.signal.failures`) · 회귀 테스트: 수정 전 코드로 되돌려 7건 모두 실패 확인 → 복원(cmp) · 전체 main-app 1498 · 통과 1474 · 실패 0 · 스킵 24 · 메인 수용: 새 지표 태그 op=clear · signal.failures(test-contract 반영) · code 커밋(아래) · README(R1-E) · test-contract docs 커밋 | 리뷰 2 준비 |

### S4 점검표 — 쓰기 경로 ↔ 무효화 호출 (구현 워커 보고 · 747073b81)

| 쓰기 | 무효화 호출 |
|---|---|
| ① `ProductSeatRepository.kt:39` · `DbLockHoldStrategies` · 엔티티 hold | `HoldSeatProcess.kt:94`(전략 8종 모두 `process.hold`를 지남) |
| ① `LazyHoldProcess.kt:106` | `LazyHoldProcess.kt:110` |
| ② `HoldGroupService.kt:94` · `:109` | `HoldGroupService.kt:78` |
| ③ `HoldReleaseService.kt:124` | `HoldReleaseService.kt:132` |
| ④ `SweepConditionalBatch.kt:118` | `SweepConditionalBatch.kt:92` |
| ④ `ExpireHoldsService` 엔티티 expireHolds | `ExpireHoldsService.kt:54` |
| ④ `LazyHoldProcess.kt:188` | `LazyHoldProcess.kt:144` |
| ④ `LazyExpiryBatch` | `LazyExpiryBatch.kt:149`(publishSeats) |
| ⑤ `SubstituteAllocator.kt:54` · `:70` | `SubstituteAllocator.kt:85`(두 좌석) |
| ⑥ `AtomicConfirmProcess.kt:80` · `:123` · `:159` | `AtomicConfirmProcess.kt:62` |
| ⑥ `LazyConfirmProcess.kt:59` | `LazyConfirmProcess.kt:62` |
| ⑥ `HoldGroupConfirmService.kt:89` | `HoldGroupConfirmService.kt:112` |
| ⑥ `ConfirmReservationService` 엔티티 confirm | `ConfirmReservationService.kt:96` |
| ⑦ `LoadtestDataService.kt:54` · `:88` · `:115` | `LoadtestDataService.kt:100`(publishAll) |

## 리뷰 ledger

| id | loop | source | 근거 | repro | impact | disposition | status |
|----|------|--------|------|-------|--------|-------------|--------|
| DV-1 | 선검증 | codex | SeatMapQuery.kt:14 · ADR-019 설계-3 | 해당 없음 — 설계 문서(계약 테스트: 조회 반환 ↔ 캐시 적재 사이 장벽 + 선점 커밋) | 데이터 | 채택 — 회차별 세대 번호: 조회 전 세대를 읽고 같을 때만 적재 | open |
| DV-2 | 선검증 | codex | SubstituteAllocator.kt:49-70 · ADR-019 설계-1 | 해당 없음 — 설계 문서(계약 테스트: substitute 대체 배정 뒤 두 좌석) | 데이터 | 채택 — 방향 무관 모든 상태 쓰기 무효화 · 설계-1 문장 고침 | open |
| DV-3 | 선검증 | codex | LoadtestDataService.kt:43-95 | 해당 없음 — 설계 문서(계약 테스트: 회차 2 캐시 → reset schedules=1 → 404) | 데이터 | 채택 — 초기화 커밋 뒤 전체 캐시 비움 | open |
| DV-4 | 선검증 | codex | ADR-019 설계-2 · SeatMapQuery.kt:31-38 | 해당 없음 — 설계 문서(계약 테스트: 첫 조회 ↔ 구독 등록 사이 선점) | 계약 | 채택 — 구독 먼저(버퍼) → 첫 맵 → 버퍼 비움 · 변경은 좌석 id로 알리고 보낼 때 현재 보이는 상태를 다시 읽음 · 끊김 시 구독 · 버퍼 함께 정리 | open |
| DV-5 | 선검증 | codex | Adr014ModeContractBase.kt:509-516 · :154-158 | 코드로만 확인 — 테스트 공백(두 상태로 바꾸면 HELD/RESERVED 구별 소실) | 검증 | 채택 — 맵 단언은 false · 확정 뒤 RESERVED는 DB로 따로 단언 · 필드 집합 검사 고침 | open |
| DV-OQ1 | 선검증 | codex | 명세 S9 ↔ S7 | 해당 없음 — 설계 문서 | 계약 | open question → 메인: S9(다중 인스턴스 무효화 범위 밖)를 SSE에도 적용 — 다른 인스턴스의 변경은 전파하지 않음(ADR 설계-4에 명시) | 정리 |
| DV-OQ2 | 선검증 | codex | 명세 S6 · HoldStrategyStartupCheck.kt:158 | 해당 없음 — 설계 문서 | 계약 | open question → 메인: '연결하지 않는다'는 좌석 맵 기능 한정(기존 Redis 선점 전략은 그대로) — 명세 S6 문장 보충 | 정리 |
| R1-A | 1 | codex F1 · opus F5 | SeatMapView.kt:114-123 | 실행 — 747073b81 · ReproARedisBadEntryTest · DateTimeParseException 전파 · failures 증가 없음 | 가용성 | 채택 — (3) 이번 변경 · (4) S6 | fixed(1) |
| R1-B | 1 | codex F2 · opus OQ4 | SeatMapChanges.kt:43-47 · SeatMapView.kt:83-87 | 실행 — 747073b81 · ReproBRedisResetTest · 초기화 뒤 TTL 안 seat1=false(DB AVAILABLE) | 계약 | 채택 — (4) S4′ 전체 비움 | fixed(1) |
| R1-C | 1 | codex F3 · opus F4 | SeatMapView.kt:49 · :91-103 | 해당 없음 — 후보(실행 재현하지 않음) | 가용성 | 후보 — (3) 이번 변경 · (4) 명세 불변식에 메모리 상한 없음 · 지금 측정은 회차 1~2개 | 후보 |
| R1-D | 1 | codex F4 | SeatMapPush.kt:61-63 · :147-153 | 실행 — 747073b81 · ReproPushTest D · 회차 5 열고 닫은 뒤 빈 집합 5 | 가용성 | 채택 — (4) S7 자원 정리 | fixed(1) |
| R1-E | 1 | codex F5 | README.md:169-171 | 코드로만 확인 — 문서 | 문서 | 채택(docs 커밋) | fixed(1) |
| R1-F | 1 | opus F1 | SeatMapPush.kt:35 · :93-118 | 실행 — 747073b81 · ReproPushTest F2(변경 4999석 1회 publish 주입 + 읽지 않는 X) · Y 수신 58,468 · 58,431 · 58,424ms · sender가 X 쓰기에서 막힘(스택) · F(1석 선점 4999회)는 3/3 통과 = 이 조건 잠재 | 가용성 | 채택 — (4) S7 · 2단계 전파 지연 측정 | fixed(1) |
| R1-G | 1 | opus F2 | SeatMapView.kt:108 · :129 · application.yml | 실행 — 747073b81 · ReproGRedisDefaultTimeoutTest · Redis 멈춘 뒤 GET 1회 120,016ms · 200(get 60s + put 60s) | 가용성 | 채택 — (4) S6 | fixed(1) |
| R1-H | 1 | opus F3 | SeatMapWriteSitesTest.kt:107-113 | 실행 — 747073b81 · HoldReleaseService에 신호 없는 UPDATE 임시 추가 → 감시 테스트 통과(2/0) · 되돌림 확인 | 검증 | 채택 — (4) 분기의 답(목록 밖 쓰기 실패) | fixed(1) |
| R1-I | 1 | opus OQ1 | SeatMapChanges.kt:56-60 · AtomicConfirmProcess.kt:54 | 잠재 — 747073b81 · ReproIAfterCommitOrderTest(앞선 콜백이 던지게 주입 → DB HELD · 맵 true) · 운영 콜백(convertedAfterExpiry 등)이 던지는 경로는 못 찾음 | 데이터 | 채택 — 잠재 → (3) 이번 변경 · (4) S4 커밋 뒤 무효화 | fixed(1) |
| R1-OQ | 1 | opus OQ2 · OQ3 | SeatMapView.kt:143-144 · :91-99 | 해당 없음 — 측정 설계 | 미정 | open question → 메인: test-contract §3이 정한 '읽은 뒤 지난 시간'으로 구현됨(계약대로) · 2단계 명세에서 화면 나이를 클라이언트 쪽으로 잴지 · 동시 미스 합치기를 비교 조건에 넣을지 정함(NEXT) | 정리 |

## 생략한 검증

## 완료 요약
