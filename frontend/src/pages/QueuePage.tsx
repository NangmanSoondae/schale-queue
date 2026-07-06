import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import { subscribeQueueStream } from '../api/sse'
import type { QueuePositionResponse } from '../api/types'
import { useMember } from '../member'

// 재연결 정책(리뷰2 M-8/M-9): 오류에도 재구독하되, 지수 백오프 + 지터로 재구독 폭주를 막는다.
// 서버 emitter 타임아웃(30분)은 오픈런 특성상 수천 스트림이 '동시에' 닫히므로, 고정 지연이면
// 재구독 스파이크가 주기적으로 반복된다 — 지터가 이를 흩뜨린다.
const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 15_000
const RECONNECT_JITTER_MS = 3_000

/**
 * 대기열 화면 — 이 프로젝트의 간판 플로우.
 *
 * 진입(enqueue)은 멱등이라(P-Q2, 이미 대기 중이면 순번 유지) 새로고침해도 안전하다.
 * 순번은 SSE 'position' 이벤트로 갱신되고, 'admitted' 를 받으면 주문 화면으로 이동한다.
 * 스트림이 닫히거나(타임아웃) 오류가 나도 대기 중인 동안은 백오프+지터로 자동 재구독한다 —
 * API 순단 수 초가 '영구 오류 화면 + 입장 알림 유실'로 굳지 않게(리뷰2 M-8).
 */
export default function QueuePage() {
  const { goodsId } = useParams()
  const navigate = useNavigate()
  const { memberId } = useMember()
  const [status, setStatus] = useState<QueuePositionResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [reconnecting, setReconnecting] = useState(false)
  const initialWaitingRef = useRef<number | null>(null)
  const attemptRef = useRef(0)

  useEffect(() => {
    const id = Number(goodsId)
    const controller = new AbortController()
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined

    const scheduleResubscribe = () => {
      if (controller.signal.aborted) return
      const backoff = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** Math.min(attemptRef.current, 4))
      attemptRef.current += 1
      reconnectTimer = setTimeout(subscribe, backoff + Math.random() * RECONNECT_JITTER_MS)
    }

    const subscribe = () => {
      subscribeQueueStream(id, memberId, {
        onPosition: (data) => {
          attemptRef.current = 0        // 이벤트 수신 = 연결 정상 → 백오프 리셋
          setReconnecting(false)
          initialWaitingRef.current ??= data.waiting
          setStatus(data)
        },
        onAdmitted: () => navigate(`/goods/${id}/order`),
        onClose: () => {
          // admitted 없이 닫힘(타임아웃 등) — 이탈한 게 아니면 다시 구독한다.
          scheduleResubscribe()
        },
        onError: () => {
          // 일시 장애(API 재기동·프록시 순단)일 수 있다 — 마지막 순번을 유지한 채 재시도한다.
          setReconnecting(true)
          scheduleResubscribe()
        },
      }, controller.signal)
    }

    api<QueuePositionResponse>(`/api/v1/queue/${id}/entries`, { method: 'POST', memberId })
      .then((data) => {
        initialWaitingRef.current = data.waiting
        setStatus(data)
        subscribe()
      })
      .catch((e) => {
        if (e instanceof ApiError && e.code === 'SALE_NOT_OPEN') {
          setError('아직 판매 시작 전입니다.')
        } else {
          setError(e instanceof ApiError ? e.message : '대기열 진입에 실패했습니다.')
        }
      })

    return () => {
      controller.abort()
      clearTimeout(reconnectTimer)
    }
  }, [goodsId, memberId, navigate])

  if (error) {
    return (
      <section className="queue-panel">
        <p className="notice error">{error}</p>
        <Link className="back" to={`/goods/${goodsId}`}>← 상품으로 돌아가기</Link>
      </section>
    )
  }

  // 입장 직전(대기열에서 빠졌지만 admitted 이벤트 대기 중)에는 position=0 이 올 수 있다.
  const aboutToEnter = status !== null && status.position === 0
  const progress = initialWaitingRef.current
    ? Math.max(0, Math.min(1, 1 - (status?.position ?? 0) / Math.max(1, initialWaitingRef.current)))
    : 0

  return (
    <section className="queue-panel">
      <h2>대기열</h2>
      {status === null ? (
        <p className="notice">대기열 진입 중…</p>
      ) : aboutToEnter ? (
        <p className="queue-position entering">곧 입장합니다…</p>
      ) : (
        <>
          <p className="queue-label">내 순번</p>
          <p className="queue-position">{status.position.toLocaleString('ko-KR')}</p>
          <p className="queue-waiting">전체 대기 {status.waiting.toLocaleString('ko-KR')}명</p>
          <div className="progress-track">
            <div className="progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
          <p className="queue-hint">순서가 되면 자동으로 주문 화면으로 이동합니다. 이 화면을 유지해 주세요.</p>
        </>
      )}
      {reconnecting && <p className="notice">실시간 연결 재시도 중… (대기 순번은 유지됩니다)</p>}
    </section>
  )
}
