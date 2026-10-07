/**
 * 视觉规范（spec §6.3「风格 A · 稳健金融蓝」）。
 * 全站颜色与圆角的唯一真相，改风格只改这里。
 */
export const tokens = {
  pageGradient: 'linear-gradient(165deg, #0e2a58 0%, #123a7a 55%, #0b2347 100%)',
  gridLine: 'rgba(255, 255, 255, 0.05)',
  gridSize: '26px',
  cardRadius: 6,
  cardShadow: '0 18px 40px -18px rgba(0, 0, 0, 0.6)',
  cardAccentHeight: 3,
  primary: '#1d4ed8',
  textPrimary: '#0f172a',
  textSecondary: '#64748b',
  textMuted: '#94a3b8',
  borderInput: '#d7dfec',
  borderDivider: '#eef1f6',
  controlRadius: 4,
  siderBg: '#0e2a58',
  siderSelected: '#1d4ed8',
} as const;
