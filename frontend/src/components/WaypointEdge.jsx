import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, useReactFlow } from '@xyflow/react';
import { useCallback } from 'react';

/** Returns the perpendicular distance from point p to the segment a→b */
function pointToSegmentDist(p, a, b) {
    const dx = b.x - a.x, dy = b.y - a.y;
    const lenSq = dx * dx + dy * dy;
    if (lenSq === 0) return Math.hypot(p.x - a.x, p.y - a.y);
    const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq));
    return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

export default function WaypointEdge({
    id,
    sourceX, sourceY, sourcePosition,
    targetX, targetY, targetPosition,
    data = {},
    style = {},
    markerEnd,
    label,
}) {
    const { setEdges, screenToFlowPosition } = useReactFlow();
    const waypoints = data.waypoints ?? [];

    // No waypoints → use React Flow's smooth-step path (orthogonal, avoids crossings).
    // With waypoints → straight-line segments through the user-placed points.
    let d, labelX, labelY;
    if (waypoints.length === 0) {
        [d, labelX, labelY] = getSmoothStepPath({
            sourceX, sourceY, sourcePosition,
            targetX, targetY, targetPosition,
            borderRadius: 8,
        });
    } else {
        const allPoints = [
            { x: sourceX, y: sourceY },
            ...waypoints,
            { x: targetX, y: targetY },
        ];
        d = allPoints.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ');
        const midIdx = Math.floor(allPoints.length / 2);
        labelX = (allPoints[midIdx - 1].x + allPoints[midIdx].x) / 2;
        labelY = (allPoints[midIdx - 1].y + allPoints[midIdx].y) / 2;
    }

    // Click on edge path → insert a new waypoint at the nearest segment
    const handlePathClick = useCallback((e) => {
        e.stopPropagation();
        const flowPos = screenToFlowPosition({ x: e.clientX, y: e.clientY });

        setEdges(edges => edges.map(edge => {
            if (edge.id !== id) return edge;

            const pts = [
                { x: sourceX, y: sourceY },
                ...(edge.data?.waypoints ?? []),
                { x: targetX, y: targetY },
            ];

            // Find the closest segment and insert the new waypoint there
            let minDist = Infinity, insertIdx = 0;
            for (let i = 0; i < pts.length - 1; i++) {
                const dist = pointToSegmentDist(flowPos, pts[i], pts[i + 1]);
                if (dist < minDist) { minDist = dist; insertIdx = i; }
            }

            const prev = edge.data?.waypoints ?? [];
            const newWaypoints = [
                ...prev.slice(0, insertIdx),
                flowPos,
                ...prev.slice(insertIdx),
            ];

            return { ...edge, data: { ...edge.data, waypoints: newWaypoints } };
        }));
    }, [id, setEdges, screenToFlowPosition, sourceX, sourceY, targetX, targetY]);

    // Mouse down on a waypoint dot → start dragging it
    const handleWaypointMouseDown = useCallback((e, wpIndex) => {
        e.stopPropagation();
        e.preventDefault();

        const onMouseMove = (moveEvent) => {
            const flowPos = screenToFlowPosition({ x: moveEvent.clientX, y: moveEvent.clientY });
            setEdges(edges => edges.map(edge => {
                if (edge.id !== id) return edge;
                const updated = [...(edge.data?.waypoints ?? [])];
                updated[wpIndex] = flowPos;
                return { ...edge, data: { ...edge.data, waypoints: updated } };
            }));
        };

        const onMouseUp = () => {
            window.removeEventListener('mousemove', onMouseMove);
            window.removeEventListener('mouseup', onMouseUp);
        };

        window.addEventListener('mousemove', onMouseMove);
        window.addEventListener('mouseup', onMouseUp);
    }, [id, setEdges, screenToFlowPosition]);

    // Double-click a waypoint dot → remove it
    const handleWaypointDoubleClick = useCallback((e, wpIndex) => {
        e.stopPropagation();
        setEdges(edges => edges.map(edge => {
            if (edge.id !== id) return edge;
            const newWaypoints = (edge.data?.waypoints ?? []).filter((_, i) => i !== wpIndex);
            return { ...edge, data: { ...edge.data, waypoints: newWaypoints } };
        }));
    }, [id, setEdges]);

    return (
        <>
            {/* Rendered first so BaseEdge's interaction path sits on top for selection */}
            <BaseEdge
                path={d}
                markerEnd={markerEnd}
                style={style}
                labelX={labelX}
                labelY={labelY}
                label={label}
                labelStyle={{ fill: '#0f172a', fontWeight: 600 }}
                labelBgStyle={{ fill: '#ffffff', fillOpacity: 0.92 }}
                labelBgBorderRadius={6}
            />

            {/*
              Wide transparent path rendered on top.
              Receives clicks for adding waypoints; transparent stroke still fires
              pointer events in SVG (unlike visibility:hidden or display:none).
            */}
            <path
                d={d}
                fill="none"
                stroke="transparent"
                strokeWidth={20}
                style={{ cursor: 'crosshair' }}
                onClick={handlePathClick}
            />

            {/* Waypoint handles rendered in the HTML overlay above the SVG */}
            <EdgeLabelRenderer>
                {waypoints.map((wp, i) => (
                    <div
                        key={i}
                        className="nodrag nopan"
                        title="Drag to move · Double-click to remove"
                        style={{
                            position: 'absolute',
                            transform: `translate(-50%, -50%) translate(${wp.x}px, ${wp.y}px)`,
                            width: 12,
                            height: 12,
                            borderRadius: '50%',
                            background: '#ffffff',
                            border: '2px solid #475569',
                            cursor: 'grab',
                            zIndex: 10,
                            pointerEvents: 'all',
                            boxShadow: '0 1px 4px rgba(0,0,0,0.3)',
                        }}
                        onMouseDown={(e) => handleWaypointMouseDown(e, i)}
                        onDoubleClick={(e) => handleWaypointDoubleClick(e, i)}
                    />
                ))}
            </EdgeLabelRenderer>
        </>
    );
}
