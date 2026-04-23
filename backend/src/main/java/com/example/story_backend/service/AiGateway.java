package com.example.story_backend.service;

import com.example.story_backend.model.GraphResponse;
import com.example.story_backend.model.SceneEdge;
import com.example.story_backend.model.SceneNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles all communication with the local Ollama AI model
 * and all JSON parsing / repair utilities.
 */
@Service
public class AiGateway {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // JSON-constrained model used for structured outputs (nodes, edges)
    private ChatLanguageModel buildJsonModel() {
        return OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("phi3")
            .format("json")
            .timeout(Duration.ofMinutes(10))
            .numPredict(4096)
            .numCtx(8192)
            .build();
    }

    // Plain-text model used when we want free-form prose output (novel rewrite)
    private ChatLanguageModel buildTextModel() {
        return OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .modelName("phi3")
            .timeout(Duration.ofMinutes(10))
            .numPredict(4096)
            .numCtx(8192)
            .build();
    }

    // -------------------------------------------------------------------------
    // Novel rewrite (pipeline — expands freeform prose into gamebook format)
    // -------------------------------------------------------------------------

    /**
     * Rewrites a freeform novel or prose text into the structured
     * "Scene: Title / If you choose X, go to Y." format that the
     * programmatic pipeline can process.
     *
     * Returns the original text unchanged if the rewrite fails.
     */
    public String rewriteNovel(String text) {
        String prompt = """
                You are a story analyst. Read the story below and rewrite it as a structured branching narrative.

                Format EVERY scene exactly like this:

                Scene: [Scene Title]
                [2-3 sentence description of what happens in this scene.]
                If you choose to [action], go to [Exact Scene Title]. If you decide to [other action], go to [Other Exact Scene Title].

                Rules:
                - Create EXACTLY as many scenes as there are distinct locations or events in the text. Do NOT invent new scenes.
                - Do NOT create more than 12 scenes total.
                - Every "go to" target MUST exactly match the title of a scene you defined.
                - Only create decision branches that are EXPLICITLY described in the original text. Do NOT invent choices.
                - If the story is purely linear (no choices), just list scenes in order with no "go to" lines.
                - Do NOT output JSON. Output plain text only.

                Story:
                %s
                """.formatted(text);

        System.out.println("Novel rewrite: asking AI to restructure the text...");
        try {
            String result = buildTextModel().generate(prompt);
            System.out.println("Novel rewrite complete.");
            return result;
        } catch (Exception e) {
            System.err.println("Novel rewrite failed, using original text: " + e.getMessage());
            return text;
        }
    }

    // -------------------------------------------------------------------------
    // AI Help rewrite (user-facing — strict, no invention)
    // -------------------------------------------------------------------------

    /**
     * Formats the user's rough story notes into clean gamebook format WITHOUT
     * adding any new content. Only the scenes, characters, and choices explicitly
     * present in the input are included.
     *
     * Returns the original text unchanged if the rewrite fails.
     */
    public String rewriteForHelp(String text) {
        String prompt = """
                You are a story formatter. Your only job is to take the rough story notes below and write them out in clean gamebook format.

                Format EVERY scene exactly like this:

                Scene: [Scene Title]
                [1-2 sentence description using only details from the notes.]
                If you choose to [action from the notes], go to [Exact Scene Title].
                If you decide to [other action from the notes], go to [Other Exact Scene Title].

                STRICT RULES — breaking any of these is wrong:
                - Include ONLY the scenes explicitly described in the notes. Do NOT invent new scenes.
                - Include ONLY the choices explicitly described in the notes. Do NOT invent new choices.
                - Do NOT add characters, locations, items, or events not mentioned in the notes.
                - Do NOT expand the story. Format it faithfully, keep it concise.
                - Every "go to" target MUST exactly match the title of a scene in your output.
                - If there are no choices, just list the scenes in order with no "go to" lines.
                - Do NOT output JSON. Output plain text only.

                Story notes:
                %s
                """.formatted(text);

        System.out.println("AI Help rewrite: formatting user notes...");
        try {
            String result = buildTextModel().generate(prompt);
            System.out.println("AI Help rewrite complete.");
            return result;
        } catch (Exception e) {
            System.err.println("AI Help rewrite failed, using original text: " + e.getMessage());
            return text;
        }
    }

    // -------------------------------------------------------------------------
    // Node summary fill
    // -------------------------------------------------------------------------

    /**
     * Asks the AI to fill in one-sentence summaries for a pre-built node list.
     * The AI is NOT allowed to add, remove, or rename nodes — structure is fixed.
     * Returns the original nodes unchanged if the AI call fails.
     */
    public List<SceneNode> fillSummaries(List<SceneNode> nodes, String text) {
        List<SceneNode> filled = attemptSummaryFill(nodes, text);

        // Retry for any nodes that came back with a blank summary
        List<SceneNode> missing = filled.stream()
            .filter(n -> n.summary() == null || n.summary().isBlank())
            .collect(Collectors.toList());

        if (!missing.isEmpty()) {
            System.out.println("Pass 1 retry: " + missing.size() + " node(s) had empty summaries — retrying...");
            List<SceneNode> retried = attemptSummaryFill(missing, text);
            Map<String, String> retriedById = retried.stream()
                .filter(n -> n.summary() != null && !n.summary().isBlank())
                .collect(Collectors.toMap(SceneNode::id, SceneNode::summary, (a, b) -> a));

            filled = filled.stream()
                .map(n -> retriedById.containsKey(n.id())
                    ? new SceneNode(n.id(), n.type(), n.label(), retriedById.get(n.id()))
                    : n)
                .collect(Collectors.toList());
        }

        return filled;
    }

    private List<SceneNode> attemptSummaryFill(List<SceneNode> nodes, String text) {
        String nodeList = nodes.stream()
            .map(n -> "  {\"id\": \"" + n.id() + "\", \"type\": \"" + n.type()
                + "\", \"label\": \"" + n.label() + "\", \"summary\": \"\"}")
            .collect(Collectors.joining(",\n"));

        String prompt = """
                You are a story analyst. Fill in the "summary" field for each node below with one sentence. Do not add, remove, or rename any nodes.

                Reply with ONLY this JSON — no markdown, no explanation:
                {"nodes": [
                %s
                ]}

                Story:
                %s
                """.formatted(nodeList, text);

        System.out.println("Pass 1: Sending summary-fill request to AI (" + nodes.size() + " nodes)...");
        try {
            String response = buildJsonModel().generate(prompt);
            System.out.println("Pass 1 complete.");
            GraphResponse parsed = parseGraphResponse(response);

            if (parsed.nodes() == null || parsed.nodes().isEmpty()) {
                return nodes;
            }

            Map<String, String> summaryById = parsed.nodes().stream()
                .filter(n -> n.id() != null && n.summary() != null)
                .collect(Collectors.toMap(SceneNode::id, SceneNode::summary, (a, b) -> a));

            return nodes.stream()
                .map(n -> new SceneNode(n.id(), n.type(), n.label(),
                    summaryById.getOrDefault(n.id(), "")))
                .collect(Collectors.toList());

        } catch (Exception e) {
            System.err.println("Pass 1: summary fill failed, returning structural nodes: " + e.getMessage());
            return nodes;
        }
    }

    // -------------------------------------------------------------------------
    // Edge AI fallbacks
    // -------------------------------------------------------------------------

    /**
     * Asks the AI to generate edges when programmatic "go to" parsing left some
     * nodes uncovered. Only fires as a supplement, not as the primary strategy.
     */
    public List<SceneEdge> askForEdgesFallback(String text, List<SceneNode> nodes, int startCounter) {
        String validIds = nodes.stream()
            .map(n -> "\"" + n.id() + "\"")
            .collect(Collectors.joining(", "));

        String nodeList = nodes.stream()
            .map(n -> "  - " + n.id() + " (" + n.type() + "): " + n.label())
            .collect(Collectors.joining("\n"));

        String prompt = """
                You are a story analyst. Read the story, then list every connection between the nodes.

                Story:
                %s

                Nodes (id → label):
                %s

                Reply with ONLY this JSON — no markdown, no explanation:
                {"edges": [{"from": "scene_1", "to": "scene_2", "label": "short action phrase"}]}

                Rules:
                - "from" and "to" MUST be one of these exact IDs: %s
                - Each "label" must be a short phrase (2-5 words) describing the action or choice taken.
                - Decision nodes must have one outgoing edge per possible outcome.
                - Do not create self-loops or duplicate connections.
                - Only include connections that are supported by the story text.
                """.formatted(text, nodeList, validIds);

        System.out.println("Pass 2 AI fallback: supplementing with AI edges...");
        try {
            String response = buildJsonModel().generate(prompt);
            return parseEdgeList(response, nodes, startCounter);
        } catch (Exception e) {
            System.err.println("Pass 2 AI fallback failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Focused prompt used when the story has no "go to" phrases.
     * Shows each decision node alongside its parent scene's body text so the AI
     * can identify which scenes are the 2 possible outcomes for each decision.
     *
     * @param decisionContexts map of decisionNodeId → parent scene body text
     */
    public List<SceneEdge> askForDecisionOutcomes(String text,
                                                   List<SceneNode> nodes,
                                                   Map<String, String> decisionContexts,
                                                   int startCounter) {
        List<SceneNode> decisionNodes = nodes.stream()
            .filter(n -> "decision".equals(n.type()))
            .collect(Collectors.toList());

        List<SceneNode> sceneNodes = nodes.stream()
            .filter(n -> "scene".equals(n.type()))
            .collect(Collectors.toList());

        if (decisionNodes.isEmpty() || sceneNodes.isEmpty()) return List.of();

        // Build a context block for each decision: label + parent scene body
        String decisionBlock = decisionNodes.stream()
            .map(d -> "  Decision " + d.id() + " — \"" + d.label() + "\"\n"
                + "  Context: " + decisionContexts.getOrDefault(d.id(), d.summary()))
            .collect(Collectors.joining("\n\n"));

        String sceneList = sceneNodes.stream()
            .map(n -> "  " + n.id() + ": " + n.label())
            .collect(Collectors.joining("\n"));

        String validDecisionIds = decisionNodes.stream()
            .map(n -> "\"" + n.id() + "\"")
            .collect(Collectors.joining(", "));

        String validSceneIds = sceneNodes.stream()
            .map(n -> "\"" + n.id() + "\"")
            .collect(Collectors.joining(", "));

        String prompt = """
                You are a story analyst. Read the story, then connect each decision point to its two outcome scenes.

                Story:
                %s

                Decision points and their surrounding story context:
                %s

                Outcome scenes (id: label):
                %s

                Reply with ONLY this JSON — no markdown, no explanation:
                {"edges": [{"from": "decision_1", "to": "scene_2", "label": "climb the gate"}, {"from": "decision_1", "to": "scene_3", "label": "follow the stream"}]}

                Rules:
                - "from" MUST be one of these exact IDs: %s
                - "to" MUST be one of these exact IDs: %s
                - Each decision must have exactly 2 outgoing edges — one per outcome described in the context.
                - "label" must be a short verb phrase (2-5 words) naming the choice taken.
                - Do not create self-loops or duplicate connections.
                """.formatted(text, decisionBlock, sceneList, validDecisionIds, validSceneIds);

        System.out.println("Pass 2 (decision outcomes): linking " + decisionNodes.size()
            + " decision(s) to their outcome scenes...");
        try {
            String response = buildJsonModel().generate(prompt);
            return parseEdgeList(response, nodes, startCounter);
        } catch (Exception e) {
            System.err.println("Pass 2 decision outcomes failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Asks the AI specifically to link orphaned nodes that have no incoming edge.
     */
    public List<SceneEdge> askForMissingEdges(String text, List<SceneNode> allNodes,
                                               List<SceneEdge> existingEdges,
                                               List<SceneNode> orphans,
                                               int nextIndex) {
        String allNodeList = allNodes.stream()
            .map(n -> "  - " + n.id() + " (" + n.type() + "): " + n.label())
            .collect(Collectors.joining("\n"));

        String orphanIds = orphans.stream()
            .map(n -> "\"" + n.id() + "\"")
            .collect(Collectors.joining(", "));

        String existingEdgeList = existingEdges.stream()
            .map(e -> "  - " + e.source() + " → " + e.target() + " (\"" + e.label() + "\")")
            .collect(Collectors.joining("\n"));

        String validIds = allNodes.stream()
            .map(n -> "\"" + n.id() + "\"")
            .collect(Collectors.joining(", "));

        String prompt = """
                You are a story analyst. These nodes have no incoming connection and need to be linked: %s

                All nodes:
                %s

                Already existing connections (do NOT duplicate):
                %s

                Reply with ONLY this JSON — no markdown, no explanation:
                {"edges": [{"from": "scene_1", "to": "scene_2", "label": "short action phrase"}]}

                Rules:
                - "from" and "to" must be exactly one of: %s
                - Only add connections supported by the story.
                - Do not duplicate existing connections.

                Story:
                %s
                """.formatted(orphanIds, allNodeList, existingEdgeList, validIds, text);

        System.out.println("Pass 3: Asking AI to recover " + orphans.size() + " orphaned node(s)...");
        try {
            String response = buildJsonModel().generate(prompt);
            System.out.println("Pass 3 complete.");
            return parseEdgeList(response, allNodes, nextIndex);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            System.err.println("Pass 3 failed: " + e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing utilities
    // -------------------------------------------------------------------------

    public GraphResponse parseGraphResponse(String json) {
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "The local AI returned an empty response.");
        }
        try {
            return objectMapper.readValue(extractJsonObject(json), GraphResponse.class);
        } catch (Exception e) {
            System.err.println("AI returned invalid JSON: " + json);
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Failed to parse the AI graph response.", e);
        }
    }

    public List<SceneEdge> parseEdgeList(String json, List<SceneNode> nodes, int startCounter) {
        Set<String> validIds = nodes.stream().map(SceneNode::id).collect(Collectors.toSet());
        List<SceneEdge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int counter = startCounter;

        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(json));
            JsonNode edgesNode = root.get("edges");

            if (edgesNode == null || !edgesNode.isArray()) {
                System.err.println("Edge parse: 'edges' field missing or not an array.");
                return edges;
            }

            for (JsonNode entry : edgesNode) {
                String from  = entry.has("from")  ? entry.get("from").asText("").trim()  : "";
                String to    = entry.has("to")    ? entry.get("to").asText("").trim()    : "";
                String label = entry.has("label") ? entry.get("label").asText("").trim() : "";

                if (from.isBlank() || to.isBlank() || label.isBlank()) continue;
                if (!validIds.contains(from) || !validIds.contains(to))  continue;
                if (from.equals(to)) continue;
                if (!seen.add(from + "→" + to)) continue;

                edges.add(new SceneEdge("edge_" + counter++, from, to, label));
            }
        } catch (Exception e) {
            System.err.println("Failed to parse edge list: " + e.getMessage());
        }

        return edges;
    }

    String extractJsonObject(String raw) {
        String stripped = raw
            .replaceAll("(?s)^\\s*```[a-zA-Z]*\\s*", "")
            .replaceAll("(?s)```\\s*$", "")
            .trim();

        int start = stripped.indexOf('{');
        if (start == -1) return stripped;

        return repairTruncatedJson(stripped.substring(start));
    }

    /**
     * Closes any unclosed strings, arrays, and objects in JSON that was
     * cut off mid-stream by the AI's token limit.
     */
    String repairTruncatedJson(String json) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escape   = false;

        for (char c : json.toCharArray()) {
            if (escape)                     { escape = false; continue; }
            if (c == '\\' && inString)      { escape = true;  continue; }
            if (c == '"')                   { inString = !inString; continue; }
            if (!inString) {
                if      (c == '{' || c == '[')                    stack.push(c);
                else if ((c == '}' || c == ']') && !stack.isEmpty()) stack.pop();
            }
        }

        if (!inString && stack.isEmpty()) return json;

        StringBuilder sb = new StringBuilder(json.stripTrailing());
        if (inString) sb.append('"');

        // Strip trailing comma or colon before closing brackets
        String trimmed = sb.toString().stripTrailing();
        while (trimmed.endsWith(",") || trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        sb = new StringBuilder(trimmed);

        while (!stack.isEmpty()) {
            sb.append(stack.pop() == '{' ? '}' : ']');
        }

        return sb.toString();
    }
}
