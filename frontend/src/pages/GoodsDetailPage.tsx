import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { GoodsResponse } from '../api/types'
import { formatKrw, formatOpenAt } from '../format'

/** 상품 상세 화면. 판매중이면 대기열 진입 버튼을 노출한다. */
export default function GoodsDetailPage() {
  const { goodsId } = useParams()
  const navigate = useNavigate()
  const [goods, setGoods] = useState<GoodsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<GoodsResponse>(`/api/v1/goods/${goodsId}`)
      .then(setGoods)
      .catch((e) => setError(e instanceof ApiError ? e.message : '상품을 불러오지 못했습니다.'))
  }, [goodsId])

  if (error) return <p className="notice error">{error}</p>
  if (!goods) return <p className="notice">불러오는 중…</p>

  return (
    <section className="goods-detail">
      <Link className="back" to="/">← 상품 목록</Link>
      <h2>{goods.name}</h2>
      <p className="goods-desc">{goods.description ?? ''}</p>
      <dl className="goods-meta">
        <dt>가격</dt>
        <dd className="price">{formatKrw(goods.price)}</dd>
        <dt>1인 구매 한도</dt>
        <dd>{goods.maxPurchasePerMember ?? '제한 없음'}개</dd>
        <dt>판매 오픈</dt>
        <dd>{formatOpenAt(goods.openAt)}</dd>
      </dl>
      {goods.saleOpen ? (
        <button className="primary" onClick={() => navigate(`/goods/${goods.id}/queue`)}>
          대기열 진입
        </button>
      ) : (
        <button className="primary" disabled>
          판매 시작 전 — {formatOpenAt(goods.openAt)} 오픈
        </button>
      )}
    </section>
  )
}
