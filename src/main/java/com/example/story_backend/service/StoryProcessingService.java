package com.example.story_backend.service;

import com.example.story_backend.model.GraphResponse;
import com.example.story_backend.model.SceneEdge;
import com.example.story_backend.model.SceneNode;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StoryProcessingService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatLanguageModel buildModel() {
        return OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("phi3")
            .format("json")
            .timeout(Duration.ofMinutes(5))
            .build();
    }

    public GraphResponse processDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a non-empty .docx file.");
        }

        System.out.println("Step 1: Extracting text from document");
        String text = extractTextFromDocx(file);

        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The uploaded document did not contain readable text.");
        }

        System.out.println("Step 2: Pass 1 - Extracting scenes...");
        List<SceneNode> nodes = askAiForNodes(text);

        if (nodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI could not identify any scenes in the document.");
        }

        System.out.println("Step 3: Pass 2 - Extracting transitions for " + nodes.size() + " scenes...");
        List<SceneEdge> edges = new ArrayList<>(askAiForEdges(text, nodes));

        List<SceneNode> orphans = findOrphanedNodes(nodes, edges);
        if (!orphans.isEmpty()) {
            System.out.println("Step 4: Pass 3 - Recovering " + orphans.size() + " orphaned node(s) with no incoming edges...");
            List<SceneEdge> recoveredEdges = askAiForMissingEdges(text, nodes, edges, orphans);
            edges.addAll(recoveredEdges);
        }

        System.out.println("Step " + (orphans.isEmpty() ? "4" : "5") + ": Validating graph");
        GraphResponse validated = validateGraphResponse(new GraphResponse(nodes, edges));

        System.out.println("Success! Returning graph data to controller");
        return validated;
    }

    List<String> extractParagraphsFromDocx(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             XWPFDocument document = new XWPFDocument(inputStream)) {

            return document.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .map(String::trim)
                .filter(paragraph -> !paragraph.isBlank())
                .toList();
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Failed to read the Word document. Make sure the file is a valid .docx.",
                e
            );
        }
    }

    public String extractTextFromDocx(MultipartFile file) {
        return String.join("\n", extractParagraphsFromDocx(file));
    }

    private List<SceneNode> askAiForNodes(String text) {
        String prompt = """
                You are a story analyst. Read the story below and list its scenes as a JSON array of nodes.

                Reply with ONLY this JSON — no markdown, no explanation:
                {"nodes": [{"id": "scene_1", "label": "Short scene title", "summary": "One sentence describing what happens in this scene"}]}

                Rules:
                - Create exactly one node per named scene or location in the story. Do NOT split a scene into multiple nodes.
                - If a scene contains a decision or choice, that scene IS the node — do not create a separate decision node for it.
                - Only include scenes that are explicitly described in the text. Do not invent scenes.
                - Use simple sequential ids: scene_1, scene_2, scene_3, etc.
                - Keep labels short (2–4 words) and summaries to one sentence.

                Story:
                %s
                """.formatted(text);

        System.out.println("Pass 1: Sending scene extraction request to AI...");
        try {
            String response = buildModel().generate(prompt);
            System.out.println("Pass 1 complete.");
            GraphResponse parsed = parseAiGraphResponse(response);
            return parsed.nodes() == null ? List.of() : parsed.nodes();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to extract scenes from the AI.", e);
        }
    }

    private List<SceneEdge> askAiForEdges(String text, List<SceneNode> nodes) {
        // Build a pre-filled adjacency template so phi3 only has to fill in "transitions" —
        // not invent IDs. Each "from" id is already correct; the model just adds "to" + "label".
        StringBuilder templateBuilder = new StringBuilder("{\"adjacency\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            SceneNode node = nodes.get(i);
            templateBuilder.append("  {\"from\": \"").append(node.id())
                .append("\", \"label\": \"").append(node.label().replace("\"", "'"))
                .append("\", \"transitions\": []}");
            if (i < nodes.size() - 1) templateBuilder.append(",");
            templateBuilder.append("\n");
        }
        templateBuilder.append("]}");

        String validIds = nodes.stream().map(SceneNode::id).collect(Collectors.joining(", "));

        String prompt = """
                You are a story analyst. Fill in the "transitions" arrays in the JSON template below.

                For each scene, add entries to its "transitions" array showing which scenes follow it directly in the story.
                Each transition entry must be: {"to": "<scene_id>", "label": "<short action phrase>"}

                Rules:
                - "to" must be exactly one of these ids: %s
                - If a scene is a decision point with multiple outcomes, add one transition per outcome.
                - If a scene is a story ending, leave its transitions as [].
                - Do not add a scene as its own transition target.
                - Only use transitions that are supported by the story.

                Template (fill in the transitions arrays):
                %s

                Story:
                %s
                """.formatted(validIds, templateBuilder, text);

        System.out.println("Pass 2: Sending adjacency template to AI...");
        try {
            String response = buildModel().generate(prompt);
            System.out.println("Pass 2 complete.");
            return parseAdjacencyToEdges(response, nodes);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to extract transitions from the AI.", e);
        }
    }

    private List<SceneEdge> parseAdjacencyToEdges(String json, List<SceneNode> nodes) {
        Set<String> validIds = nodes.stream().map(SceneNode::id).collect(Collectors.toSet());
        List<SceneEdge> edges = new ArrayList<>();
        int edgeCounter = 1;

        try {
            String cleaned = extractJsonObject(json);
            tools.jackson.databind.JsonNode root = objectMapper.readTree(cleaned);
            tools.jackson.databind.JsonNode adjacency = root.get("adjacency");

            if (adjacency == null || !adjacency.isArray()) {
                System.err.println("Pass 2: adjacency field missing or not an array, raw: " + cleaned);
                return edges;
            }

            for (tools.jackson.databind.JsonNode entry : adjacency) {
                String from = entry.has("from") ? entry.get("from").asText() : null;
                if (from == null || !validIds.contains(from)) continue;

                tools.jackson.databind.JsonNode transitions = entry.get("transitions");
                if (transitions == null || !transitions.isArray()) continue;

                for (tools.jackson.databind.JsonNode t : transitions) {
                    String to = t.has("to") ? t.get("to").asText() : null;
                    String label = t.has("label") ? t.get("label").asText("").trim() : "";

                    if (to == null || !validIds.contains(to) || to.equals(from) || label.isBlank()) continue;

                    edges.add(new SceneEdge("edge_" + edgeCounter++, from, to, label));
                }
            }
        } catch (Exception e) {
            System.err.println("Pass 2: failed to parse adjacency response: " + e.getMessage());
        }

        System.out.println("Pass 2: extracted " + edges.size() + " edge(s) from adjacency response.");
        return edges;
    }

    private List<SceneNode> findOrphanedNodes(List<SceneNode> nodes, List<SceneEdge> edges) {
        if (nodes.size() <= 1) return List.of();

        Set<String> nodesWithIncoming = edges.stream()
            .map(SceneEdge::target)
            .collect(Collectors.toSet());

        // Every node except the root (first node) should have at least one incoming edge
        String rootId = nodes.get(0).id();
        return nodes.stream()
            .filter(n -> !n.id().equals(rootId))
            .filter(n -> !nodesWithIncoming.contains(n.id()))
            .collect(Collectors.toList());
    }

    private List<SceneEdge> askAiForMissingEdges(String text, List<SceneNode> allNodes,
                                                  List<SceneEdge> existingEdges, List<SceneNode> orphans) {
        String allSceneList = allNodes.stream()
            .map(n -> "  - " + n.id() + ": " + n.label() + " — " + n.summary())
            .collect(Collectors.joining("\n"));

        String orphanList = orphans.stream()
            .map(n -> "  - " + n.id() + ": " + n.label() + " — " + n.summary())
            .collect(Collectors.joining("\n"));

        String existingEdgeList = existingEdges.stream()
            .map(e -> "  - " + e.source() + " → " + e.target() + " (\"" + e.label() + "\")")
            .collect(Collectors.joining("\n"));

        String validIds = allNodes.stream()
            .map(SceneNode::id)
            .collect(Collectors.joining(", "));

        // Build template with one entry per orphan, pre-filled with the orphan as "target"
        StringBuilder templateBuilder = new StringBuilder("{\"missing_edges\": [\n");
        for (int i = 0; i < orphans.size(); i++) {
            SceneNode orphan = orphans.get(i);
            templateBuilder.append("  {\"target\": \"").append(orphan.id())
                .append("\", \"target_label\": \"").append(orphan.label().replace("\"", "'"))
                .append("\", \"source\": \"\", \"label\": \"\"}");
            if (i < orphans.size() - 1) templateBuilder.append(",");
            templateBuilder.append("\n");
        }
        templateBuilder.append("]}");

        int nextEdgeIndex = existingEdges.size() + 1;

        String prompt = """
                You are a story analyst. Some scenes have no incoming connections and need to be linked.

                All scenes:
                %s

                Already existing connections (do NOT duplicate):
                %s

                For each entry below, fill in "source" with the scene id that directly leads to the target scene, and "label" with a short transition phrase.

                Template to fill in:
                %s

                Rules:
                - "source" must be exactly one of: %s
                - Only use a source that logically leads to the target based on the story.
                - Do not duplicate existing connections.

                Story:
                %s
                """.formatted(allSceneList, existingEdgeList, templateBuilder, validIds, text);

        System.out.println("Pass 3: Sending recovery request to AI...");
        try {
            String response = buildModel().generate(prompt);
            System.out.println("Pass 3 complete.");
            return parseMissingEdgesToEdges(response, allNodes, nextEdgeIndex);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            System.err.println("Pass 3 failed: " + e.getMessage());
            return List.of();
        }
    }

    private List<SceneEdge> parseMissingEdgesToEdges(String json, List<SceneNode> allNodes, int startIndex) {
        Set<String> validIds = allNodes.stream().map(SceneNode::id).collect(Collectors.toSet());
        List<SceneEdge> edges = new ArrayList<>();
        int edgeCounter = startIndex;

        try {
            String cleaned = extractJsonObject(json);
            tools.jackson.databind.JsonNode root = objectMapper.readTree(cleaned);
            tools.jackson.databind.JsonNode missing = root.get("missing_edges");

            if (missing == null || !missing.isArray()) return edges;

            for (tools.jackson.databind.JsonNode entry : missing) {
                String source = entry.has("source") ? entry.get("source").asText("").trim() : "";
                String target = entry.has("target") ? entry.get("target").asText("").trim() : "";
                String label  = entry.has("label")  ? entry.get("label").asText("").trim()  : "";

                if (source.isBlank() || target.isBlank() || label.isBlank()) continue;
                if (!validIds.contains(source) || !validIds.contains(target)) continue;
                if (source.equals(target)) continue;

                edges.add(new SceneEdge("edge_" + edgeCounter++, source, target, label));
            }
        } catch (Exception e) {
            System.err.println("Pass 3: failed to parse recovery response: " + e.getMessage());
        }

        System.out.println("Pass 3: recovered " + edges.size() + " edge(s).");
        return edges;
    }

    GraphResponse parseAiGraphResponse(String json) {
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The local AI returned an empty response.");
        }

        String cleaned = extractJsonObject(json);

        try {
            return objectMapper.readValue(cleaned, GraphResponse.class);
        } catch (Exception e) {
            System.err.println("AI returned invalid JSON: " + cleaned);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to parse the AI graph response.", e);
        }
    }

    private String extractJsonObject(String raw) {
        String stripped = raw.replaceAll("(?s)^\\s*```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();

        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return stripped.substring(start, end + 1);
        }

        return stripped;
    }

    GraphResponse validateGraphResponse(GraphResponse response) {
        List<SceneNode> rawNodes = response == null || response.nodes() == null ? List.of() : response.nodes();
        List<SceneEdge> rawEdges = response == null || response.edges() == null ? List.of() : response.edges();

        if (rawNodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI response did not contain any scenes to render.");
        }

        Set<String> nodeIds = new HashSet<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> edgePairs = new HashSet<>(); // deduplicates by source→target regardless of id

        List<SceneNode> validatedNodes = rawNodes.stream()
            .filter(node -> node != null
                && node.id() != null && !node.id().isBlank()
                && node.label() != null && !node.label().isBlank())
            .filter(node -> nodeIds.add(node.id().trim()))
            .map(node -> new SceneNode(
                node.id().trim(),
                node.label().trim(),
                node.summary() == null ? "" : node.summary().trim()
            ))
            .collect(Collectors.toList());

        if (validatedNodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI response did not contain any valid scenes to render.");
        }

        List<SceneEdge> validatedEdges = rawEdges.stream()
            .filter(edge -> edge != null
                && edge.id() != null && !edge.id().isBlank()
                && edge.source() != null && !edge.source().isBlank()
                && edge.target() != null && !edge.target().isBlank()
                && edge.label() != null && !edge.label().isBlank())
            .filter(edge -> nodeIds.contains(edge.source().trim()) && nodeIds.contains(edge.target().trim()))
            .filter(edge -> !edge.source().trim().equals(edge.target().trim()))
            .filter(edge -> edgeIds.add(edge.id().trim()))
            .filter(edge -> edgePairs.add(edge.source().trim() + "→" + edge.target().trim()))
            .map(edge -> new SceneEdge(
                edge.id().trim(),
                edge.source().trim(),
                edge.target().trim(),
                edge.label().trim()
            ))
            .collect(Collectors.toList());

        int skippedEdges = rawEdges.size() - validatedEdges.size();
        if (skippedEdges > 0) {
            System.out.println("Warning: Dropped " + skippedEdges + " invalid edge(s) from AI response.");
        }

        return new GraphResponse(validatedNodes, validatedEdges);
    }
}
