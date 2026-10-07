import { ConfigProvider } from 'antd';
import { BrowserRouter } from 'react-router-dom';

import { AuthProvider } from './auth/AuthContext';
import { AppRoutes } from './routes/AppRoutes';
import { antdThemeConfig } from './theme/antdTheme';
import './styles/global.css';

export function App() {
  return (
    <ConfigProvider theme={antdThemeConfig}>
      <BrowserRouter>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  );
}
