import { Tag } from 'antd';

/** 环境标识取构建期注入的 VITE_APP_ENV，缺省按 dev 处理。 */
export function currentEnv(): string {
  return import.meta.env.VITE_APP_ENV ?? 'dev';
}

export function EnvBadge() {
  const env = currentEnv();
  const isProd = env === 'prod';

  return (
    <Tag color={isProd ? '#a8071a' : '#d46b08'} style={{ marginInlineEnd: 0, fontWeight: 600 }}>
      {isProd ? '生产环境' : '开发环境'}
    </Tag>
  );
}
