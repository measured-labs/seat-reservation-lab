# 테스트 계약 — seat-reservation-lab ADR-019 1단계 (구현 워커 · blind 워커 공통)

> 설계 정본은 `docs/adr/ADR-019-seat-map-query.md` 설계-1~4와 명세 `requirement-spec.md` §2 S1~S9 · S4′다. 여기서는 그 둘이 정하지 않은 시험 접점만 정한다.

## 1. 설정 옵션

| 키 | 값 | 기본 |
|---|---|---|
| `seat.seat-map.mode` | `none` · `ttl` · `redis` · `event` | `none` |
| `seat.seat-map.ttl` | Duration (`ttl` · `redis`에서 씀) | `1s` |
| `seat.seat-map.push.enabled` | `true` / `false` | `false` |

- 기동 검사: `seat.seat-map.mode=event` 또는 `seat.seat-map.push.enabled=true`인데 `seat.hold.expiry-mode`가 `sweep`이 아니면 기동이 실패한다. 실패 메시지(원인 사슬 중 한 메시지)에 `seat.seat-map`과 `sweep`이 함께 들어간다.
- 캐시의 TTL은 앱의 `Clock` 빈으로 잰다(시험은 `IntegrationTest`의 `MutableClock`을 옮겨 만료시킨다). Redis에 넣는 항목의 TTL도 같은 시계 기준으로 판정한다(Redis 자체 만료에만 기대지 않는다 — 시계를 옮기면 TTL이 지난 것으로 본다).
- `redis` 모드의 Redis 연결은 기존 `spring.data.redis.*` 설정을 쓴다. 시험은 기존 `RedisTestcontainersConfiguration`을 쓴다.

## 2. HTTP

- `GET /api/schedules/{scheduleId}/seats` → 200, JSON 배열. 원소의 필드는 정확히 `seatId` · `section` · `rowNo` · `seatNo` · `available`(bool) 다섯 개다. `seatId` 오름차순. 회차 없음 = 404(본문 `code` = `SEAT_NOT_FOUND` — 지금과 같음). 좌석 0석 회차 = `[]`.
- `available`은 만료 방식별 '보이는 상태'가 AVAILABLE이면 true, HELD · RESERVED면 false다(ADR-014 `SeatMapQuery`의 판정 그대로).
- `GET /api/schedules/{scheduleId}/seats/stream` (`Accept: text/event-stream`) — `seat.seat-map.push.enabled=true`일 때만 있다. 끔이면 404.
  - 회차 없음 = 404(스트림을 열기 전).
  - 첫 이벤트: `event: snapshot`, `data:` = 위 GET과 같은 배열(JSON 한 줄).
  - 그 뒤: `event: change`, `data:` = `[{"seatId": n, "available": bool}, …]` — 바뀐 좌석의 **보낼 때의** 보이는 상태. 같은 좌석이 여러 번 올 수 있고, 마지막 값이 지금 상태다.
  - 클라이언트가 끊으면 서버의 구독이 정리된다(지표 `seat.seatmap.push.connections`가 줄어든다).

## 3. 지표 (Micrometer — `MeterRegistry` 빈으로 읽는다)

| 이름 | 종류 | 태그 | 뜻 |
|---|---|---|---|
| `seat.seatmap.requests` | counter | `mode`, `source`(`db` · `cache`) | 맵 GET 한 번에 1. 캐시에서 답하면 `cache`, DB를 읽었으면 `db` |
| `seat.seatmap.age` | timer | `mode` | 응답한 맵의 나이(응답 시각 − 그 맵을 DB에서 읽기 시작한 시각, `Clock` 기준) |
| `seat.seatmap.redis.failures` | counter | `op`(`get` · `put`) | Redis 읽기 · 쓰기 실패 수(실패해도 응답은 DB로 200) |
| `seat.seatmap.push.connections` | gauge | 없음 | 열린 SSE 연결 수 |

## 4. 시험용 접점 (운영에서는 null — 순서 경합 시험 전용)

`com.jun.labs.seatreservation.service.impl.seatmap.SeatMapTestHooks` (Kotlin `object`):

- `@Volatile var afterDbRead: ((scheduleId: Long) -> Unit)?` — 캐시 미스 조회가 DB를 읽은 직후, 캐시에 넣기 전에 부른다(DV-1 시험: 여기서 멈춰 두고 다른 스레드가 선점을 커밋한 뒤 풀어 준다).
- `@Volatile var afterSnapshotRead: ((scheduleId: Long) -> Unit)?` — SSE 연결이 첫 맵을 DB에서 읽은 직후, `snapshot`을 보내기 전에 부른다(DV-4 시험).
- 시험은 끝날 때 둘 다 null로 되돌린다.

## 5. 무효화 경로 (S4 · S4′ — 시험이 볼 행위)

`event` 모드에서 아래 HTTP · 배치가 커밋된 뒤 다음 GET이 바뀐 `available`을 보여야 한다. 롤백되거나 거부된 요청(4xx)은 맵을 바꾸지 않는다.

- 1석 선점 `POST /api/schedules/{s}/seats/{seat}/hold` · 묶음 선점 `POST /api/schedules/{s}/hold-groups`(`seat.hold-group.enabled=true`)
- 해제 `DELETE /api/holds/{id}` · `DELETE /api/hold-groups/{id}`
- 만료 배치(시계를 옮긴 뒤 만료 배치를 직접 부르는 기존 방식 — `ExpireHoldsUseCase.expire()`)
- 대체 배정(`seat.compensation.mode=substitute` — 기존 adr017 시험 지원 코드 방식): 원래 좌석과 새 좌석 둘 다
- loadtest 초기화 `POST /internal/reset`(loadtest 프로필): 지워진 회차는 404, 다시 만든 회차는 새 시드대로

## 6. 파일 경계

- **구현 워커**: `main-app/src/main/**` · `main-app/src/test/kotlin/com/jun/labs/seatreservation/adr019impl/**` · 좌석 맵 응답의 `status`를 단언하는 기존 시험의 조정(S8 예외 — `Adr014CutoverContractTest` · `Adr014ImplSweepTest` · `Adr014ModeContractBase`(:145-158 · :504-516) · `Adr014SweepContractTest`, DV-5대로 맵은 `available=false`, 확정 뒤 RESERVED는 DB로 따로 단언) · k6 스크립트 중 좌석 맵의 `status`를 읽는 곳 · `application.yml` 주석. 그 밖의 기존 시험 단언은 바꾸지 않는다(바꿔야 하면 멈추고 보고). `adr019/`(blind)은 열지 않는다.
- **blind 워커**: `main-app/src/test/kotlin/com/jun/labs/seatreservation/adr019/**`만 쓴다. `main-app/src/main/**`(단 `src/main/resources/application.yml`은 읽어도 된다) · `adr019impl/**`은 열지 않는다. 기존 시험 코드(`src/test/**`)와 `mock-pg/**`는 읽어도 된다.
