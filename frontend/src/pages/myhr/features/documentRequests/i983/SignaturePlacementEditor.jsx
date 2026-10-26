import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, ValidationMessage } from '../../../../../components';
import { startPdfPageRender } from './pdfRendering';

import './i983.css';

const MAX_PAGE_WIDTH = 880;
const DEFAULT_WIDTH_FRACTION = 0.25;
const MIN_BOX_PX = 36;
const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

/** The server accepts at most this many signatures on one document. */
export const MAX_SIGNATURES = 20;

/**
 * Page-by-page PDF viewer where signatures are placed exactly where they
 * belong: click an empty spot on a page to add the signature there (as many
 * times, on as many pages, as needed), drag a box to move it, drag its corner
 * to resize it (proportions are kept), or - with a box focused - use the
 * arrow keys to move it, + / - to resize it and Delete to remove it.
 *
 * placements is a list of { page, x, y, width, height } in fractions of the
 * displayed page from its top-left corner, which is what the server uses to
 * stamp the signature into the PDF. Shared by the HR/Admin organization
 * signature (I983SigningPage) and the employee's own signature.
 */
export default function SignaturePlacementEditor({ pdf, signatureImage, placements, onChange, previewing = false, onExitPreview }) {
  const [pageNumber, setPageNumber] = useState(1);
  const [pageSize, setPageSize] = useState(null);
  const [containerWidth, setContainerWidth] = useState(0);
  const [renderError, setRenderError] = useState('');
  const containerRef = useRef(null);
  const canvasRef = useRef(null);
  const surfaceRef = useRef(null);
  const gestureRef = useRef(null);

  // Fit the page to the available width, and follow window resizes.
  useEffect(() => {
    const measure = () => setContainerWidth(Math.min(containerRef.current?.clientWidth || MAX_PAGE_WIDTH, MAX_PAGE_WIDTH));
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [pdf]);

  const list = placements || [];
  const firstPage = list.length ? Math.min(...list.map((item) => item.page)) : null;

  // Previewing opens on a page that has a signature, unless this one has one.
  useEffect(() => {
    if (previewing && firstPage && !list.some((item) => item.page === pageNumber)) setPageNumber(firstPage);
  }, [previewing, firstPage]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!pdf || !canvasRef.current || !containerWidth) return undefined;
    let task = null;
    let active = true;
    setRenderError('');
    startPdfPageRender(pdf, pageNumber, canvasRef.current, containerWidth)
      .then((render) => {
        task = render.task;
        if (!active) { task.cancel(); return undefined; }
        setPageSize(render.size);
        return task.promise;
      })
      .catch((failure) => {
        if (active && failure?.name !== 'RenderingCancelledException') setRenderError('This page could not be displayed.');
      });
    return () => { active = false; task?.cancel(); };
  }, [pdf, pageNumber, containerWidth]);

  const signable = Boolean(signatureImage);
  const aspect = signatureImage ? signatureImage.height / signatureImage.width : 0.3;
  const toBox = (item) => ({
    left: item.x * pageSize.width, top: item.y * pageSize.height,
    width: item.width * pageSize.width, height: item.height * pageSize.height,
  });
  const boxes = pageSize ? list.map((item, index) => ({ index, item })).filter(({ item }) => item.page === pageNumber) : [];
  const otherPages = [...new Set(list.filter((item) => item.page !== pageNumber).map((item) => item.page))].sort((a, b) => a - b);
  const full = list.length >= MAX_SIGNATURES;

  /** Turns a box given in page pixels into page fractions, kept inside the page. */
  const fromBoxPx = useCallback((next) => {
    const width = clamp(next.width, MIN_BOX_PX, pageSize.width);
    const height = width * aspect;
    const left = clamp(next.left, 0, pageSize.width - width);
    const top = clamp(next.top, 0, Math.max(pageSize.height - height, 0));
    return {
      page: pageNumber, x: left / pageSize.width, y: top / pageSize.height,
      width: width / pageSize.width, height: Math.min(height, pageSize.height) / pageSize.height,
    };
  }, [pageSize, pageNumber, aspect]);

  const updateAt = (index, box) => onChange(list.map((item, at) => (at === index ? fromBoxPx(box) : item)));
  const removeAt = (index) => {
    const next = list.filter((_, at) => at !== index);
    onChange(next.length ? next : null);
  };

  const localPoint = (event) => {
    const bounds = surfaceRef.current.getBoundingClientRect();
    return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
  };

  const placeAt = (event) => {
    if (!signable || previewing || !pageSize || full || event.target !== surfaceRef.current) return;
    const point = localPoint(event);
    const width = pageSize.width * DEFAULT_WIDTH_FRACTION;
    onChange([...list, fromBoxPx({ left: point.x - width / 2, top: point.y - (width * aspect) / 2, width })]);
  };

  function beginGesture(mode, index, event) {
    if (previewing) return;
    event.stopPropagation();
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    gestureRef.current = { mode, index, start: localPoint(event), box: toBox(list[index]) };
  }
  const moveGesture = (event) => {
    const gesture = gestureRef.current;
    if (!gesture) return;
    const point = localPoint(event);
    const dx = point.x - gesture.start.x;
    const dy = point.y - gesture.start.y;
    if (gesture.mode === 'move') updateAt(gesture.index, { ...gesture.box, left: gesture.box.left + dx, top: gesture.box.top + dy });
    else updateAt(gesture.index, { left: gesture.box.left, top: gesture.box.top, width: Math.min(gesture.box.width + dx, pageSize.width - gesture.box.left) });
  };
  const endGesture = () => { gestureRef.current = null; };

  const onBoxKeyDown = (index, event) => {
    if (previewing) return;
    const box = toBox(list[index]);
    const step = event.shiftKey ? 10 : 2;
    const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
    if (moves[event.key]) {
      event.preventDefault();
      updateAt(index, { ...box, left: box.left + moves[event.key][0], top: box.top + moves[event.key][1] });
    } else if (event.key === '+' || event.key === '=') {
      event.preventDefault(); updateAt(index, { ...box, width: box.width * 1.1 });
    } else if (event.key === '-') {
      event.preventDefault(); updateAt(index, { ...box, width: box.width / 1.1 });
    } else if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault(); removeAt(index);
    }
  };

  const goToPage = (next) => { setPageNumber(clamp(next, 1, pdf.numPages)); onExitPreview?.(); };
  const pageCount = pdf?.numPages || 1;

  return (
    <div className="i983-signing__viewer" ref={containerRef}>
      <div className="i983-signing__pager" role="group" aria-label="Page navigation">
        <Button type="button" variant="secondary" disabled={!pdf || pageNumber <= 1} onClick={() => goToPage(pageNumber - 1)}>Previous page</Button>
        <span>Page {pageNumber} of {pageCount}</span>
        <Button type="button" variant="secondary" disabled={!pdf || pageNumber >= pageCount} onClick={() => goToPage(pageNumber + 1)}>Next page</Button>
      </div>
      {renderError && <ValidationMessage message={renderError} variant="error" />}
      {list.length > 0 && (
        <p className="i983-signing__placement" role="status">
          {list.length} signature{list.length === 1 ? '' : 's'} placed
          {otherPages.length > 0 && <> - also on page{otherPages.length === 1 ? '' : 's'} {otherPages.map((page, at) => (
            <span key={page}>{at > 0 && ', '}<button type="button" className="i983-signing__page-link" onClick={() => goToPage(page)}>{page}</button></span>
          ))}</>}
          {full && ` (the maximum of ${MAX_SIGNATURES})`}
        </p>
      )}
      <div className={`i983-signing__page${previewing ? ' i983-signing__page--preview' : ''}${signable && !full ? ' i983-signing__page--placeable' : ''}`}>
        <canvas ref={canvasRef} aria-label={`Document page ${pageNumber}`} />
        <div ref={surfaceRef} className="i983-signing__surface" data-testid="i983-page-surface"
          style={pageSize ? { width: pageSize.width, height: pageSize.height } : undefined}
          onPointerDown={placeAt} onPointerMove={moveGesture} onPointerUp={endGesture} onPointerCancel={endGesture}>
          {boxes.map(({ index, item }, order) => {
            const box = toBox(item);
            return (
              <div key={index} className="i983-signing__box" role="button" tabIndex={0}
                aria-label={`Signature ${order + 1} on page ${item.page}. Drag or use arrow keys to move, plus and minus to resize, Delete to remove.`}
                style={{ left: box.left, top: box.top, width: box.width, height: box.height }}
                onPointerDown={(event) => beginGesture('move', index, event)} onKeyDown={(event) => onBoxKeyDown(index, event)}>
                <img src={signatureImage?.url} alt="" draggable="false" />
                {!previewing && <>
                  <span className="i983-signing__handle" aria-hidden="true" onPointerDown={(event) => beginGesture('resize', index, event)} />
                  <button type="button" className="i983-signing__remove" aria-label={`Remove signature ${order + 1} on page ${item.page}`}
                    onPointerDown={(event) => event.stopPropagation()} onClick={() => removeAt(index)}>×</button>
                </>}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
