# log — ADR-019 좌석 맵 조회와 실제 상태 불일치 (Q5)

> 명세: `requirement-spec.md`(합의 2026-11-22 · SPEC=1 · auto) · 브랜치 `feat/adr-019-seat-map-query`(main 831f65edb에서 · 로컬)

| 시각(KST) | 사건 | 결과 |
|-----------|------|------|
| 11-22 | 사용자 "전부 마무리하고 19로 가자" → 하네스 PR #9 · #10 병합 · 배포 · smoke 통과 · worktree 정리 → N00e ③ 기동 검사 구조는 건너뛰고(후보로 남김) ④ ADR-019 착수 · 사전 확인: `SeatMapController`(GET /api/schedules/{id}/seats · 캐시 없음 · 1만 석 한 문장 — `SeatMapQuery` 만료 방식별 SQL) · ADR-014 §가정 9(좌석 맵 초당 20번만 잼) · ADR-018 Q/A 8(캐시는 ADR-019 몫) | 작업 폴더 생성 · 인터뷰 |
| 11-22 04:0x | 인터뷰 3회(11문항 — `decisions.md`) · 상태 표시: 사용자 질문(HELD를 합쳐 보여야 하나)에 답(status 한 칸 · 한 문장) → 두 상태 · 값 이름 AVAILABLE/UNAVAILABLE → 사용자 재고("true/false … 응답관점") → 메인 권고 `available` bool로 명세 반영 · 번호 이동 = ADR-020으로 끼움(번호만 · 닫힌 ADR 예외) · 사전 grep: 좌석 상태 쓰기 15곳(9파일) · lazy/derived 시간 만료는 쓰기 없음 → S5(event는 sweep 전용) · 코드에 ADR-020 · 021 참조 없음(docs/adr 59줄 · README · NEXT) | 명세 초안 · 합의 대기 |

## 리뷰 ledger

| id | loop | source | 근거 | repro | impact | disposition | status |
|----|------|--------|------|-------|--------|-------------|--------|

## 생략한 검증

## 완료 요약
