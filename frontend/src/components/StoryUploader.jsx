import {useState} from 'react';

export default function StoryUploader ({onGraphDataReceived}) {
    const [isProcessing, setIsProcessing] = useState(false);

    const [error, setError] = useState(null);

    const getNodeCount = (graphData) => {
        const graph = graphData?.graph ?? graphData?.data ?? graphData;
        return Array.isArray(graph?.nodes) ? graph.nodes.length : 0;
    };

    const readErrorMessage = async (response) => {
        const contentType = response.headers.get('content-type') ?? '';

        if (contentType.includes('application/json')) {
            const payload = await response.json();
            return payload.message ?? payload.error ?? 'Java server returned an error.';
        }

        const text = await response.text();
        return text || 'Java server returned an error.';
    };

    const handleFileUpload = async (event) => {
        const file = event.target.files[0];
        if (!file) return;

        setIsProcessing(true);
        setError(null);

        const formData = new FormData();
        formData.append("file", file);

        try {
        console.log("Sending file to Java backend...");

        const response = await fetch ("http://localhost:8080/api/narrative/parse", {
            method: "POST",
            body: formData,
            });

            if (!response.ok) {
            throw new Error (await readErrorMessage(response));
            }

            const graphData = await response.json();
            const nodeCount = getNodeCount(graphData);

            if (nodeCount === 0) {
                throw new Error("The backend returned an empty graph, so there is nothing to render.");
            }

            console.log("Success! Recieved Graph Data: " , graphData);

            onGraphDataReceived(graphData);
        } catch (err) {
        console.error(err);
        onGraphDataReceived(null);
        setError(err instanceof Error ? err.message : "Failed to process story. Make sure the Java server is running.");
        } finally {
            setIsProcessing(false);
        }
        };

        return (
        <div style = {{padding: '20px', border: '2px dashed #ccc', borderRadius: '8px', textAlign: 'center', marginBottom: '20px' }}>
        <h2> Upload Narrative Document </h2>
        <p> Select a .docx file to generate your flowchart.</p>

        <input
            type = "file"
            accept = ".docx"
            onChange = {handleFileUpload}
            disabled = {isProcessing}
        />

        {isProcessing && (
        <div style = {{marginTop: '15px', color: '#0066cc'}}>
            <strong> AI is analysing your story... this might take up to 3 minutes. </strong>
        </div>
        )}

        {error && (
        <div style= {{marginTop: '15px', color: 'red'}}>
            <strong> {error} </strong>
            </div>
        )}
        </div>
        );
    }
