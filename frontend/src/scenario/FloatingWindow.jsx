import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { clampPosition, initialRect, GAP } from './windowPlacement';

/**
 * 지도 위에 띄우는 상세 창 — 그래프와 수단별 비교표를 담는다.
 *
 * **모달이 아니다.** 뒤를 어둡게 하지도, 지도 조작을 막지도 않는다(창 밖은 그대로 지도다).
 * 표에서 수단 행을 고르면 뒤의 지도가 그 수단의 정차역으로 바뀌어야 해서, 창이 열린 채로 지도를 봐야 한다.
 * 제목줄을 끌어 옮기고, 접고(제목줄만 남김), 오른쪽 아래 모서리로 크기를 바꾼다.
 *
 * `document.body` 로 포탈을 띄운다 — 결과 패널 안에 두면 `.panel section` 의 구분선·여백이 걸리고
 * 패널의 스크롤에 갇힌다. 위치(`pos`)는 부모가 들고 있어 창을 닫았다 열어도 그 자리에 다시 뜬다.
 */
export default function FloatingWindow({ title, pos, onMove, onClose, children }) {
  const ref = useRef(null);
  const drag = useRef(null);
  const [collapsed, setCollapsed] = useState(false);

  // 처음 열 때만 자리를 정한다 — 이 값은 부모가 올린 `pos` 가 없을 때의 기본이다
  const rect = pos || initialRect({
    vw: window.innerWidth,
    vh: window.innerHeight,
    panelLeft: document.querySelector('.panel')?.getBoundingClientRect().left ?? window.innerWidth,
  });

  const viewport = () => ({ vw: window.innerWidth, vh: window.innerHeight });

  function onPointerDown(e) {
    if (e.target.closest('button')) return;       // 접기·닫기 버튼은 끌기가 아니다
    const box = ref.current.getBoundingClientRect();
    drag.current = { dx: e.clientX - box.left, dy: e.clientY - box.top };
    e.currentTarget.setPointerCapture(e.pointerId);
  }

  function onPointerMove(e) {
    if (!drag.current) return;
    const box = ref.current.getBoundingClientRect();
    onMove({
      ...rect,
      ...clampPosition(
        { x: e.clientX - drag.current.dx, y: e.clientY - drag.current.dy },
        { w: box.width },
        viewport(),
      ),
    });
  }

  function onPointerUp() {
    drag.current = null;
  }

  return createPortal(
    <div
      ref={ref}
      className={`float-win${collapsed ? ' collapsed' : ''}`}
      role="dialog"
      aria-modal="false"
      aria-label={title}
      tabIndex={-1}
      // 처음 열 때 한 번 정한 크기만 준다 — `resize: both` 가 고친 크기를 React 가 덮어쓰지 않는다
      // 최대 높이는 **자리에 따라** 줄인다 — 아래로 끌어 내리면 창 아래가 화면 밖으로 나가 내용이 잘리므로, 남은 높이만큼만 두고 안쪽을 스크롤한다
      style={{
        left: rect.x, top: rect.y, width: rect.w,
        height: collapsed ? undefined : rect.h,
        maxHeight: `calc(100vh - ${rect.y}px - ${GAP}px)`,
      }}
      onKeyDown={(e) => { if (e.key === 'Escape') onClose(); }}
    >
      <div
        className="float-win-bar"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <strong>{title}</strong>
        <span className="float-win-hint">제목줄을 끌어 옮길 수 있습니다</span>
        <button
          type="button"
          className="float-win-btn"
          onClick={() => setCollapsed((c) => !c)}
          aria-expanded={!collapsed}
        >
          {collapsed ? '펼치기' : '접기'}
        </button>
        <button type="button" className="float-win-btn" onClick={onClose} aria-label="상세 창 닫기">
          닫기
        </button>
      </div>
      {!collapsed && <div className="float-win-body">{children}</div>}
    </div>,
    document.body,
  );
}
