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
| 11-22 08:1x | 리뷰 루프 2 packet(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/adr019/rv2 · base 4166953b4 · **리뷰한 SHA eb97224a6**(code 649364dc4 + docs) · 누적 diff(docs/plans 제외) + 이번 루프 수정 diff 747073b81..649364dc4 참고 · 미러 = git archive 앱 4개 · docs/adr · README · gradle · k6/ADR-014/scenarios) · 보안 스캔: 내부 주소 · 홈 경로 가림 · 재스캔 0 · `DB_PASSWORD: ${…:?}`는 자리 표시(값 없음) → codex high ∥ Opus 리뷰어 · 그사이 기동 스모크 | 리뷰 2 실행 중 |
| 11-22 08:3x | 기동 스모크(안전선 ① · bootJar eb97224a6 트리 · 임시 postgres:16 127.0.0.1:25434 · redis:7 127.0.0.1:26380 · loadtest 프로필 · 시드 200석 · 스크립트 scratchpad/adr019/smoke/smoke.sh): 필드 5개(available · rowNo · seatId · seatNo · section) 모든 모드 · 좌석 7 선점 201 뒤 — none 바로 false · ttl 선점 직후 true(캐시) → 1.2초 뒤 false · redis 같음 · event 바로 false · requests 지표 source db/cache · SSE(event + push) snapshot 1 → change 1 `data:[{"seatId":7,"available":false}]` · event + lazy 기동 거부 exit 1 · 메시지 "seat.seat-map.mode=event 는 seat.hold.expiry-mode=sweep에서만 쓴다 — 현재 lazy …" · 컨테이너 제거 | 스모크 통과 |
| 11-22 08:5x | 리뷰 루프 2 회수: codex high 지적 4 + OQ 1(헛선점 스모크 증거 — log 04:2x에 있음, packet이 log를 빼서 못 봄 → 답함) ∥ Opus(202,497 토큰) 지적 3(낮음) + OQ 4 → 묶음 R2-A~H · 메인 1차 분류(아래 ledger) → 재현 검증 워커 2(Opus · worktree · eb97224a6) 착수 | 재현 중 |
| 11-22 09:1x | 재현 2 회수(Opus · 133,925 토큰 · worktree eb97224a6): R2-A · R2-B · R2-F 재현 · R2-G 잠재(Tomcat이 1초 안 완료) · OQ1 문제 없음 · OQ2 snapshot 꼬리 하트비트에 묶임(재현) · OQ3 기동 실패(안내 없음) → 채택 7(A · B · C · E · F · H2 · H3) · 후보 1(G) · 기각 2(D · H1) → 구현 워커 재개(루프 2 수정) | 수정 중 |
| 11-22 10:0x | 루프 2 수정 회수(구현 워커 · 누적 479,973 토큰): R2-A Redis 값에 세대 · 꺼낼 때 비교 · MGET · R2-B 초기화 = 세대 INCR(SCAN 삭제 없앰) · 실패 시 이 인스턴스 TTL 동안 Redis 건너뜀 · R2-C useSsl · R2-E 시험만(SeatMapEventCurrentModeTest — 처음부터 통과 = 빠진 검증 채움) · R2-F open 전체 try + 정리 · ready는 출력 준비 뒤 · OQ2 프레임 끝 flush(1만 석 snapshot 5회 모두 300ms 안) · OQ3 redis 모드 0 이하 거부 · 수정 전 실패 확인 = 부분 되돌림 변이(새 API로 옛 코드 컴파일 불가) — R2-F 변이 실패는 OQ2 변이 잔여 연결과 섞여 단독 판별 못 함(기록) · 동작 변화: Redis 읽기 실패 시 put 생략(SeatMapRedisFailureTest put 단언 조정) · 전체 main-app 1506 · 통과 1482 · 실패 0 · 스킵 24 · 메인: 한계 2(다른 인스턴스 세대 실패 · ssl bundle)를 ADR 설계-4에 적음 · test-contract Redis 키 · 커밋(아래) | 리뷰 3 준비 |
| 11-22 10:1x | 리뷰 루프 3(상한) packet(OUT=/tmp/claude-1000/-home-jun-project-lab-backend-labs-commerce/0961cd42-5c49-4146-8c52-81561cdbcc92/scratchpad/adr019/rv3 · base 4166953b4 · **리뷰한 SHA 10e290fae**(code c54e933e8 + docs) · 직전 루프 수정 diff eb97224a6..c54e933e8 참고 · 미러 루프 2와 같은 범위 · 보안 스캔 재스캔 0) → codex high ∥ Opus 리뷰어 | 리뷰 3 실행 중 |
| 11-22 10:4x | 리뷰 루프 3 회수: codex high 지적 4 + OQ 1 ∥ Opus(낮음 1 + OQ 2) → R3-A~E · codex F1 기각(꺼낼 때 `it.stamp == before` 비교 — SeatMapView local 3행 · 옛 적재는 미스 1회일 뿐 — 메인이 코드로 확인) · codex F2 기각(S3 "TTL + 조회 시간" 허용 · 상한 redis-timeout 200ms) · codex F3 후보(S9 다중 인스턴스 범위 밖 · ADR 설계-4 한계) · codex F4 + opus OQ1 = R2-G와 같은 지적 세 번째 → 후보 유지(실측 Tomcat 1초 안 완료) · opus F1 새 지적 → 재현 워커 3(10e290fae) | 재현 중 |
| 11-22 11:0x | 재현 3 회수(Opus · 99,920 토큰 · 10e290fae): R3-A 재현(12/12) → 루프 3(상한) 신규 채택 1 → 종료 조건 불충족(review unresolved) · 같은 유형 네 번째 → §6 구조 제안 · 사용자 결정 "구조 수정 + 재점검 1회로 마감"(user override — `decisions.md`) · R3-E(후보)는 구조 수정에 함께 듦 → 구현 워커 재개 | 구조 수정 중 |
| 11-22 14:4x | 구조 수정 회수(구현 워커 · 누적 512,595 토큰): SeatMapPush.kt 한 파일 — 상태 OPENING → READY → CLOSED(AtomicReference · 한 방향) · 닫힘 경로 close(sub) 한 곳(목록 제거 · 빈 키 삭제 · 연결 수 1회 · completeOnce) · attach가 붙인 직후 상태 재확인(닫혔으면 완료) · finish 제거(쓰기 오류 · onError · onTimeout도 close) · 회귀: R3-A 4클래스(closeAll · reset × none · event) · 초기화 → 준비된 연결 2개 EOF · R3-E 쓰기 오류 → 완료 — 수정 전(SeatMapPush.kt만 c54e933e8로 되돌림) R3-A 4 · R3-E 실패 → 복원(cmp) 뒤 통과 · 전체 main-app 1512 · 통과 1488 · 실패 0 · 스킵 24 · code 커밋(아래) → post-fix 재점검(codex · 1회 · 고친 hunk + 인접 + 새 시험) | 재점검 중 |
| 11-22 15:0x | post-fix 재점검 회수(codex high · 미러 fa60e7d1d · 대상 10e290fae..1c95828bf + 인접 + 새 시험): 지적 2 → PF-1 잠재(startAsync 뒤 attach 전 예외 → 완료 누락 · 실행 재현을 수정 전 시험으로) · PF-2 테스트 공백(readUntilEof가 IOException도 EOF) → 둘 다 채택 · 사용자 결정대로 추가 리뷰 없이 수정으로 마감 → 구현 워커(실패 시험 먼저) | 마감 수정 중 |
| 11-22 15:3x | 마감 수정 회수(구현 워커 · 누적 534,607 토큰): PF-1 SeatMapPush.kt:202-203 startAsync 직후 attach → 이후 실패도 close가 1회 완료 · PF-2 Adr019ImplSupport.readUntilEof `read()==-1`만 EOF(IOException 따로) · 경합 시험에 complete 정확히 1 · 연결 수 0 · 목록 제거 단언(4클래스) · 수정 전 실패 확인(P1 `expected <1> but was <0>` · P2 옛 분기 `Eof(text=)`) · RST 방식은 이 커널에서 read -1이라 비결정 → IOException 던지는 소켓으로 바꿈 · 전체 main-app 1518 · 통과 1494 · 실패 0 · 스킵 24 · code 커밋(아래) · 추가 리뷰 없음(사용자 결정) | 리뷰 마감(user override) |
| 11-22 15:4x | worktree 정리(이 작업의 워커 4개 · 브랜치 4 — 남은 것은 커밋 안 한 재현 · blind 시험 사본뿐 · `agent-a1681…`은 10-23 빈 디렉터리라 둠) · 기동 스모크 재실행(cc60cf3d8 bootJar — eb97224a6 뒤 Redis · 푸시 코드가 바뀜): 결과 08:3x와 같음(필드 5 · none/event 바로 false · ttl/redis TTL 뒤 false · SSE snapshot → change `[{"seatId":7,"available":false}]` · event + lazy 거부) · README ADR-019 행 · NEXT N00f · 측정 로그 1행 | 마감 |
| 11-22 15:5x | **정정(사후 — 기록만 바로잡음)**: 이 log의 05:1x 이후 행 시각 칸 일부는 메인이 대략 적은 값이다 — 정확한 시각은 decisions.md(예: 갱신 신호 04:3x 결정 · 리뷰 상한 결정 13:50:41)와 각 워커 보고의 실행 시각이 정본. 워커 토큰(완료 알림 usage): blind 224,902 · 구현 누적 537,867(마지막 알림) · 리뷰 Opus 193,212 · 202,497 · 172,316 · 재현 164,534 · 133,925 · 99,920 · 완료 요약 136,528 · codex는 토큰 표시 없음 | 기록 정정 |

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
| DV-1 | 선검증 | codex | SeatMapQuery.kt:14 · ADR-019 설계-3 | 해당 없음 — 설계 문서(계약 테스트: 조회 반환 ↔ 캐시 적재 사이 장벽 + 선점 커밋) | 데이터 | 채택 — 회차별 세대 번호: 조회 전 세대를 읽고 같을 때만 적재 | fixed(설계) |
| DV-2 | 선검증 | codex | SubstituteAllocator.kt:49-70 · ADR-019 설계-1 | 해당 없음 — 설계 문서(계약 테스트: substitute 대체 배정 뒤 두 좌석) | 데이터 | 채택 — 방향 무관 모든 상태 쓰기 무효화 · 설계-1 문장 고침 | fixed(설계) |
| DV-3 | 선검증 | codex | LoadtestDataService.kt:43-95 | 해당 없음 — 설계 문서(계약 테스트: 회차 2 캐시 → reset schedules=1 → 404) | 데이터 | 채택 — 초기화 커밋 뒤 전체 캐시 비움 | fixed(설계) |
| DV-4 | 선검증 | codex | ADR-019 설계-2 · SeatMapQuery.kt:31-38 | 해당 없음 — 설계 문서(계약 테스트: 첫 조회 ↔ 구독 등록 사이 선점) | 계약 | 채택 — 구독 먼저(버퍼) → 첫 맵 → 버퍼 비움 · 변경은 좌석 id로 알리고 보낼 때 현재 보이는 상태를 다시 읽음 · 끊김 시 구독 · 버퍼 함께 정리 | fixed(설계) |
| DV-5 | 선검증 | codex | Adr014ModeContractBase.kt:509-516 · :154-158 | 코드로만 확인 — 테스트 공백(두 상태로 바꾸면 HELD/RESERVED 구별 소실) | 검증 | 채택 — 맵 단언은 false · 확정 뒤 RESERVED는 DB로 따로 단언 · 필드 집합 검사 고침 | fixed(설계) |
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
| R2-A | 2 | codex F1 · opus OQ4 | SeatMapView.kt:133-141 · :87-96 | 실행 — eb97224a6 · RedisResetReproTest R2-A · afterDbRead에서 멈춘 사이 초기화 커밋 → A가 옛 맵 적재 → TTL 안 GET 좌석 1 false(DB AVAILABLE) | 계약 | 채택 — (3) 이번 변경 · (4) S4′ | fixed(2) |
| R2-B | 2 | codex F2 | SeatMapView.kt:91-96 · :123-131 | 실행 — eb97224a6 · RedisResetReproTest R2-B(deleteByPrefix만 예외 주입 · stub) · failures{op=clear} 1 · 키 남음 · GET 좌석 1 false | 계약 | 채택 — (4) S4′ · S6 | fixed(2) |
| R2-C | 2 | codex F3 | SeatMapRedis.kt:52-69 | 코드로만 확인 — 명세 위반 자명(test-contract §1 '기존 spring.data.redis.* 설정을 쓴다' ↔ SSL 미적용 · TLS 시험 장치 없음) | 계약 | 채택 — (3) 이번 변경 · (4) 계약 §1 | fixed(2) |
| R2-D | 2 | codex F4 | README.md:171 | 해당 없음 — 기각 | 문서 | 기각 — README에 좌석 맵 API 응답 설명이 원래 없음((3) 이번 변경이 틀린 말을 만들지 않음) · 응답 정본은 ADR-019 설계-1 | 정리 |
| R2-E | 2 | opus F1 | SeatMapEventPathsTest.kt:89 · :99 · ExpireHoldsService.kt:54 · ConfirmReservationService.kt:96 | 코드로만 확인 — 테스트 공백(current 조합의 엔티티 경로 반영 시험 없음) | 검증 | 채택 — (4) 명세 §5 경로마다 반영 테스트 | fixed(2) |
| R2-F | 2 | opus F2 | SeatMapPush.kt:146-166 | 실행 — eb97224a6 · PushOpenFailureReproTest F1(startAsync) · F2(getOutputStream) · 12초 뒤에도 연결 수 +1 · 구독 1 남음(out null이면 막힘 상한도 안 걸림) | 가용성 | 채택 — (4) S7 | fixed(2) |
| R2-G | 2 | opus F3 | SeatMapPush.kt:115 · :127-135 | 잠재 — eb97224a6 · PushRealPortReproTest R2-G · close · RST 모두 약 1초 안에 AsyncContext 완료(request null · waitingProcessors 0) · Tomcat 10.1.44 | 가용성 | 후보 — (4) 이 컨테이너에서 S7 위반 관측 없음 · 완료 주체 미구분 | 후보 |
| R2-H1 | 2 | opus OQ1 | SeatMapPush.kt:165-166 | 실행 — eb97224a6 · PushRealPortReproTest OQ1 · listener 없이 isReady = false(예외 없음) | 해당 없음 | 기각 — 실측으로 문제 없음 | 정리 |
| R2-H2 | 2 | opus OQ2 | SeatMapPush.kt:112 | 실행 — eb97224a6 · OQ2b · 1만 석 snapshot(701.7KB) 끝 바이트가 하트비트 flush에 묶임 995 · 236 · 187 · 887 · 1004ms(5/5 끝 바이트와 하트비트 같은 읽기) · change는 묶이지 않음 | 계약 | 채택 — (3) 이번 변경(비차단 쓰기 끝의 flush 빠짐) · (4) §1 ⑤ 측정 가능한 후보(2단계 SSE 지연에 섞임) | fixed(2) |
| R2-H3 | 2 | opus OQ3 | SeatMapRedis.kt · SeatMapStartupCheck.kt | 실행 — eb97224a6 · RedisZeroTimeoutReproTest · `redis-timeout=0s` → Lettuce `Connect timeout must be greater 0`로 기동 실패 · 앱 안내 없음 | 계약 | 채택 — (3) 새 키 · (4) 설정 거부 안내(ttl 검사와 같게) | fixed(2) |
| R3-A | 3 | opus F1 · OQ2 | SeatMapPush.kt:154 · :169-177 · :193-195 · :141-144 | 실행 — 10e290fae · R3aCloseAllDuringOpenTest 4클래스(직접 closeAll · reset × none · event) · 닫기 낀 12/12: 15초 동안 바이트 0 · EOF 없음 · 지표 0 · 종료 시 활성 요청 남음 4/4 · 대조군 12/12 정상 | 가용성 | 채택 — (3) 이번 변경 · (4) S7 · S4′ · 같은 유형 네 번째(R1-D · R1-F · R2-F · R3-A — SSE 연결 수명 주기 정리) → §6 구조: 구독 상태 기계 · 닫힘이면 async가 생기는 순간 완료(R3-E 함께) · 초기화 → 푸시 닫힘(EOF)을 계약에 | fixed(3) |
| R3-B | 3 | codex F1 | SeatMapView.kt:115 · :81 | 코드로만 확인 — 기각 근거(꺼낼 때 세대 비교) | 해당 없음 | 기각 — 옛 적재 항목은 stamp가 옛 세대라 다음 조회에서 버려짐(미스 1회) | 정리 |
| R3-C | 3 | codex F2 | SeatMapView.kt:127 · :136 | 해당 없음 — 기각 | 해당 없음 | 기각 — S3가 TTL + 조회 시간을 허용 · Redis 읽기 시간 상한 = redis-timeout | 정리 |
| R3-D | 3 | codex F3 · codex OQ | SeatMapView.kt:93-99 | 해당 없음 — 후보 | 검증 | 후보 — 다중 인스턴스 무효화는 S9 범위 밖 · ADR 설계-4에 한계 기록 | 후보 |
| R3-E | 3 | codex F4 · opus OQ1 | SeatMapPush.kt:122 · :141 | 잠재 — R2-G 실측(Tomcat 10.1.44가 1초 안 완료) | 가용성 | 채택 — 구조 수정(R3-A)에 함께 듦(사용자 결정) | fixed(3) |
| PF-1 | 재점검 | codex | SeatMapPush.kt:201 · :98 · :210 | 실행 — 1c95828bf · SeatMapPushTest P1(가짜 AsyncContext · addListener · setTimeout이 던짐) · `complete() 정확히 1번 expected <1> but was <0>` | 가용성 | 채택 — (3) 이번 변경 · (4) S7 | fixed(마감) |
| PF-2 | 재점검 | codex | SeatMapPushCloseDuringOpenTest.kt:92 · :104 · :145 · SeatMapPushTest.kt:317 | 코드로만 확인 — 테스트 공백(IOException을 EOF로 통과) · 헬퍼 시험이 옛 분기에서 `Eof(text=)`로 실패 확인 | 검증 | 채택 — (4) S7 시험 계약 | fixed(마감) |

## 생략한 검증

## 완료 요약 (2026-11-22)

- 범위: 명세 §1 ①~⑤ — ① 헛선점 재현 스모크(log 04:2x · 저장소 코드 아님) ② 응답 `available`(SeatMapView의 SeatMapSeat · k6 s14-flow.js 1줄) ③ `seat.seat-map.mode`(none 기본 · ttl · redis · event · SeatMapProperties · SeatMapView 세대 번호 · Redis 초기화 세대 · SeatMapRedis 전용 연결 `seat.seat-map.redis-timeout` 200ms · SeatMapChanges 커밋 뒤 신호 — 쓰기 경로 13파일 · 기동 검사 event/푸시 = sweep 전용) ④ SSE `GET /api/schedules/{id}/seats/stream`(기본 끔 · 비차단 출력 · 구독 상태 기계) ⑤ 지표 requests · age · redis.failures(get · put · clear) · push.connections · signal.failures · 문서: 번호 이동 11차 · 새 ADR-020(가설) · ADR-019 설계-1~4.
- 변경: 커밋 11(code 5: 747073b81 · 649364dc4 · c54e933e8 · 1c95828bf · cc60cf3d8 / docs 6: 4a513f36e · 16a6927bb · 4166953b4 · eb97224a6 · 10e290fae · fa60e7d1d) · main 25파일 +886/−10 · 시험 27파일 +2790/−13(blind adr019/ 11 · adr019impl/ · 기존 시험 조정 4) · k6 1 · docs 18.
- 핵심 diff(실파일 `git show` 추출):

① 응답 — before `831f65edb` SeatMapQuery.kt · SeatMapController.kt
```kotlin
data class SeatMapEntry(val seatId: Long, val section: String, val rowNo: Int, val seatNo: Int, val status: SeatStatus)
…
    @GetMapping("/api/schedules/{scheduleId}/seats")
    fun seats(@PathVariable scheduleId: Long): List<SeatMapEntry> = seatMap.seats(scheduleId)
```
after `cc60cf3d8` SeatMapView.kt · SeatMapController.kt
```kotlin
data class SeatMapSeat(val seatId: Long, val section: String, val rowNo: Int, val seatNo: Int, val available: Boolean)
…
    @GetMapping("/api/schedules/{scheduleId}/seats")
    fun seats(@PathVariable scheduleId: Long): List<SeatMapSeat> = seatMap.seats(scheduleId)
```
② 커밋 뒤 신호 — `cc60cf3d8` SeatMapChanges.kt
```kotlin
    private fun afterCommit(block: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return guarded(block)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun getOrder() = Ordered.HIGHEST_PRECEDENCE
            override fun afterCommit() = guarded(block)
        })
    }
```
③ 세대 번호 — `cc60cf3d8` SeatMapView.kt
```kotlin
    private fun local(scheduleId: Long): List<SeatMapSeat> {
        val before = stamp(scheduleId)
        val now = clock.instant()
        local[scheduleId]?.takeIf { it.stamp == before && fresh(it.readAt, now) }?.let { return respond(it.seats, it.readAt, fromCache) }
        val readAt = clock.instant()
        val seats = readDb(scheduleId)
        SeatMapTestHooks.afterDbRead?.invoke(scheduleId)
        if (stamp(scheduleId) == before) local[scheduleId] = Cached(seats, readAt, before)
        return respond(seats, readAt, fromDb)
    }
```
④ 푸시 상태 기계 — `cc60cf3d8` SeatMapPush.kt
```kotlin
    private enum class State { OPENING, READY, CLOSED }
…
    private fun close(sub: Subscription) {
        if (sub.toClosed()) {
            bySchedule.computeIfPresent(sub.scheduleId) { _, subs -> subs.remove(sub); if (subs.isEmpty()) null else subs }
            open.decrementAndGet()
            sub.discard()
        }
        sub.completeOnce()
    }
```
- 검증: 재현 스모크 409 비율 37% · 41% · 50%(생각 0 · 0~500ms · 0~2초 · 위반 0) · 가정 2 일부 깨짐 → 사용자 분기(앱 쓰기 지점 + 감시 테스트) · 설계 선검증 DV-1~5 · blind 40/40(구현 없이 None · Event 15 중 12 실패) · 변이 확인(세대 검사 · 첫 맵 버림 · 신호 없는 UPDATE · 루프마다 수정 전 코드 실패) · 전체 `./gradlew test`(747073b81) main-app 1491/1467/0/24 · mock-pg 24/0 · reconciler 6/0 → main-app 마지막 1518/1494/0/24(cc60cf3d8) · 기동 스모크 eb97224a6 · **cc60cf3d8(마감 재실행)** 통과.
- 리뷰: 듀얼 3루프(리뷰한 SHA 747073b81 · eb97224a6 · 10e290fae) + 구조 수정(1c95828bf) + post-fix 재점검(codex · 10e290fae..1c95828bf) · 루프마다 별도 재현 워커가 리뷰한 SHA에서 재현 뒤 채택 · ledger: 채택 24(DV 5 · R1 8 · R2 7 · R3 2 · PF 2) · 후보 3(R1-C · R2-G · R3-D) · 기각 4(R2-D · R2-H1 · R3-B · R3-C) · OQ 정리 3(ledger 행의 disposition 첫 낱말을 셈 — 요약 워커 awk).
- 리뷰 상태: 루프 3(상한)에서 신규 채택 R3-A(12/12 재현) → 종료 조건 불충족(review unresolved) · 같은 유형 네 번째(SSE 수명 주기) → 사용자 결정 "구조 수정 + 재점검 1회로 마감"(user override · decisions.md 13:50:41) · 재점검 지적 PF-1 · PF-2는 수정으로 마감 · cc60cf3d8 뒤 리뷰 없음 — 병합 판단은 사용자.
- 남은 것: 후보 R1-C(앱 메모리 캐시 축출) · R2-G(끊김 뒤 완료 주체 — 구조 수정이 쓰기 오류 → 완료를 넣음) · R3-D(다중 인스턴스 세대 실패) · 2단계 측정 명세 입력(R1-OQ — NEXT N00f).
- 생략한 검증: mock-pg · reconciler-app 시험은 747073b81 뒤 다시 돌리지 않음(그 뒤 code 커밋은 main-app만 바꿈 — `git diff --name-only 747073b81 cc60cf3d8` 확인).
- CS 이슈 아카이브: 보류 — study-note 저장소 쓰기는 이 세션의 사용자 금지 영역(issue-archive §1 보류 경로) · 후보 이슈: 비동기 서블릿 I/O 수명 주기(여는 중 닫힘 · 완료 누락) · 캐시 무효화 ↔ 적재 경합(세대 번호).
