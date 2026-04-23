# Narrative Node Mapper

> Turn interactive fiction and gamebook stories into visual flowcharts — powered by a local AI model.

> [!WARNING]
> **Work in Progress** — This project is under active development. Expect rough edges, breaking changes, missing features, and bugs.

[![Java](https://img.shields.io/badge/Java-17-orange?logo=java)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-19-61DAFB?logo=react)](https://react.dev/)
[![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite)](https://vitejs.dev/)
[![Ollama](https://img.shields.io/badge/Ollama-phi3-black?logo=ollama)](https://ollama.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Overview

Narrative Node Mapper parses branching stories — written in gamebook format or as freeform prose — and renders them as interactive, pannable/zoomable flowcharts. An on-device AI model (via [Ollama](https://ollama.com/)) handles the heavy lifting: it structures raw prose, extracts scenes and choices, and builds the graph entirely locally with no cloud API required.

### Key Features

- **Write or Upload** — type a story directly in the browser or upload a `.docx` / `.txt` file
- **AI-assisted editing** — ask the AI to rewrite your prose into proper gamebook format before converting
- **Local & private** — all AI inference runs on your own machine through Ollama; no data leaves your computer
- **Interactive flowchart** — pan, zoom, and inspect every scene node and choice edge
- **Raw data inspector** — expand the collapsible panel to see the full JSON graph the AI produced

---

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 19, Vite 8, [@xyflow/react](https://reactflow.dev/), [dagre](https://github.com/dagrejs/dagre) |
| Backend | Java 17, Spring Boot 3.4.5, Maven |
| AI / NLP | [LangChain4j](https://docs.langchain4j.dev/) + [Ollama](https://ollama.com/) (`phi3`) |
| Document parsing | Apache POI (`.docx`), plain text (`.txt`) |

---

## Prerequisites

| Tool | Minimum version | Notes |
|---|---|---|
| Java JDK | 17 | e.g. [Eclipse Temurin](https://adoptium.net/) |
| Maven | 3.9+ | or use the included `mvnw` wrapper |
| Node.js | 18+ | includes npm |
| Ollama | latest | [ollama.com](https://ollama.com/) |

---

## Getting Started

### 1 — Clone the repository

```bash
git clone https://github.com/Tobbe96/Narrative_To_Flowchart_Converter.git
cd Narrative_To_Flowchart_Converter
```

### 2 — Pull the AI model

```bash
ollama pull phi3
```

Ollama must be running (`ollama serve`) before you start the backend.

### 3 — Start the backend

```bash
# Using the Maven wrapper (no local Maven required)
./mvnw spring-boot:run        # macOS / Linux
mvnw.cmd spring-boot:run      # Windows
```

The API starts on **http://localhost:8080**.

### 4 — Install and start the frontend

```bash
npm run install:frontend   # installs frontend/node_modules
npm run dev                # starts Vite dev server
```

The app opens at **http://localhost:5173**.

---

## Usage

1. Open **http://localhost:5173** in your browser.
2. Choose a mode:
   - **✏️ Write Story** — paste or type your story, optionally click **✨ Get AI Help** to let the AI restructure it, then click **→ Convert to Flowchart**.
   - **📁 Upload File** — upload a `.docx` or `.txt` file; conversion starts automatically.
3. The flowchart renders below. Drag to pan, scroll to zoom.
4. Expand the **Raw AI Output Data** panel at the bottom to inspect the underlying JSON.

### Story Format

The converter understands two formats:

**Gamebook / Scene format** (fastest and most accurate):
```
Scene: The Crossroads
You stand at a dusty crossroads...
If you choose the northern road, go to The Dark Forest.
If you decide to head east, go to The Riverside Camp.

Scene: The Dark Forest
The trees close in around you...
```

**Freeform prose** — just write naturally; the AI will structure it into scenes and choices for you.

A complete example story is provided in [`test-stories/the-wanderers-choice.txt`](test-stories/the-wanderers-choice.txt).

---

## Project Structure

```
Narrative_To_Flowchart_Converter/
├── backend/
│   └── src/main/java/com/example/story_backend/
│       ├── controller/        # REST endpoints (/api/narrative/*)
│       ├── model/             # GraphResponse, SceneNode, SceneEdge
│       └── service/
│           ├── AiGateway.java           # Ollama integration & JSON repair
│           ├── DocumentReaderService.java  # .docx / .txt parsing
│           ├── GraphBuilderService.java    # Node/edge assembly
│           └── StoryProcessingService.java # Orchestration
├── frontend/
│   └── src/
│       ├── components/
│       │   ├── NodeCanvas.jsx     # @xyflow/react flowchart renderer
│       │   ├── StoryUploader.jsx  # Write/upload UI with AI-help flow
│       │   └── WaypointEdge.jsx   # Custom edge component
│       └── App.jsx
├── test-stories/                  # Sample gamebook files
├── pom.xml                        # Maven build (backend)
└── package.json                   # npm scripts (frontend shortcuts)
```

---

## API Reference

Base path: `POST /api/narrative`

| Endpoint | Body | Description |
|---|---|---|
| `/parse` | `multipart/form-data` — field `file` (`.docx` or `.txt`) | Convert an uploaded file to a graph |
| `/convert-text` | `{ "text": "..." }` | Convert typed/pasted text to a graph |
| `/rewrite` | `{ "text": "..." }` | Ask the AI to restructure prose into gamebook format |

All endpoints return a `GraphResponse` JSON object:
```json
{
  "graph": {
    "nodes": [{ "id": "1", "label": "The Crossroads", "description": "..." }],
    "edges": [{ "source": "1", "target": "2", "label": "Go north" }]
  }
}
```

---

## Contributing

Contributions are welcome! Please:

1. Fork the repository and create a feature branch (`git checkout -b feature/my-feature`)
2. Commit your changes with a clear message
3. Open a Pull Request describing what you changed and why

---

## License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

© 2024 Tobias Boström ([@Tobbe96](https://github.com/Tobbe96))