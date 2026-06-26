#!/usr/bin/env bash
# ============================================================================
#  Schale Queue 부하 테스트 오케스트레이터 (Phase 3 ⑥)
#  ----------------------------------------------------------------------------
#  앱 생명주기(bootRun, VT 토글)는 직접 관리하지 않는다 — 이 스크립트는 시드/리셋/
#  k6 구동/오버셀 검증의 '재현 가능한 조각'을 제공한다. (앱 기동·VT 토글은 README 참조)
#
#  사용:
#    ./load/run.sh seed-db                # 마스터 데이터(goods/stock/member) 시드
#    ./load/run.sh seed-tokens [N=1000]   # admission:1001:i 입장 토큰 N개 시드(Redis)
#    ./load/run.sh reset-orders           # 주문/항목/결제 비우고 재고 원복(B2 반복용)
#    ./load/run.sh b1 [VUS=100] [DUR=30s] # B1 큐 지연(S1/S2) k6 실행
#    ./load/run.sh b2 [VUS=200] [N=1000]  # B2 오버셀/주문지연(S4/S5) k6 실행 + 검증
#    ./load/run.sh oversell-check         # 재고 불변식(sold<=total, remain=total-sold)
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
set -a; [ -f .env ] && . ./.env; set +a

DB_C="${DB_CONTAINER:-schale-mariadb}"
REDIS_C="${REDIS_CONTAINER:-schale-redis}"
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:latest}"
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:?DB_ROOT_PASSWORD 가 .env 에 필요합니다}"
DB_NAME="${DB_NAME:?DB_NAME 가 .env 에 필요합니다}"

mariadb_exec() { docker exec -i "$DB_C" mariadb -u root -p"$DB_ROOT_PASSWORD" "$DB_NAME" "$@" 2>/dev/null; }
k6_run() { # $1=script, rest=-e KEY=VAL ...
  local script="$1"; shift
  # 스크립트를 stdin 으로 주입한다(k6 run -). Git Bash(MSYS)의 볼륨 마운트/인자 경로 변환
  # 문제를 원천 회피한다(호스트 파일은 bash 의 < 리다이렉트로 읽으므로 경로 변환 영향 없음).
  docker run --rm -i --add-host host.docker.internal:host-gateway \
    -e "BASE_URL=$BASE_URL" "$@" "$K6_IMAGE" run - < "$ROOT/load/k6/$script"
}

seed_db() {
  echo "[seed-db] goods/stock/member 시드…"
  mariadb_exec < load/seed/seed.sql
  echo "[seed-db] 완료: $(mariadb_exec -N -B -e 'SELECT COUNT(*) FROM member') 회원"
}

seed_tokens() {
  local n="${1:-1000}"
  echo "[seed-tokens] admission:1001:1..$n (TTL 600s) 시드…"
  seq 1 "$n" | sed 's#.*#SET admission:1001:& 1 EX 600#' | docker exec -i "$REDIS_C" redis-cli --pipe
}

reset_orders() {
  echo "[reset-orders] 주문/항목/결제 비우고 재고 원복…"
  mariadb_exec <<'SQL'
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM purchase_slot; DELETE FROM order_item; DELETE FROM payment; DELETE FROM orders;
SET FOREIGN_KEY_CHECKS=1;
UPDATE stock SET available_quantity = total_quantity, reserved_quantity = 0, sold_quantity = 0;
SQL
}

metric() { # $1 = Actuator metric name → VALUE 추출
  curl -s "$BASE_URL/actuator/metrics/$1" 2>/dev/null | grep -oE '"value":[0-9.eE+]+' | head -1 | cut -d: -f2
}

sample_metrics() { # $1 = 초, 부하 중 피크 스레드/힙 추적
  local secs="${1:-40}" peakThreads=0 peakMemMB=0 t m memMB
  for _ in $(seq 1 "$secs"); do
    t=$(metric jvm.threads.live); m=$(metric jvm.memory.used)
    [ -n "$t" ] && t=${t%.*} && [ "$t" -gt "$peakThreads" ] 2>/dev/null && peakThreads=$t
    if [ -n "$m" ]; then memMB=$(awk "BEGIN{printf \"%d\", $m/1048576}"); [ "$memMB" -gt "$peakMemMB" ] 2>/dev/null && peakMemMB=$memMB; fi
    sleep 1
  done
  echo "[sample] PEAK jvm.threads.live=$peakThreads  jvm.memory.used=${peakMemMB}MB  (peak threads.peak=$(metric jvm.threads.peak | cut -d. -f1))"
}

oversell_check() {
  # 예약 모델(P-S2): 주문은 available-- reserved++. 결제 미연결이라 sold=0, 성공 주문 수=reserved.
  local total available reserved sold ordered
  read -r total available reserved sold < <(mariadb_exec -N -B -e \
    "SELECT total_quantity, available_quantity, reserved_quantity, sold_quantity FROM stock WHERE goods_id=1001")
  ordered=$(mariadb_exec -N -B -e "SELECT COALESCE(SUM(quantity),0) FROM order_item WHERE goods_id=1001")
  echo "[oversell-check] total=$total available=$available reserved=$reserved sold=$sold | 주문수량합=$ordered"
  if [ "$available" -ge 0 ] \
     && [ "$((available + reserved + sold))" -eq "$total" ] \
     && [ "$((reserved + sold))" -le "$total" ] \
     && [ "$reserved" -eq "$ordered" ]; then
    echo "[oversell-check] PASS ✅ (오버셀 0, 합계 불변식 성립, 예약수=주문수량)"
  else
    echo "[oversell-check] FAIL ❌ (불변식 위반)"; return 1
  fi
}

cmd="${1:-}"; shift || true
case "$cmd" in
  seed-db)        seed_db ;;
  seed-tokens)    seed_tokens "${1:-1000}" ;;
  reset-orders)   reset_orders ;;
  oversell-check) oversell_check ;;
  b1)             k6_run queue.js -e "VUS=${1:-100}" -e "DURATION=${2:-30s}" ;;
  metrics)        echo "threads.live=$(metric jvm.threads.live) threads.peak=$(metric jvm.threads.peak) mem.used=$(awk "BEGIN{printf \"%dMB\", $(metric jvm.memory.used)/1048576}")" ;;
  sample)         sample_metrics "${1:-40}" ;;
  bench-ramp)     k6_run enqueue-ramp.js -e "MAXVUS=${1:-1000}" ;;
  sse-hold)       k6_run sse-hold.js -e "VUS=${1:-500}" -e "DURATION=${2:-25s}" ;;
  b2)
    n="${2:-1000}"
    reset_orders
    seed_tokens "$n"
    # k6 threshold(주문 p99) 미충족이어도 오버셀 검증은 반드시 수행한다(불변식이 최우선).
    k6_run order.js -e "VUS=${1:-200}" -e "MEMBERS=$n" -e "GOODS_ID=1001" || echo "[b2] k6 threshold 일부 미충족 — 검증 계속"
    oversell_check
    ;;
  # S4 대표값: 큐가 입장률로 조절한 '저동시성' 주문 경로(대재고 1002)를 측정한다.
  s4-baseline)
    vus="${1:-20}"; n="${2:-1000}"
    reset_orders
    seq 1 "$n" | sed 's#.*#SET admission:1002:& 1 EX 600#' | docker exec -i "$REDIS_C" redis-cli --pipe
    k6_run order.js -e "VUS=$vus" -e "MEMBERS=$n" -e "GOODS_ID=1002"
    ;;
  *) sed -n '2,30p' "$0"; exit 1 ;;
esac
