package com.example.story_backend.service;

import com.example.story_backend.model.GraphResponse;
import com.example.story_backend.model.SceneEdge;
import com.example.story_backend.model.SceneNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full document-to-graph pipeline:
 * 1. Read file text (DocumentReaderService)
 * 2. If unstructured novel: rewrite to Scene:/go to format (AiGateway)
 * 3. Build nodes programmatically + AI summaries (GraphBuilderService)
 * 4. Build edges programmatically + AI fallback (GraphBuilderService)
 * 5. Recover any orphaned nodes (AiGateway)
 * 6. Validate and return the final graph (GraphBuilderService)
 */
@Service
public class StoryProcessingService {

    private final DocumentReaderService documentReader;
    private final AiGateway             ai;
    private final GraphBuilderService   graphBuilder;

    public StoryProcessingService(DocumentReaderService documentReader,
                                  AiGateway ai,
                                  GraphBuilderService graphBuilder) {
        this.documentReader = documentReader;
        this.ai             = ai;
        this.graphBuilder   = graphBuilder;
    }

    public GraphResponse processDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Upload a non-empty .docx or .txt file.");
        }

        System.out.println("Step 1: Extracting text from document");
        String text = documentReader.extractAndTruncate(file);
        return runPipeline(text);
    }

    public GraphResponse processText(String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Story text must not be empty.");
        }
        return runPipeline(text.length() > 12_000 ? text.substring(0, 12_000) : text);
    }

    /**
     * Rewrites the user's raw text into the structured Scene:/go to format
     * and returns it as plain text for preview before conversion.
     */
    public String rewriteForHelp(String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Story text must not be empty.");
        }
        String truncated = text.length() > 12_000 ? text.substring(0, 12_000) : text;
        return ai.rewriteForHelp(truncated);
    }

    private GraphResponse runPipeline(String text) {

        // Step 2: If the story has no Scene: headings it is a freeform novel.
        // Ask the AI to restructure it into the Scene:/go to format first.
        String workingText;
        if (graphBuilder.isStructured(text)) {
            System.out.println("Step 2: Story is already structured — skipping rewrite.");
            workingText = text;
        } else {
            System.out.println("Step 2: Unstructured novel detected — rewriting to scene format...");
            workingText = ai.rewriteNovel(text);
            System.out.println("Rewritten text:\n" + workingText);
        }

        System.out.println("Step 3: Building nodes...");
        List<SceneNode> nodes = graphBuilder.buildNodes(workingText);

        if (nodes.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Could not identify any scenes in the document.");
        }

        System.out.println("Step 4: Building edges for " + nodes.size() + " nodes...");
        List<SceneEdge> edges = new ArrayList<>(graphBuilder.buildEdges(workingText, nodes));

        List<SceneNode> orphans = graphBuilder.findOrphanedNodes(nodes, edges);
        if (!orphans.isEmpty()) {
            System.out.println("Step 5: Recovering " + orphans.size() + " orphaned node(s)...");
            List<SceneEdge> recovered = ai.askForMissingEdges(
                workingText, nodes, edges, orphans, edges.size() + 1);
            edges.addAll(recovered);
        }

        int validateStep = orphans.isEmpty() ? 5 : 6;
        System.out.println("Step " + validateStep + ": Validating graph");
        return graphBuilder.validate(new GraphResponse(nodes, edges));
    }
}
