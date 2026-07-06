import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { GoodsResponse } from '../api/types'
import { formatKrw, formatOpenAt } from '../format'

/** 상품 목록 화면. saleOpen(서버 판정)에 따라 판매중/판매 예정 배지를 보여준다. */
export default function GoodsListPage() {
  const [goods, setGoods] = useState<GoodsResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<GoodsResponse[]>('/api/v1/goods')
      .then(setGoods)
      .catch((e) => setError(e instanceof ApiError ? e.message : '상품 목록을 불러오지 못했습니다.'))
  }, [])

  if (error) return <p className="notice error">{error}</p>
  if (!goods) return <p className="notice">불러오는 중…</p>
  if (goods.length === 0) return <p className="notice">등록된 상품이 없습니다.</p>

  return (
    <section>
      <h2>상품</h2>
      <ul className="goods-grid">
        {goods.map((g) => (
          <li key={g.id}>
            <Link className="goods-card" to={`/goods/${g.id}`}>
              <div className="goods-card-head">
                <span className={g.saleOpen ? 'badge open' : 'badge closed'}>
                  {g.saleOpen ? '판매중' : '판매 예정'}
                </span>
                <strong>{g.name}</strong>
              </div>
              <p className="goods-desc">{g.description ?? ''}</p>
              <div className="goods-card-foot">
                <span className="price">{formatKrw(g.price)}</span>
                {!g.saleOpen && <span className="open-at">오픈 {formatOpenAt(g.openAt)}</span>}
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
