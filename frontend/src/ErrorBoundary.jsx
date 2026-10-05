import { Component } from 'react';

/**
 * 렌더 중 터지면 React 는 화면을 통째로 비운다 — 아무 설명 없는 흰 화면이 된다.
 * 발표 중이나 제출본에서 이게 제일 곤란하므로, 무엇이 터졌는지라도 보여 준다.
 *
 * 지도(MapLibre·deck.gl)와 계산 결과 렌더가 가장 터지기 쉽다. 되돌릴 방법이 없으므로
 * 새로고침을 권하되, 저장된 시나리오는 서버에 있으니 잃는 것은 그리던 노선뿐이다.
 *
 * 에러 경계는 클래스로만 만들 수 있다 (훅에는 componentDidCatch 가 없다).
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // 콘솔에는 스택을 남긴다 — 화면에는 한 줄만 보여 준다
    console.error('렌더 중 오류', error, info?.componentStack);
  }

  render() {
    if (!this.state.error) {
      return this.props.children;
    }
    return (
      <div className="login">
        <div className="card">
          <h1>화면을 그리지 못했습니다</h1>
          <p className="muted small">
            저장된 시나리오는 서버에 있습니다. 새로고침하면 다시 불러올 수 있고,
            그리던 노선만 사라집니다.
          </p>
          <p className="error small">{String(this.state.error?.message || this.state.error)}</p>
          <button type="submit" onClick={() => window.location.reload()}>
            새로고침
          </button>
        </div>
      </div>
    );
  }
}
