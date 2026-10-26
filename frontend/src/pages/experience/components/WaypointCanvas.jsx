import { useEffect, useRef } from 'react';

const NODES_DEF = [
  {
    id: 'docscan',
    step: 'INGEST',
    action: 'AI Scan & OCR',
    label: 'Document Ingestion',
    x: 0.10,
    y: 0.28,
    color: '#059669',
    role: 'LLM + OCR Parser',
  },
  {
    id: 'timesheets',
    step: 'STEP 1',
    action: 'Capture Hours',
    label: 'Timesheet Core',
    x: 0.29,
    y: 0.28,
    color: '#10B981',
    role: 'Employee Logs Time',
  },
  {
    id: 'compliance',
    step: 'STEP 2',
    action: 'FLSA Overtime',
    label: 'Compliance Radar',
    x: 0.49,
    y: 0.28,
    color: '#059669',
    role: 'Automated Rule Check',
  },
  {
    id: 'reports',
    step: 'STEP 3',
    action: 'Manager Approval',
    label: 'Direct Reports',
    x: 0.69,
    y: 0.28,
    color: '#10B981',
    role: '1-Click Verification',
  },
  {
    id: 'payroll',
    step: 'STEP 4',
    action: 'Payroll Sync',
    label: 'Payroll Engine',
    x: 0.88,
    y: 0.28,
    color: '#047857',
    role: 'Gross Pay Calculation',
  },
  {
    id: 'talent',
    step: 'TALENT',
    action: 'Talent Intake',
    label: 'Talent Stream',
    x: 0.10,
    y: 0.72,
    color: '#0d9488',
    role: 'Hires to Staff',
  },
  {
    id: 'esign',
    step: 'E-SIGN',
    action: 'Native E-Sign',
    label: 'Document Studio',
    x: 0.36,
    y: 0.72,
    color: '#059669',
    role: 'Offer & I-983 Signing',
  },
  {
    id: 'r2vault',
    step: 'STORAGE',
    action: 'Cloud Storage',
    label: 'R2 Document Vault',
    x: 0.66,
    y: 0.72,
    color: '#10B981',
    role: 'Signed PDF/Receipts',
  },
  {
    id: 'audit',
    step: 'AUDIT',
    action: 'Traceability',
    label: 'Audit Ledger',
    x: 0.88,
    y: 0.72,
    color: '#065f46',
    role: 'Immutable History',
  },
];

const CONNECTIONS = [
  { from: 'docscan', to: 'timesheets', label: '1. Hours Auto-Extracted (LLM/OCR)' },
  { from: 'timesheets', to: 'compliance', label: '2. Hours to FLSA Validation' },
  { from: 'compliance', to: 'reports', label: '3. Validated Hours to Manager' },
  { from: 'reports', to: 'payroll', label: '4. Approved Hours to Payroll' },
  { from: 'talent', to: 'esign', label: 'Candidate Onboarded' },
  { from: 'esign', to: 'r2vault', label: 'Signed Offer & I-983 Vaulted' },
  { from: 'reports', to: 'r2vault', label: 'Timesheet PDF Vaulted' },
  { from: 'payroll', to: 'audit', label: 'Payroll Lock Recorded' },
  { from: 'r2vault', to: 'audit', label: 'SHA-256 Digest Logged' },
];

function getNodeAt(x, y, nodesList) {
  for (const node of nodesList) {
    const cardW = 150;
    const cardH = 58;
    const left = node.currX - cardW / 2;
    const right = node.currX + cardW / 2;
    const top = node.currY - cardH / 2;
    const bottom = node.currY + cardH / 2;

    if (x >= left && x <= right && y >= top && y <= bottom) {
      return node;
    }
  }
  return null;
}

export default function WaypointCanvas({ onNodeSelect, onNodeDoubleClick, selectedNode }) {
  const canvasRef = useRef(null);
  const selectedNodeRef = useRef(selectedNode);
  const onNodeSelectRef = useRef(onNodeSelect);
  const onNodeDoubleClickRef = useRef(onNodeDoubleClick);

  useEffect(() => {
    selectedNodeRef.current = selectedNode;
  }, [selectedNode]);

  useEffect(() => {
    onNodeSelectRef.current = onNodeSelect;
    onNodeDoubleClickRef.current = onNodeDoubleClick;
  }, [onNodeSelect, onNodeDoubleClick]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animId = null;
    const motionPreference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    let width = (canvas.width = canvas.parentElement.clientWidth);
    let height = (canvas.height = canvas.parentElement.clientHeight);

    const handleResize = () => {
      if (!canvas.parentElement) return;
      width = canvas.width = canvas.parentElement.clientWidth;
      height = canvas.height = canvas.parentElement.clientHeight;
    };
    window.addEventListener('resize', handleResize);

    const mouse = { x: width / 2, y: height / 2, targetX: width / 2, targetY: height / 2, isHovered: false };

    // Packets that flow along workflow stages
    const packets = CONNECTIONS.map((c, i) => ({
      from: c.from,
      to: c.to,
      progress: (i * 0.14) % 1,
      speed: 0.003 + (i % 2) * 0.001,
    }));

    const nodes = NODES_DEF.map((def) => ({
      ...def,
      currX: def.x * width,
      currY: def.y * height,
      baseX: def.x,
      baseY: def.y,
    }));

    const onMouseMove = (e) => {
      const rect = canvas.getBoundingClientRect();
      const scaleX = canvas.width / rect.width;
      const scaleY = canvas.height / rect.height;
      mouse.targetX = (e.clientX - rect.left) * scaleX;
      mouse.targetY = (e.clientY - rect.top) * scaleY;
      mouse.isHovered = true;

      const hovered = getNodeAt(mouse.targetX, mouse.targetY, nodes);
      canvas.style.cursor = hovered ? 'pointer' : 'default';
    };

    const onMouseLeave = () => {
      mouse.isHovered = false;
      canvas.style.cursor = 'default';
    };

    let lastClickTime = 0;
    let lastClickNodeId = null;

    const onDblClick = (e) => {
      const rect = canvas.getBoundingClientRect();
      const clickX = (e.clientX - rect.left) * (canvas.width / rect.width);
      const clickY = (e.clientY - rect.top) * (canvas.height / rect.height);
      const hitNode = getNodeAt(clickX, clickY, nodes);
      if (hitNode) {
        onNodeDoubleClickRef.current?.(hitNode.id);
      }
    };

    const onClick = (e) => {
      const rect = canvas.getBoundingClientRect();
      const clickX = (e.clientX - rect.left) * (canvas.width / rect.width);
      const clickY = (e.clientY - rect.top) * (canvas.height / rect.height);
      const hitNode = getNodeAt(clickX, clickY, nodes);
      const now = Date.now();

      if (hitNode) {
        if (lastClickNodeId === hitNode.id && now - lastClickTime < 400) {
          onNodeDoubleClickRef.current?.(hitNode.id);
        } else {
          onNodeSelectRef.current?.(hitNode.id);
        }
        lastClickTime = now;
        lastClickNodeId = hitNode.id;
      } else {
        lastClickTime = 0;
        lastClickNodeId = null;
      }
    };

    canvas.addEventListener('mousemove', onMouseMove);
    canvas.addEventListener('mouseleave', onMouseLeave);
    canvas.addEventListener('click', onClick);
    canvas.addEventListener('dblclick', onDblClick);


    const render = () => {
      ctx.clearRect(0, 0, width, height);

      const isLight = document.documentElement.getAttribute('data-theme') === 'light';

      // ── 1. Subtle Dot Matrix ────────────────────────────────────────────────
      const dotSpacing = 30;
      const dotColor = isLight ? 'rgba(15, 23, 42, 0.05)' : 'rgba(52, 211, 153, 0.07)';
      ctx.fillStyle = dotColor;
      for (let x = 15; x < width; x += dotSpacing) {
        for (let y = 15; y < height; y += dotSpacing) {
          ctx.beginPath();
          ctx.arc(x, y, 1, 0, Math.PI * 2);
          ctx.fill();
        }
      }

      // Update node positions based on dimensions
      nodes.forEach((n) => {
        n.currX = n.baseX * width;
        n.currY = n.baseY * height;
      });

      const nodeMap = new Map(nodes.map((n) => [n.id, n]));

      // ── 2. Subtle Lane Headers ──────────────────────────────────────────────
      ctx.save();
      ctx.font = '700 8.5px system-ui, -apple-system, sans-serif';
      ctx.fillStyle = isLight ? '#55645C' : '#B3C2B9';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'top';
      ctx.fillText('▸ WORKFORCE TIME & PAYROLL PIPELINE', 0.10 * width - 75, 0.10 * height);
      ctx.fillText('▸ TALENT ONBOARDING & DOCUMENT LIFECYCLE', 0.10 * width - 75, 0.56 * height);
      ctx.restore();

      // ── 3. Draw Workflow Connection Paths ───────────────────────────────────
      CONNECTIONS.forEach(({ from, to }) => {
        const n1 = nodeMap.get(from);
        const n2 = nodeMap.get(to);
        if (!n1 || !n2) return;

        const isRelated =
          selectedNodeRef.current === from || selectedNodeRef.current === to;

        ctx.save();
        ctx.beginPath();
        const midX = (n1.currX + n2.currX) / 2;
        const midY = (n1.currY + n2.currY) / 2;
        ctx.moveTo(n1.currX, n1.currY);

        // Smooth curve (upward arc for horizontal, direct for vertical)
        const isHorizontal = Math.abs(n1.currY - n2.currY) < 15;
        const curveOffset = isHorizontal ? -14 : 0;
        ctx.quadraticCurveTo(midX, midY + curveOffset, n2.currX, n2.currY);

        if (isRelated) {
          ctx.strokeStyle = isLight ? 'rgba(5, 150, 105, 0.85)' : 'rgba(52, 211, 153, 0.85)';
          ctx.lineWidth = 2.5;
        } else {
          ctx.strokeStyle = isLight ? 'rgba(203, 213, 225, 0.85)' : 'rgba(52, 211, 153, 0.2)';
          ctx.lineWidth = 1.4;
        }
        ctx.stroke();
        ctx.restore();
      });

      // ── 4. Draw Flowing Workflow Packets ────────────────────────────────────
      packets.forEach((pkt) => {
        if (!motionPreference?.matches) pkt.progress += pkt.speed;
        if (pkt.progress > 1) pkt.progress = 0;

        const n1 = nodeMap.get(pkt.from);
        const n2 = nodeMap.get(pkt.to);
        if (!n1 || !n2) return;

        const t = pkt.progress;
        const midX = (n1.currX + n2.currX) / 2;
        const isHorizontal = Math.abs(n1.currY - n2.currY) < 15;
        const curveOffset = isHorizontal ? -14 : 0;
        const midY = (n1.currY + n2.currY) / 2 + curveOffset;

        const px = (1 - t) * (1 - t) * n1.currX + 2 * (1 - t) * t * midX + t * t * n2.currX;
        const py = (1 - t) * (1 - t) * n1.currY + 2 * (1 - t) * t * midY + t * t * n2.currY;

        ctx.save();
        ctx.beginPath();
        ctx.arc(px, py, 3, 0, Math.PI * 2);
        ctx.fillStyle = isLight ? '#059669' : '#34d399';
        ctx.shadowColor = isLight ? 'rgba(5, 150, 105, 0.6)' : '#34d399';
        ctx.shadowBlur = 0;
        ctx.fill();
        ctx.restore();
      });

      // ── 5. Draw Self-Explanatory Step Cards ─────────────────────────────────
      nodes.forEach((node) => {
        const isSelected = selectedNodeRef.current === node.id;
        const cardW = 150;
        const cardH = 58;
        const rx = node.currX - cardW / 2;
        const ry = node.currY - cardH / 2;

        ctx.save();

        // Elevation Shadow
        if (isSelected) {
          ctx.shadowColor = isLight ? 'rgba(5, 150, 105, 0.35)' : 'rgba(52, 211, 153, 0.5)';
          ctx.shadowBlur = 0;
          ctx.shadowOffsetY = 4;
        } else {
          ctx.shadowColor = isLight ? 'rgba(15, 23, 42, 0.08)' : 'rgba(0, 0, 0, 0.3)';
          ctx.shadowBlur = 0;
          ctx.shadowOffsetY = 2;
        }

        // Card container
        ctx.beginPath();
        if (ctx.roundRect) {
          ctx.roundRect(rx, ry, cardW, cardH, 14);
        } else {
          ctx.rect(rx, ry, cardW, cardH);
        }

        if (isSelected) {
          ctx.fillStyle = isLight ? '#ecfdf5' : '#064e3b';
        } else {
          ctx.fillStyle = isLight ? '#ffffff' : '#0b1d17';
        }
        ctx.fill();

        // Card border
        ctx.shadowColor = 'transparent';
        if (isSelected) {
          ctx.strokeStyle = isLight ? '#059669' : '#34d399';
          ctx.lineWidth = 2;
        } else {
          ctx.strokeStyle = isLight ? '#cbd5e1' : 'rgba(52, 211, 153, 0.25)';
          ctx.lineWidth = 1;
        }
        ctx.stroke();

        // Step Badge pill (Top-Left inside card)
        ctx.fillStyle = isSelected
          ? isLight ? '#059669' : '#10b981'
          : isLight ? '#e2e8f0' : 'rgba(255, 255, 255, 0.1)';
        ctx.beginPath();
        if (ctx.roundRect) {
          ctx.roundRect(rx + 10, ry + 8, 42, 13, 6);
        } else {
          ctx.rect(rx + 10, ry + 8, 42, 13);
        }
        ctx.fill();

        ctx.font = '700 7.5px system-ui, -apple-system, sans-serif';
        ctx.fillStyle = isSelected
          ? '#ffffff'
          : isLight ? '#475569' : '#cbd5e1';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(node.step, rx + 31, ry + 14.5);

        // Action Subtitle (e.g. "Capture Hours")
        ctx.font = '600 8.5px system-ui, -apple-system, sans-serif';
        ctx.fillStyle = isSelected
          ? isLight ? '#047857' : '#a7f3d0'
          : isLight ? '#64748b' : '#94a3b8';
        ctx.textAlign = 'left';
        ctx.fillText(node.action, rx + 56, ry + 14.5);

        // Main Service Title (e.g. "Timesheet Core")
        ctx.font = '700 11.5px system-ui, -apple-system, sans-serif';
        ctx.fillStyle = isSelected
          ? isLight ? '#064e3b' : '#ffffff'
          : isLight ? '#0f172a' : '#f1f5f9';
        ctx.fillText(node.label, rx + 12, ry + 32);

        // Plain English Role (e.g. "Employee Logs Time")
        ctx.font = '500 8.5px system-ui, -apple-system, sans-serif';
        ctx.fillStyle = isSelected
          ? isLight ? '#059669' : '#34d399'
          : isLight ? '#64748b' : '#6ee7b7';
        ctx.fillText(node.role, rx + 12, ry + 45);

        ctx.restore();
      });

      animId = requestAnimationFrame(render);
    };

    animId = requestAnimationFrame(render);

    return () => {
      cancelAnimationFrame(animId);
      window.removeEventListener('resize', handleResize);
      canvas.removeEventListener('mousemove', onMouseMove);
      canvas.removeEventListener('mouseleave', onMouseLeave);
      canvas.removeEventListener('click', onClick);
      canvas.removeEventListener('dblclick', onDblClick);
    };
  }, []);

  return <canvas ref={canvasRef} className="wp-exp-canvas" />;
}
