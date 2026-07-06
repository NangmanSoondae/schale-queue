/** 표시용 포맷터 모음. */

export function formatKrw(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}원`
}

/** 서버의 UTC LocalDateTime 문자열('Z' 없음)을 브라우저 로컬 시간으로 표시한다. */
export function formatOpenAt(openAtUtc: string): string {
  return new Date(`${openAtUtc}Z`).toLocaleString('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
