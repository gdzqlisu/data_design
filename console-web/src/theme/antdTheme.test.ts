import { describe, expect, it } from 'vitest';

import { antdThemeConfig } from './antdTheme';
import { tokens } from './tokens';

describe('设计 token', () => {
  it('与 spec §6.3 一致', () => {
    expect(tokens.primary).toBe('#1d4ed8');
    expect(tokens.siderBg).toBe('#0e2a58');
    expect(tokens.controlRadius).toBe(4);
    expect(tokens.cardRadius).toBe(6);
    expect(tokens.textPrimary).toBe('#0f172a');
    expect(tokens.textSecondary).toBe('#64748b');
    expect(tokens.textMuted).toBe('#94a3b8');
    expect(tokens.borderInput).toBe('#d7dfec');
    expect(tokens.borderDivider).toBe('#eef1f6');
    expect(tokens.pageGradient).toBe(
      'linear-gradient(165deg, #0e2a58 0%, #123a7a 55%, #0b2347 100%)',
    );
  });

  it('Ant Design 主题用 token，不用默认蓝', () => {
    expect(antdThemeConfig.token?.colorPrimary).toBe(tokens.primary);
    expect(antdThemeConfig.token?.colorPrimary).not.toBe('#1677ff');
    expect(antdThemeConfig.token?.borderRadius).toBe(tokens.controlRadius);
    expect(antdThemeConfig.components?.Layout?.siderBg).toBe(tokens.siderBg);
    expect(antdThemeConfig.components?.Menu?.darkItemSelectedBg).toBe(tokens.siderSelected);
  });
});
