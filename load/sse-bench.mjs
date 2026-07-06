// ============================================================================
//  SSE 브로드캐스트 벤치 (Phase 5 — 리뷰 백로그 M8 실측)
//  ----------------------------------------------------------------------------
//  QueueStreamService.broadcast() 는 단일 스케줄러가 모든 emitter 를 '직렬' 순회한다.
//  구독 N 개를 열고 각 position 이벤트의 도착 시각을 기록하면, 같은 tick 의 이벤트
//  도착 스프레드(최초~최후) ≈ 직렬 브로드캐스트 1회 소요 시간이 된다.
//  이 값이 폴링 주기(1s)에 근접하면 M8 수정(병렬화)이 필요하다는 정량 근거가 된다.
//
//  전제: 워커 정지(입장 발생 시 스트림이 닫혀 관측이 끊긴다), 회원 1..N 이 ZSET 에 존재.
//  사용: node load/sse-bench.mjs [N=500] [SECS=15] [BASE=http://localhost:8080] [GOODS=1002]
// ============================================================================
import http from 'node:http'

const N = Number(process.argv[2] ?? 500)
const SECS = Number(process.argv[3] ?? 15)
const BASE = process.argv[4] ?? 'http://localhost:8080'
const GOODS = process.argv[5] ?? '1002'
const url = new URL(`${BASE}/api/v1/queue/${GOODS}/subscribe`)

const arrivals = [] // position 이벤트 도착 시각(ms, performance.now 기준)
let open = 0
let connectErrors = 0

for (let m = 1; m <= N; m++) {
  const req = http.request(
    url,
    { agent: false, headers: { 'X-Member-Id': String(m), Accept: 'text/event-stream' } },
    (res) => {
      if (res.statusCode !== 200) {
        connectErrors++
        res.resume()
        return
      }
      open++
      let buf = ''
      res.on('data', (chunk) => {
        buf += chunk.toString()
        // 프레임 경계(빈 줄)로 자르고, 마지막 미완성 프레임은 버퍼에 남긴다(청크 분할 안전).
        const frames = buf.split(/\r?\n\r?\n/)
        buf = frames.pop() ?? ''
        const now = performance.now()
        for (const f of frames) if (f.includes('event:position')) arrivals.push(now)
      })
      res.on('error', () => {})
    },
  )
  req.on('error', () => connectErrors++)
  req.end()
}

setTimeout(() => {
  arrivals.sort((a, b) => a - b)
  // tick 클러스터링: 도착 간격 250ms 초과를 tick 경계로 본다(폴링 주기 1s 의 1/4).
  const clusters = []
  let start = 0
  for (let i = 1; i <= arrivals.length; i++) {
    if (i === arrivals.length || arrivals[i] - arrivals[i - 1] > 250) {
      const slice = arrivals.slice(start, i)
      clusters.push({ count: slice.length, spreadMs: slice[slice.length - 1] - slice[0] })
      start = i
    }
  }
  // 워밍업/그레이스 구간 배제: 완전한 tick(count ≥ open의 90%)만 통계에 넣는다.
  const full = clusters.filter((c) => c.count >= open * 0.9)
  const spreads = full.map((c) => c.spreadMs).sort((a, b) => a - b)
  const pct = (p) => (spreads.length ? spreads[Math.min(spreads.length - 1, Math.floor((p / 100) * spreads.length))] : NaN)

  console.log(`connections=${open}/${N} connectErrors=${connectErrors} events=${arrivals.length}`)
  console.log(`ticks(full)=${full.length}/${clusters.length}  events/tick=${full[0]?.count ?? '-'}`)
  console.log(
    `broadcast spread ms  p50=${pct(50).toFixed(1)}  p90=${pct(90).toFixed(1)}  max=${Math.max(...spreads).toFixed(1)}`,
  )
  full.forEach((c, i) => console.log(`  tick${i + 1}: count=${c.count} spread=${c.spreadMs.toFixed(1)}ms`))
  process.exit(0)
}, SECS * 1000)
