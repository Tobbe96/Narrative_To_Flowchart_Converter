package com.example.story_backend.service;

import com.example.story_backend.model.GraphResponse;
import com.example.story_backend.model.SceneEdge;
import com.example.story_backend.model.SceneNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds nodes and edges from structured story text (Scene:/go to format)
 * using regex-based programmatic detection. Delegates to AiGateway only
 * for content fills (summaries) and edge fallbacks.
 */
@Service
public class GraphBuilderService {

    private final AiGateway ai;

    public GraphBuilderService(AiGateway ai) {
        this.ai = ai;
    }

    // -------------------------------------------------------------------------
    // Structure detection
    // -------------------------------------------------------------------------

    /** Returns true if the text has at least 2 "Scene: Title" headings. */
    public boolean isStructured(String text) {
        return detectSceneHeadings(text).size() >= 2;
    }

    /**
     * Extracts scene titles from headings in order.
     * Matches all of:  "Scene: Title"  "Scene 1: Title"  "Scene 4-A: Title"
     */
    Map<String, String> detectSceneHeadings(String text) {
        Map<String, String> scenes = new LinkedHashMap<>();
        // \s*[\d\w-]* allows an optional number/letter suffix between "scene" and ":"
        Pattern pattern = Pattern.compile("(?im)^\\s*scene\\s*[\\d\\w-]*\\s*:\\s*(.+)$");
        Matcher matcher = pattern.matcher(text);
        int index = 1;
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            if (!title.isBlank()) {
                scenes.put("scene_" + index++, title);
            }
        }
        return scenes;
    }

    /**
     * Returns true if the scene body describes a player/character choice.
     *
     * Gamebook-style phrases ("if you choose", "go to") are matched directly.
     * Prose-style detection requires a clear binary structure, e.g.
     * "He could stay... or he could retreat." — matching just "he could" alone
     * would fire on descriptive narration like "A choice sat heavy in his mind."
     */
    boolean sceneContainsDecision(String sceneText) {
        String lower = sceneText.toLowerCase();

        // Explicit gamebook-style choice language
        if (lower.contains("if you choose") || lower.contains("if you decide")
                || lower.contains("you can choose") || lower.contains("you may choose")
                || lower.contains("go to") || lower.contains("goes to")
                || lower.contains("do you")
                || lower.contains("if he choose") || lower.contains("if he decide")
                || lower.contains("if she choose") || lower.contains("if she decide")
                || lower.contains("if they choose") || lower.contains("if they decide")) {
            return true;
        }

        // Prose-style: only matches when a clear binary "X or Y" structure is present
        boolean hasCouldSubject = lower.contains("he could") || lower.contains("she could")
                || lower.contains("they could");
        boolean hasOrBranch = lower.contains("or he could") || lower.contains("or she could")
                || lower.contains("or they could") || lower.contains("or retreat")
                || lower.contains("or turn back");
        return hasCouldSubject && hasOrBranch;
    }

    /** Extracts the paragraph body of a named scene from the full story text. */
    String extractSceneBody(String text, String sceneTitle) {
        // The heading may have a number prefix (e.g. "Scene 1: Title"), so match
        // any optional suffix between "scene" and ":" before the title.
        Pattern p = Pattern.compile(
            "(?is)scene\\s*[\\d\\w-]*\\s*:\\s*" + Pattern.quote(sceneTitle)
            + "\\s*\\n(.*?)(?=\\nscene\\s*[\\d\\w-]*\\s*:|$)"
        );
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    // -------------------------------------------------------------------------
    // Node building
    // -------------------------------------------------------------------------

    /**
     * Extracts the two option phrases from gamebook choice text to produce
     * a human-readable decision label like "Climb the gate or follow the stream?"
     *
     * Falls back to "Make a choice" when the pattern does not match.
     */
    String buildDecisionLabel(String sceneBody) {
        Pattern p = Pattern.compile(
            "(?i)if (?:you|he|she|they) (?:choose(?:s)?|decide(?:s)?)(?:\\s+instead)?(?:\\s+to)?\\s+([^,\\.!?\\n]{3,70})"
        );
        Matcher m = p.matcher(sceneBody);
        List<String> choices = new ArrayList<>();
        while (m.find() && choices.size() < 2) {
            // Strip any trailing "go to …" / "goes to …" tail
            String choice = m.group(1).trim()
                .replaceAll("(?i),?\\s*(?:go(?:es)? to|proceed(?:s)? to|travel(?:s)? to|head(?:s)? to|lead(?:s)? to).*$", "")
                .trim();
            if (!choice.isBlank()) choices.add(choice);
        }
        if (choices.size() >= 2) {
            return capitalize(choices.get(0)) + " or " + choices.get(1) + "?";
        }
        if (choices.size() == 1) {
            return capitalize(choices.get(0)) + "?";
        }
        return "Make a choice";
    }

    /**
     * Given a scene body and a target scene title, finds the action phrase from
     * "If you choose to [action], go to [title]" and returns it as the edge label.
     * Falls back to the target title when the pattern does not match.
     */
    String extractChoiceLabel(String sceneBody, String targetTitle) {
        Pattern p = Pattern.compile(
            "(?i)if (?:you|he|she|they) (?:choose(?:s)?|decide(?:s)?)(?:\\s+instead)?(?:\\s+to)?\\s+([^,\\.!?\\n]{3,70})"
            + ",?\\s*(?:go(?:es)? to|proceed(?:s)? to|travel(?:s)? to|head(?:s)? to|lead(?:s)? to)\\s+"
            + Pattern.quote(targetTitle)
        );
        Matcher m = p.matcher(sceneBody);
        if (m.find()) {
            return capitalize(m.group(1).trim());
        }
        return targetTitle;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Builds a node list from structured text.
     * One scene node per "Scene:" heading; a decision node is inserted after
     * any scene whose body text contains a player choice.
     * Summaries are filled by the AI (falls back to empty strings on failure).
     */
    public List<SceneNode> buildNodes(String text) {
        Map<String, String> headings = detectSceneHeadings(text);
        if (headings.isEmpty()) {
            return List.of();
        }

        List<SceneNode> nodes = new ArrayList<>();
        int decisionCounter = 1;
        int sceneCounter    = 1;

        for (Map.Entry<String, String> entry : headings.entrySet()) {
            String sceneId = "scene_" + sceneCounter++;
            String title   = entry.getValue();
            String body    = extractSceneBody(text, title);

            nodes.add(new SceneNode(sceneId, "scene", title, ""));

            if (sceneContainsDecision(body)) {
                String lowerBody = body.toLowerCase();
                boolean hasChoicePhrase = lowerBody.contains("if you choose")
                        || lowerBody.contains("if you decide")
                        || lowerBody.contains("if he choose") || lowerBody.contains("if he decide")
                        || lowerBody.contains("if she choose") || lowerBody.contains("if she decide")
                        || lowerBody.contains("if they choose") || lowerBody.contains("if they decide");
                String decisionLabel = hasChoicePhrase ? buildDecisionLabel(body) : "Make a choice";
                nodes.add(new SceneNode(
                    "decision_" + decisionCounter++, "decision",
                    decisionLabel, ""
                ));
            }
        }

        return ai.fillSummaries(nodes, text);
    }

    // -------------------------------------------------------------------------
    // Edge building
    // -------------------------------------------------------------------------

    /**
     * Builds edges using two programmatic strategies, with an AI fallback:
     * 1. Consecutive scene → decision pairs get a "face a choice" edge.
     * 2. "go to [Title]" patterns in each scene body create decision → target edges.
     * 3. If any node is still uncovered after both passes, AI supplies the rest.
     */
    public List<SceneEdge> buildEdges(String text, List<SceneNode> nodes) {
        List<SceneEdge> edges   = new ArrayList<>();
        Set<String>     seen    = new HashSet<>();
        int             counter = 1;

        // Title → id lookup restricted to scene nodes (decision nodes are never "go to" targets)
        Map<String, String> titleToId = new LinkedHashMap<>();
        for (SceneNode node : nodes) {
            if ("scene".equals(node.type())) {
                titleToId.put(node.label().toLowerCase().trim(), node.id());
            }
        }

        // 1. scene → decision edges for consecutive pairs
        for (int i = 0; i < nodes.size() - 1; i++) {
            SceneNode current = nodes.get(i);
            SceneNode next    = nodes.get(i + 1);
            if ("scene".equals(current.type()) && "decision".equals(next.type())) {
                String key = current.id() + "→" + next.id();
                if (seen.add(key)) {
                    edges.add(new SceneEdge("edge_" + counter++, current.id(), next.id(), "face a choice"));
                }
            }
        }

        // 2. Parse "go to [Title]" inside each scene's body text
        for (SceneNode node : nodes) {
            if (!"decision".equals(node.type())) continue;

            // Find the scene that owns this decision (it immediately precedes it)
            String ownerTitle = null;
            for (int i = 1; i < nodes.size(); i++) {
                if (nodes.get(i).id().equals(node.id())) {
                    ownerTitle = nodes.get(i - 1).label();
                    break;
                }
            }
            if (ownerTitle == null) continue;

            String  sceneBody = extractSceneBody(text, ownerTitle);
            Pattern goTo      = Pattern.compile(
                "(?:go(?:es)? to|proceed(?:s)? to|travel(?:s)? to|head(?:s)? to|lead(?:s)? to|continue(?:s)? to)\\s+([A-Z][^.!?\\n,;]{1,60})",
                Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = goTo.matcher(sceneBody);

            while (matcher.find()) {
                String rawTitle = matcher.group(1).trim().replaceAll("[.!?,;]+$", "").trim();

                // Exact match first, then prefix/suffix fallback
                String targetId = titleToId.get(rawTitle.toLowerCase());
                if (targetId == null) {
                    for (Map.Entry<String, String> entry : titleToId.entrySet()) {
                        if (entry.getKey().startsWith(rawTitle.toLowerCase())
                                || rawTitle.toLowerCase().startsWith(entry.getKey())) {
                            targetId = entry.getValue();
                            break;
                        }
                    }
                }

                if (targetId != null && !targetId.equals(node.id())) {
                    String key = node.id() + "→" + targetId;
                    if (seen.add(key)) {
                        String edgeLabel = extractChoiceLabel(sceneBody, rawTitle);
                        edges.add(new SceneEdge("edge_" + counter++, node.id(), targetId, edgeLabel));
                    }
                }
            }
        }

        System.out.println("Pass 2: derived " + edges.size() + " edge(s) programmatically.");

        // Check whether any decision → scene edges were produced by "go to" parsing.
        // If there were none (story has no "go to" phrases), use the focused
        // decision-outcome prompt instead of the generic fallback.
        boolean hasDecisionOutcomeEdges = edges.stream().anyMatch(e -> {
            SceneNode src = nodes.stream()
                .filter(n -> n.id().equals(e.source()))
                .findFirst().orElse(null);
            return src != null && "decision".equals(src.type());
        });

        if (!hasDecisionOutcomeEdges) {
            // Build a context map: decisionId → parent scene's body text
            Map<String, String> decisionContexts = new LinkedHashMap<>();
            for (SceneNode node : nodes) {
                if (!"decision".equals(node.type())) continue;
                for (int i = 1; i < nodes.size(); i++) {
                    if (nodes.get(i).id().equals(node.id())) {
                        String ownerTitle = nodes.get(i - 1).label();
                        decisionContexts.put(node.id(), extractSceneBody(text, ownerTitle));
                        break;
                    }
                }
            }

            System.out.println("Pass 2: no 'go to' patterns found — using focused decision-outcome prompt...");
            List<SceneEdge> outcomeEdges = ai.askForDecisionOutcomes(text, nodes, decisionContexts, counter);
            for (SceneEdge oe : outcomeEdges) {
                if (seen.add(oe.source() + "→" + oe.target())) {
                    edges.add(new SceneEdge("edge_" + counter++, oe.source(), oe.target(), oe.label()));
                }
            }
        }

        // 3. Generic AI fallback — for any nodes still completely disconnected after the above
        Set<String> withOutgoing = edges.stream().map(SceneEdge::source).collect(Collectors.toSet());
        Set<String> withIncoming = edges.stream().map(SceneEdge::target).collect(Collectors.toSet());
        boolean anyUncovered = edges.isEmpty() || nodes.stream()
            .anyMatch(n -> !withOutgoing.contains(n.id()) && !withIncoming.contains(n.id()));

        if (anyUncovered) {
            System.out.println("Pass 2: some nodes still uncovered, supplementing with generic AI fallback...");
            List<SceneEdge> aiEdges = ai.askForEdgesFallback(text, nodes, counter);
            for (SceneEdge ae : aiEdges) {
                if (seen.add(ae.source() + "→" + ae.target())) {
                    edges.add(new SceneEdge("edge_" + counter++, ae.source(), ae.target(), ae.label()));
                }
            }
        }

        return edges;
    }

    // -------------------------------------------------------------------------
    // Orphan detection
    // -------------------------------------------------------------------------

    /**
     * Returns nodes (excluding the first/root node) that have no incoming edge.
     * These are passed to the AI for a recovery pass.
     */
    public List<SceneNode> findOrphanedNodes(List<SceneNode> nodes, List<SceneEdge> edges) {
        if (nodes.size() <= 1) return List.of();

        Set<String> withIncoming = edges.stream()
            .map(SceneEdge::target)
            .collect(Collectors.toSet());

        String rootId = nodes.get(0).id();
        return nodes.stream()
            .filter(n -> !n.id().equals(rootId))
            .filter(n -> !withIncoming.contains(n.id()))
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Cleans and validates the final graph:
     * - Removes null/blank nodes and edges
     * - Deduplicates by id and by source→target pair
     * - Drops edges referencing non-existent node ids
     * - Defaults missing "type" fields to "scene"
     */
    public GraphResponse validate(GraphResponse response) {
        List<SceneNode> rawNodes = response == null || response.nodes() == null
            ? List.of() : response.nodes();
        List<SceneEdge> rawEdges = response == null || response.edges() == null
            ? List.of() : response.edges();

        if (rawNodes.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The AI response did not contain any scenes to render."
            );
        }

        Set<String> nodeIds   = new HashSet<>();
        Set<String> edgeIds   = new HashSet<>();
        Set<String> edgePairs = new HashSet<>();

        List<SceneNode> validNodes = rawNodes.stream()
            .filter(n -> n != null
                && n.id()    != null && !n.id().isBlank()
                && n.label() != null && !n.label().isBlank())
            .filter(n -> nodeIds.add(n.id().trim()))
            .map(n -> new SceneNode(
                n.id().trim(),
                (n.type() == null || n.type().isBlank()) ? "scene" : n.type().trim(),
                n.label().trim(),
                n.summary() == null ? "" : n.summary().trim()
            ))
            .collect(Collectors.toList());

        if (validNodes.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The AI response did not contain any valid scenes to render."
            );
        }

        List<SceneEdge> validEdges = rawEdges.stream()
            .filter(e -> e != null
                && e.id()     != null && !e.id().isBlank()
                && e.source() != null && !e.source().isBlank()
                && e.target() != null && !e.target().isBlank()
                && e.label()  != null && !e.label().isBlank())
            .filter(e -> nodeIds.contains(e.source().trim()) && nodeIds.contains(e.target().trim()))
            .filter(e -> !e.source().trim().equals(e.target().trim()))
            .filter(e -> edgeIds.add(e.id().trim()))
            .filter(e -> edgePairs.add(e.source().trim() + "→" + e.target().trim()))
            .map(e -> new SceneEdge(
                e.id().trim(), e.source().trim(), e.target().trim(), e.label().trim()
            ))
            .collect(Collectors.toList());

        int dropped = rawEdges.size() - validEdges.size();
        if (dropped > 0) {
            System.out.println("Warning: dropped " + dropped + " invalid edge(s).");
        }

        return new GraphResponse(validNodes, validEdges);
    }
}
