import dagre from 'dagre';
import {useCallback, useEffect, useMemo, useRef} from 'react';
import {
    addEdge,
    Background,
    Controls,
    Handle,
    MarkerType,
    Position,
    ReactFlow,
    useEdgesState,
    useNodesState,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css'
import WaypointEdge from './WaypointEdge';

const PROCESS_NODE_SIZE = {width: 240, height: 120};
const DECISION_NODE_SIZE = {width: 260, height: 160};

const handleStyle = {
    width: 12,
    height: 12,
    borderRadius: '999px',
    background: '#1f2937',
    border: '2px solid #fff',
};

function ProcessNode({data}) {
    return (
        <div style = {{
            width: `${PROCESS_NODE_SIZE.width}px`,
            minHeight: `${PROCESS_NODE_SIZE.height}px`,
            padding: '16px 18px',
            borderRadius: '14px',
            border: '2px solid #1d4ed8',
            backgroundColor: '#eff6ff',
            boxShadow: '0 10px 24px rgba(29, 78, 216, 0.12)',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            gap: '8px',
        }}>
            <Handle type = "target" position = {Position.Left} style = {handleStyle} />
            <div style = {{fontSize: '0.75rem', fontWeight: 700, letterSpacing: '0.08em', color: '#1d4ed8', textTransform: 'uppercase'}}>
                Process
            </div>
            <div style = {{fontSize: '1rem', fontWeight: 700, color: '#111827'}}>
                {data.label}
            </div>
            <div style = {{fontSize: '0.85rem', lineHeight: 1.45, color: '#374151'}}>
                {data.summary}
            </div>
            <Handle type = "source" position = {Position.Right} style = {handleStyle} />
        </div>
    );
}

function DecisionNode({data}) {
    return (
        <div style = {{
            width: `${DECISION_NODE_SIZE.width}px`,
            height: `${DECISION_NODE_SIZE.height}px`,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            position: 'relative',
        }}>
            <Handle type = "target" position = {Position.Left} style = {handleStyle} />
            <div style = {{
                width: '154px',
                height: '154px',
                transform: 'rotate(45deg)',
                border: '2px solid #b45309',
                backgroundColor: '#fffbeb',
                boxShadow: '0 10px 24px rgba(180, 83, 9, 0.16)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
            }}>
                <div style = {{
                    transform: 'rotate(-45deg)',
                    width: '108px',
                    textAlign: 'center',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '6px',
                }}>
                    <div style = {{fontSize: '0.75rem', fontWeight: 700, letterSpacing: '0.08em', color: '#b45309', textTransform: 'uppercase'}}>
                        Decision
                    </div>
                    <div style = {{fontSize: '0.95rem', fontWeight: 700, color: '#111827'}}>
                        {data.label}
                    </div>
                    <div style = {{fontSize: '0.78rem', lineHeight: 1.35, color: '#4b5563'}}>
                        {data.summary}
                    </div>
                </div>
            </div>
            <Handle type = "source" position = {Position.Right} style = {handleStyle} />
        </div>
    );
}

const nodeTypes = {
    process: ProcessNode,
    decision: DecisionNode,
};

const edgeTypes = {
    waypoint: WaypointEdge,
};

const normalizeNode = (node, index) => {
    const id = String(node.id ?? node.nodeId ?? node.sceneId ?? `node-${index + 1}`);
    const label = node.data?.label ?? node.label ?? node.title ?? node.name ?? `Scene ${index + 1}`;
    const summary = node.data?.summary ?? node.summary ?? node.description ?? '';
    const type = node.type ?? node.data?.type ?? null;

    return {
        ...node,
        id,
        type,
        data: {
            ...node.data,
            label,
            summary,
        },
    };
};

const normalizeEdge = (edge, index) => {
    const source = edge.source ?? edge.from ?? edge.sourceId ?? edge.fromNodeId;
    const target = edge.target ?? edge.to ?? edge.targetId ?? edge.toNodeId;

    if (!source || !target) {
        return null;
    }

    return {
        ...edge,
        id: String(edge.id ?? `${source}-${target}-${index}`),
        source: String(source),
        target: String(target),
        label: edge.label ?? '',
    };
};

const normalizeGraphData = (graphData) => {
    const graph = graphData?.graph ?? graphData?.data ?? graphData;
    const rawNodes = Array.isArray(graph?.nodes) ? graph.nodes : [];
    const rawEdges = Array.isArray(graph?.edges)
        ? graph.edges
        : Array.isArray(graph?.links)
            ? graph.links
            : [];

    return {
        nodes: rawNodes.map(normalizeNode),
        edges: rawEdges.map(normalizeEdge).filter(Boolean),
    };
};

const classifyNodes = (nodes, edges) => {
    const outgoingCounts = edges.reduce((counts, edge) => {
        counts.set(edge.source, (counts.get(edge.source) ?? 0) + 1);
        return counts;
    }, new Map());

    return nodes.map((node) => {
        const explicitType = node.type ?? node.data?.type;
        const isDecision = explicitType === 'decision' || (explicitType == null && (outgoingCounts.get(node.id) ?? 0) > 1);
        const dimensions = isDecision ? DECISION_NODE_SIZE : PROCESS_NODE_SIZE;

        return {
            ...node,
            type: isDecision ? 'decision' : 'process',
            sourcePosition: Position.Right,
            targetPosition: Position.Left,
            style: {
                width: dimensions.width,
                height: dimensions.height,
                background: 'transparent',
                border: 'none',
            },
        };
    });
};

const getLayoutedElements = (nodes, edges, direction = 'LR') =>{
    const dagreGraph = new dagre.graphlib.Graph();
    const typedNodes = classifyNodes(nodes, edges);

    dagreGraph.setDefaultEdgeLabel(() => ({}));
    dagreGraph.setGraph({rankdir: direction, ranksep: 120, nodesep: 80});

    for (const node of typedNodes) {
        const dimensions = node.type === 'decision' ? DECISION_NODE_SIZE : PROCESS_NODE_SIZE;
        dagreGraph.setNode(node.id, {width: dimensions.width, height: dimensions.height});
    }

    for (const edge of edges) {
        dagreGraph.setEdge(edge.source, edge.target);
    }

    dagre.layout(dagreGraph);

    const layoutedNodes = typedNodes.map((node, index) => {
        const dimensions = node.type === 'decision' ? DECISION_NODE_SIZE : PROCESS_NODE_SIZE;
        const dagreNode = dagreGraph.node(node.id);
        const x = dagreNode?.x;
        const y = dagreNode?.y;
        const hasValidPosition = typeof x === 'number' && isFinite(x) && typeof y === 'number' && isFinite(y);

        return {
            ...node,
            position: hasValidPosition
                ? {x: x - dimensions.width / 2, y: y - dimensions.height / 2}
                : {x: index * (PROCESS_NODE_SIZE.width + 60), y: 0},
        };
    });

    const layoutedEdges = edges.map((edge) => ({
        ...edge,
        type: 'waypoint',
        markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 22,
            height: 22,
            color: '#475569',
        },
        style: {
            stroke: '#475569',
            strokeWidth: 2,
        },
    }));
    
    return {nodes: layoutedNodes, edges: layoutedEdges};
};

function InteractiveFlow({initialNodes, initialEdges, onReady, registerInstance}) {
    const [nodes, , onNodesChange] = useNodesState(initialNodes);
    const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

    const onConnect = useCallback(
        (params) => setEdges((currentEdges) => addEdge({
            ...params,
            type: 'waypoint',
            markerEnd: {
                type: MarkerType.ArrowClosed,
                color: '#475569',
            },
            style: {
                stroke: '#475569',
                strokeWidth: 2,
            },
        }, currentEdges)),
        [setEdges]
    );

    useEffect(() => {
        if (nodes.length > 0) {
            onReady();
        }
    }, [nodes, onReady]);

    return (
        <ReactFlow
            nodes = {nodes}
            edges = {edges}
            nodeTypes = {nodeTypes}
            edgeTypes = {edgeTypes}
            onNodesChange = {onNodesChange}
            onEdgesChange = {onEdgesChange}
            onConnect = {onConnect}
            nodesDraggable
            elementsSelectable
            panOnDrag
            fitView
            defaultViewport = {{x: 0, y: 0, zoom: 1}}
            onInit = {registerInstance}
            >
                <Background color = '#cbd5e1' gap = {24} />
                <Controls />
        </ReactFlow>
    );
}

export default function NodeCanvas ({graphData}) {
    const reactFlowRef = useRef(null);

    const fitCanvas = useCallback(() => {
        const instance = reactFlowRef.current;

        if (!instance) {
            return;
        }

        requestAnimationFrame(() => {
            instance.fitView({padding: 0.18, duration: 300});
        });
    }, []);

    const {nodes, edges, canvasMessage, flowKey} = useMemo(() => {
        console.log("Raw AI data recieved by canvas:", graphData);

        if (!graphData) {
            return {
                nodes: [],
                edges: [],
                canvasMessage: 'Upload a story to generate a flowchart.',
                flowKey: 'empty',
            };
        }

        const normalizedGraph = normalizeGraphData(graphData);

        if (normalizedGraph.nodes.length === 0) {
            return {
                nodes: [],
                edges: [],
                canvasMessage: 'The backend returned no nodes to render.',
                flowKey: 'no-nodes',
            };
        }

        try {
            const {nodes: layoutedNodes, edges: layoutedEdges} = getLayoutedElements(
                normalizedGraph.nodes,
                normalizedGraph.edges
            );

            return {
                nodes: layoutedNodes,
                edges: layoutedEdges,
                canvasMessage: null,
                flowKey: JSON.stringify({
                    nodeIds: layoutedNodes.map((node) => node.id),
                    edgeIds: layoutedEdges.map((edge) => edge.id),
                }),
            };
        } catch (error) {
            console.error("the layout engine crashed ", error);

            return {
                nodes: [],
                edges: [],
                canvasMessage: 'The graph data could not be laid out for rendering.',
                flowKey: 'layout-error',
            };
        }
    }, [graphData]);

    return (
        <div style = {{width: '100%', height: '70vh', border: '1px solid #d1d5db', position: 'relative', backgroundColor: '#f8fafc'}}>
            {canvasMessage && (
                <div style = {{
                    position: 'absolute',
                    inset: 0,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    padding: '24px',
                    textAlign: 'center',
                    backgroundColor: '#fafafa',
                    color: '#555',
                    zIndex: 1,
                }}>
                    <strong>{canvasMessage}</strong>
                </div>
            )}
            {!canvasMessage && (
                <InteractiveFlow
                    key = {flowKey}
                    initialNodes = {nodes}
                    initialEdges = {edges}
                    onReady = {fitCanvas}
                    registerInstance = {(instance) => {
                        reactFlowRef.current = instance;
                    }}
                />
            )}
        </div>
    );
}
