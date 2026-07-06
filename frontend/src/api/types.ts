/** 백엔드 API 응답 타입. module-api 의 DTO 와 1:1 대응한다. */

export interface GoodsResponse {
  id: number
  name: string
  description: string | null
  price: number
  /** 판매 오픈 일시 — 서버 UTC LocalDateTime(존 정보 없음). 표시할 때 'Z' 를 붙여 해석한다. */
  openAt: string
  maxPurchasePerMember: number | null
  /** 판매 시작 여부 — 서버 시각 기준 판정(클라이언트 시계를 믿지 않는다). */
  saleOpen: boolean
}

export interface QueuePositionResponse {
  position: number
  waiting: number
}

export interface AdmissionNotice {
  goodsId: number
}

export interface OrderResponse {
  orderId: number
  orderStatus: string
  totalAmount: number
}

export interface PaymentConfirmResponse {
  orderId: number
  paymentStatus: string
  orderStatus: string
}
