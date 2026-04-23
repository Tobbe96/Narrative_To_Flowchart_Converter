import { useState } from 'react';

const API_BASE = 'http://localhost:8080/api/narrative';
const CLIENT_TIMEOUT_MS = 11 * 60 * 1000;

// ─── Shared helpers ──────────────────────────────────────────────────────────

function getNodeCount(graphData) {
    const graph = graphData?.graph ?? graphData?.data ?? graphData;
    return Array.isArray(graph?.nodes) ? graph.nodes.length : 0;
}

async function readErrorMessage(response) {
    const ct = response.headers.get('content-type') ?? '';
    if (ct.includes('application/json')) {
        const payload = await response.json();
        return payload.message ?? payload.error ?? 'Server returned an error.';
    }
    return (await response.text()) || 'Server returned an error.';
}

// ─── Shared styles ───────────────────────────────────────────────────────────

const btn = (bg, fg = '#fff') => ({
    padding: '10px 22px',
    background: bg,
    color: fg,
    border: 'none',
    borderRadius: '8px',
    fontWeight: 700,
    fontSize: '0.9rem',
    cursor: 'pointer',
    transition: 'opacity 0.15s',
});

const cardStyle = {
    padding: '28px 32px',
    border: '1.5px solid #e2e8f0',
    borderRadius: '14px',
    background: '#fff',
    marginBottom: '20px',
    boxShadow: '0 2px 12px rgba(0,0,0,0.06)',
};

// ─── Write mode ──────────────────────────────────────────────────────────────

function WriteMode({ onGraphDataReceived }) {
    const [text, setText]               = useState('');
    const [aiDraft, setAiDraft]         = useState(null);   // rewritten text from AI
    const [loadingHelp, setLoadingHelp] = useState(false);
    const [loadingConvert, setLoadingConvert] = useState(false);
    const [error, setError]             = useState(null);

    const isProcessing = loadingHelp || loadingConvert;

    // Ask the AI to rewrite the user's story into gamebook format
    const handleAiHelp = async () => {
        if (!text.trim()) { setError('Write something first.'); return; }
        setError(null);
        setLoadingHelp(true);
        setAiDraft(null);

        const controller = new AbortController();
        const tid = setTimeout(() => controller.abort(), CLIENT_TIMEOUT_MS);
        try {
            const res = await fetch(`${API_BASE}/rewrite`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text }),
                signal: controller.signal,
            });
            if (!res.ok) throw new Error(await readErrorMessage(res));
            const { rewrittenText } = await res.json();
            setAiDraft(rewrittenText);
        } catch (err) {
            setError(err.name === 'AbortError'
                ? 'Request timed out. Please try again.'
                : err.message);
        } finally {
            clearTimeout(tid);
            setLoadingHelp(false);
        }
    };

    // Convert whatever is in the textarea to a flowchart
    const handleConvert = async () => {
        if (!text.trim()) { setError('Write something first.'); return; }
        setError(null);
        setLoadingConvert(true);

        const controller = new AbortController();
        const tid = setTimeout(() => controller.abort(), CLIENT_TIMEOUT_MS);
        try {
            const res = await fetch(`${API_BASE}/convert-text`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text }),
                signal: controller.signal,
            });
            if (!res.ok) throw new Error(await readErrorMessage(res));
            const graphData = await res.json();
            if (getNodeCount(graphData) === 0)
                throw new Error('The backend returned an empty graph.');
            onGraphDataReceived(graphData);
        } catch (err) {
            onGraphDataReceived(null);
            setError(err.name === 'AbortError'
                ? 'Request timed out. Please try again.'
                : err.message);
        } finally {
            clearTimeout(tid);
            setLoadingConvert(false);
        }
    };

    return (
        <div>
            {/* Story text input */}
            <textarea
                value={text}
                onChange={e => { setText(e.target.value); setAiDraft(null); setError(null); }}
                disabled={isProcessing}
                placeholder={
                    'Paste or write your story here.\n\n' +
                    'You can use gamebook format:\n' +
                    '  Scene: The Iron Gate\n' +
                    '  You stand before a massive iron gate...\n' +
                    '  If you choose to climb, go to The Sunken Path.\n\n' +
                    'Or just write your story as freeform prose — the AI will structure it for you.'
                }
                style={{
                    width: '100%',
                    minHeight: '220px',
                    padding: '14px 16px',
                    fontSize: '0.95rem',
                    lineHeight: 1.6,
                    fontFamily: 'inherit',
                    border: '1.5px solid #cbd5e1',
                    borderRadius: '10px',
                    resize: 'vertical',
                    boxSizing: 'border-box',
                    background: isProcessing ? '#f8fafc' : '#fff',
                    color: '#1e293b',
                }}
            />

            {/* Action buttons */}
            <div style={{ display: 'flex', gap: '10px', marginTop: '12px', flexWrap: 'wrap' }}>
                <button
                    onClick={handleAiHelp}
                    disabled={isProcessing}
                    style={{ ...btn('#7c3aed'), opacity: isProcessing ? 0.6 : 1 }}
                >
                    {loadingHelp ? '✨ AI is thinking…' : '✨ Get AI Help'}
                </button>
                <button
                    onClick={handleConvert}
                    disabled={isProcessing}
                    style={{ ...btn('#1d4ed8'), opacity: isProcessing ? 0.6 : 1 }}
                >
                    {loadingConvert ? '⏳ Converting…' : '→ Convert to Flowchart'}
                </button>
            </div>

            {/* AI draft preview */}
            {aiDraft && (
                <div style={{
                    marginTop: '20px',
                    border: '1.5px solid #7c3aed',
                    borderRadius: '12px',
                    overflow: 'hidden',
                }}>
                    <div style={{
                        background: '#f5f3ff',
                        padding: '10px 16px',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '10px',
                        borderBottom: '1px solid #ddd6fe',
                    }}>
                        <span style={{ fontWeight: 700, color: '#5b21b6', flexGrow: 1 }}>
                            ✨ AI suggested story
                        </span>
                        <button
                            onClick={() => { setText(aiDraft); setAiDraft(null); }}
                            style={btn('#7c3aed')}
                        >
                            ✅ Use this story
                        </button>
                        <button
                            onClick={() => setAiDraft(null)}
                            style={btn('#64748b')}
                        >
                            ✗ Discard
                        </button>
                    </div>
                    <pre style={{
                        margin: 0,
                        padding: '16px',
                        fontSize: '0.88rem',
                        lineHeight: 1.65,
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                        background: '#faf9ff',
                        color: '#1e293b',
                        maxHeight: '320px',
                        overflowY: 'auto',
                    }}>
                        {aiDraft}
                    </pre>
                </div>
            )}

            {/* Error */}
            {error && (
                <div style={{ marginTop: '12px', color: '#dc2626', fontWeight: 600 }}>
                    ⚠ {error}
                </div>
            )}

            {isProcessing && (
                <div style={{ marginTop: '12px', color: '#1d4ed8', fontWeight: 600 }}>
                    ⏳ AI is processing your story — this may take a few minutes…
                </div>
            )}
        </div>
    );
}

// ─── Upload mode ─────────────────────────────────────────────────────────────

function UploadMode({ onGraphDataReceived }) {
    const [isProcessing, setIsProcessing] = useState(false);
    const [error, setError]               = useState(null);

    const handleFileUpload = async (event) => {
        const file = event.target.files[0];
        if (!file) return;

        setIsProcessing(true);
        setError(null);

        const formData = new FormData();
        formData.append('file', file);

        const controller = new AbortController();
        const tid = setTimeout(() => controller.abort(), CLIENT_TIMEOUT_MS);
        try {
            const res = await fetch(`${API_BASE}/parse`, {
                method: 'POST',
                body: formData,
                signal: controller.signal,
            });
            if (!res.ok) throw new Error(await readErrorMessage(res));
            const graphData = await res.json();
            if (getNodeCount(graphData) === 0)
                throw new Error('The backend returned an empty graph.');
            onGraphDataReceived(graphData);
        } catch (err) {
            onGraphDataReceived(null);
            setError(err.name === 'AbortError'
                ? 'Request timed out. Please try again.'
                : err.message);
        } finally {
            clearTimeout(tid);
            setIsProcessing(false);
        }
    };

    return (
        <div style={{ textAlign: 'center' }}>
            <p style={{ color: '#475569', marginTop: 0 }}>
                Upload a <strong>.docx</strong> or <strong>.txt</strong> file — gamebook format or freeform prose, the AI handles both.
            </p>
            <label style={{
                display: 'inline-block',
                padding: '12px 28px',
                background: isProcessing ? '#94a3b8' : '#1d4ed8',
                color: '#fff',
                borderRadius: '10px',
                fontWeight: 700,
                cursor: isProcessing ? 'not-allowed' : 'pointer',
                fontSize: '0.95rem',
            }}>
                {isProcessing ? '⏳ Processing…' : '📁 Choose File'}
                <input
                    type="file"
                    accept=".docx,.txt"
                    onChange={handleFileUpload}
                    disabled={isProcessing}
                    style={{ display: 'none' }}
                />
            </label>

            {isProcessing && (
                <div style={{ marginTop: '16px', color: '#1d4ed8', fontWeight: 600 }}>
                    ⏳ AI is processing your story — this may take a few minutes…
                </div>
            )}
            {error && (
                <div style={{ marginTop: '16px', color: '#dc2626', fontWeight: 600 }}>
                    ⚠ {error}
                </div>
            )}
        </div>
    );
}

// ─── Main component ───────────────────────────────────────────────────────────

export default function StoryUploader({ onGraphDataReceived }) {
    const [mode, setMode] = useState('write'); // 'write' | 'upload'

    const tabBtn = (id, label) => ({
        padding: '9px 24px',
        fontWeight: 700,
        fontSize: '0.9rem',
        border: 'none',
        borderRadius: '8px',
        cursor: 'pointer',
        background: mode === id ? '#1d4ed8' : '#f1f5f9',
        color:      mode === id ? '#fff'    : '#475569',
        transition: 'all 0.15s',
    });

    return (
        <div style={cardStyle}>
            {/* Header */}
            <div style={{ marginBottom: '20px' }}>
                <h2 style={{ margin: '0 0 4px', color: '#0f172a', fontSize: '1.25rem' }}>
                    Narrative Node Mapper
                </h2>
                <p style={{ margin: '0 0 16px', color: '#64748b', fontSize: '0.9rem' }}>
                    Turn your game story into an interactive flowchart.
                </p>

                {/* Mode tabs */}
                <div style={{ display: 'flex', gap: '8px' }}>
                    <button style={tabBtn('write',  '✏️ Write Story')}  onClick={() => setMode('write')}>
                        ✏️ Write Story
                    </button>
                    <button style={tabBtn('upload', '📁 Upload File')} onClick={() => setMode('upload')}>
                        📁 Upload File
                    </button>
                </div>
            </div>

            {/* Mode content */}
            {mode === 'write'
                ? <WriteMode  onGraphDataReceived={onGraphDataReceived} />
                : <UploadMode onGraphDataReceived={onGraphDataReceived} />
            }
        </div>
    );
}

