import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import { subscribeQueueStream } from '../api/sse'
import type { QueuePositionResponse } from '../api/types'
import { useMember } from '../member'

const RECONNECT_DELAY_MS = 1_000

/**
 * 대기열 화면 — 이 프로젝트의 간판 플로우.
 *
 * 진입(enqueue)은 멱등이라(P-Q2, 이미 대기 중이면 순번 유지) 새로고침해도 안전하다.
 * 순번은 SSE 'position' 이벤트로 갱신되고, 'admitted' 를 받으면 주문 화면으로 이동한다.
 * 서버 emitter 타임아웃 등으로 스트림이 닫히면 대기 중인 동안은 자동 재구독한다.
 */
export default function QueuePage() {
  const { goodsId } = useParams()
  const navigate = useNavigate()
  const { memberId } = useMember()
  const [status, setStatus] = useState<QueuePositionResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const initialWaitingRef = useRef<number | null>(null)

  useEffect(() => {
    const id = Number(goodsId)
    const controller = new AbortController()
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined

    const subscribe = () => {
      subscribeQueueStream(id, memberId, {
        onPosition: (data) => {
          initialWaitingRef.current ??= data.waiting
          setStatus(data)
        },
        onAdmitted: () => navigate(`/goods/${id}/order`),
        onClose: () => {
          // admitted 없이 닫힘(타임아웃 등) — 이탈한 게 아니면 다시 구독한다.
          if (!controller.signal.aborted) {
            reconnectTimer = setTimeout(subscribe, RECONNECT_DELAY_MS)
          }
        },
        onError: () => setError('실시간 연결이 끊어졌습니다. 새로고침해 주세요.'),
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
    </section>
  )
}
