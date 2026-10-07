import type { ThemeConfig } from 'antd';

import { tokens } from './tokens';

export const antdThemeConfig: ThemeConfig = {
  token: {
    colorPrimary: tokens.primary,
    colorText: tokens.textPrimary,
    colorTextSecondary: tokens.textSecondary,
    colorTextTertiary: tokens.textMuted,
    colorBorder: tokens.borderInput,
    colorSplit: tokens.borderDivider,
    borderRadius: tokens.controlRadius,
  },
  components: {
    Layout: {
      siderBg: tokens.siderBg,
      headerBg: '#ffffff',
      bodyBg: 'transparent',
    },
    Menu: {
      darkItemBg: tokens.siderBg,
      darkSubMenuItemBg: tokens.siderBg,
      darkItemSelectedBg: tokens.siderSelected,
      darkItemColor: 'rgba(255, 255, 255, 0.72)',
      darkItemSelectedColor: '#ffffff',
      itemBorderRadius: tokens.controlRadius,
    },
    Card: {
      borderRadiusLG: tokens.cardRadius,
    },
  },
};
