import {useState} from 'react';
import StoryUploader from './components/StoryUploader'
import NodeCanvas from './components/NodeCanvas';
function App() {
    const [storyGraph, setStoryGraph] = useState(null);

    return (
    <div style = {{maxWidth: '1000px', margin: '0 auto', padding: '20px', fontFamily: 'sans-serif'}}>
    <h1> Narrative Node Mapper </h1>

    <StoryUploader onGraphDataReceived = {setStoryGraph} />
    <div style = {{ border: '2px solid #444', borderRadius: '8px', overflow: 'hidden'}}>
        <NodeCanvas graphData={storyGraph} />
    </div>
    {storyGraph && (
    <details style = {{ backgroundColor: '#1e1e1e', color: '#00ff00', padding: '20px', borderRadius: '8px', overflowX: 'auto'}}>
    <h3> Raw AI Output Data: </h3>
    <pre> {JSON.stringify(storyGraph, null, 2)} </pre>
    </details>
    )}
    </div>
    );
}

export default App;