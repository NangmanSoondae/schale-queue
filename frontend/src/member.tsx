import { createContext, useContext } from 'react'

/**
 * 회원 식별 컨텍스트. 시스템에 인증이 없으므로(X-Member-Id 규약, P-Q3)
 * 데모 편의상 첫 방문 시 무작위 ID 를 발급해 localStorage 에 보관한다.
 *
 * ID 범위는 데모 시드(load/seed/demo_seed.sql)의 회원 1..1000 과 맞춘다 —
 * orders.member_id FK 때문에 존재하지 않는 회원은 주문 단계에서 실패한다.
 */
const STORAGE_KEY = 'schale.memberId'
const SEEDED_MEMBER_MAX = 1_000

export function loadMemberId(): number {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved && Number.isFinite(Number(saved))) return Number(saved)
  const generated = Math.floor(1 + Math.random() * SEEDED_MEMBER_MAX)
  localStorage.setItem(STORAGE_KEY, String(generated))
  return generated
}

export function saveMemberId(id: number): void {
  localStorage.setItem(STORAGE_KEY, String(id))
}

interface MemberContextValue {
  memberId: number
  setMemberId: (id: number) => void
}

export const MemberContext = createContext<MemberContextValue | null>(null)

export function useMember(): MemberContextValue {
  const ctx = useContext(MemberContext)
  if (!ctx) throw new Error('MemberContext 밖에서 useMember 를 호출했습니다.')
  return ctx
}
