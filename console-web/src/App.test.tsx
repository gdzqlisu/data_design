import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('renders the product name', () => {
    render(<App />);

    expect(screen.getByText('信贷风控决策引擎')).toBeInTheDocument();
  });
});
