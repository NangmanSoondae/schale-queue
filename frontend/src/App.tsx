import { useMemo, useState } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { loadMemberId, MemberContext, saveMemberId } from './member'
import GoodsDetailPage from './pages/GoodsDetailPage'
import GoodsListPage from './pages/GoodsListPage'
import OrderPage from './pages/OrderPage'
import QueuePage from './pages/QueuePage'

export default function App() {
  const [memberId, setMemberIdState] = useState(loadMemberId)
  const member = useMemo(
    () => ({
      memberId,
      setMemberId: (id: number) => {
        saveMemberId(id)
        setMemberIdState(id)
      },
    }),
    [memberId],
  )

  return (
    <MemberContext.Provider value={member}>
      <BrowserRouter>
        <header className="app-header">
          <h1>🎫 Schale Queue</h1>
          <label className="member-box">
            회원 ID
            <input
              type="number"
              value={memberId}
              onChange={(e) => {
                const next = Number(e.target.value)
                if (Number.isFinite(next) && next > 0) member.setMemberId(next)
              }}
            />
          </label>
        </header>
        <main className="app-main">
          <Routes>
            <Route path="/" element={<GoodsListPage />} />
            <Route path="/goods/:goodsId" element={<GoodsDetailPage />} />
            <Route path="/goods/:goodsId/queue" element={<QueuePage />} />
            <Route path="/goods/:goodsId/order" element={<OrderPage />} />
          </Routes>
        </main>
      </BrowserRouter>
    </MemberContext.Provider>
  )
}
