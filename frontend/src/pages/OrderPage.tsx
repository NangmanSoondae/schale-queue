import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { GoodsResponse, OrderResponse, PaymentConfirmResponse } from '../api/types'
import { formatKrw } from '../format'
import { useMember } from '../member'

type Step =
  | { name: 'ordering' }
  | { name: 'paying'; order: OrderResponse }
  | { name: 'done'; payment: PaymentConfirmResponse; order: OrderResponse }

/**
 * 주문/결제 화면. 입장 토큰 보유자만 주문이 성공한다(P-O1 — 토큰은 주문 시 1회 소비).
 * 결제는 PG 승인 시뮬레이션(UC-06)으로, 확정 전 만료되면 워커가 재고를 해제한다(UC-07).
 */
export default function OrderPage() {
  const { goodsId } = useParams()
  const { memberId } = useMember()
  const [goods, setGoods] = useState<GoodsResponse | null>(null)
  const [quantity, setQuantity] = useState(1)
  const [step, setStep] = useState<Step>({ name: 'ordering' })
  const [error, setError] = useState<{ code: string; message: string } | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api<GoodsResponse>(`/api/v1/goods/${goodsId}`).then(setGoods).catch(() => setGoods(null))
  }, [goodsId])

  const maxQuantity = goods?.maxPurchasePerMember ?? 1

  const placeOrder = async () => {
    setBusy(true)
    setError(null)
    try {
      const order = await api<OrderResponse>('/api/v1/orders', {
        method: 'POST',
        memberId,
        body: JSON.stringify({ goodsId: Number(goodsId), quantity }),
      })
      setStep({ name: 'paying', order })
    } catch (e) {
      setError(e instanceof ApiError
        ? { code: e.code, message: e.message }
        : { code: 'UNKNOWN', message: '주문에 실패했습니다.' })
    } finally {
      setBusy(false)
    }
  }

  const confirmPayment = async (order: OrderResponse) => {
    setBusy(true)
    setError(null)
    try {
      const payment = await api<PaymentConfirmResponse>(
        `/api/v1/payments/${order.orderId}/confirm`,
        { method: 'POST', memberId },
      )
      setStep({ name: 'done', payment, order })
    } catch (e) {
      setError(e instanceof ApiError
        ? { code: e.code, message: e.message }
        : { code: 'UNKNOWN', message: '결제 확정에 실패했습니다.' })
    } finally {
      setBusy(false)
    }
  }

  if (step.name === 'done') {
    return (
      <section className="order-panel">
        <h2>🎉 구매 완료</h2>
        <p className="notice">
          주문 #{step.payment.orderId} 이 확정되었습니다. ({formatKrw(step.order.totalAmount)})
        </p>
        <Link className="back" to="/">← 상품 목록으로</Link>
      </section>
    )
  }

  return (
    <section className="order-panel">
      <h2>{step.name === 'ordering' ? '주문' : '결제'}</h2>
      {goods && <p className="order-goods">{goods.name} — {formatKrw(goods.price)}</p>}

      {step.name === 'ordering' ? (
        <>
          <label className="quantity-row">
            수량
            <select value={quantity} onChange={(e) => setQuantity(Number(e.target.value))}>
              {Array.from({ length: maxQuantity }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>{n}</option>
              ))}
            </select>
          </label>
          {goods && <p className="order-total">합계 {formatKrw(goods.price * quantity)}</p>}
          <button className="primary" onClick={placeOrder} disabled={busy}>
            {busy ? '주문 중…' : '주문하기'}
          </button>
        </>
      ) : (
        <>
          <p className="notice">
            주문 #{step.order.orderId} 생성 완료 — 합계 {formatKrw(step.order.totalAmount)}.
            결제를 확정해 주세요. (제한 시간 내 미확정 시 주문이 만료됩니다)
          </p>
          <button className="primary" onClick={() => confirmPayment(step.order)} disabled={busy}>
            {busy ? '확정 중…' : '결제 확정 (PG 승인 시뮬레이션)'}
          </button>
        </>
      )}

      {error && (
        <div className="notice error">
          <p>{error.message}</p>
          {error.code === 'ADMISSION_REQUIRED' && (
            <Link className="back" to={`/goods/${goodsId}/queue`}>← 대기열로 다시 진입</Link>
          )}
        </div>
      )}
    </section>
  )
}
