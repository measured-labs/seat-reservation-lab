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

## 생략한 검증

## 완료 요약
