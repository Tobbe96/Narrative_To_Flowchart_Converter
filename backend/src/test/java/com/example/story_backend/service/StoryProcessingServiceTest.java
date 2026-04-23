package com.example.story_backend.service;

import com.example.story_backend.model.GraphResponse;
import com.example.story_backend.model.SceneEdge;
import com.example.story_backend.model.SceneNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

class StoryProcessingServiceTest {

    private final StoryProcessingService service = new StoryProcessingService();

    @Test
    void parseAiGraphResponseParsesValidJson() {
        String json = """
                {
                  "nodes": [
                    {
                      "id": "scene_1",
                      "label": "Opening Scene",
                      "summary": "A hero wakes up in a strange room."
                    }
                  ],
                  "edges": [
                    {
                      "id": "edge_1",
                      "source": "scene_1",
                      "target": "scene_1",
                      "label": "Stay put"
                    }
                  ]
                }
                """;

        GraphResponse response = service.parseAiGraphResponse(json);

        assertEquals(1, response.nodes().size());
        assertEquals("scene_1", response.nodes().get(0).id());
        assertEquals(1, response.edges().size());
    }

    @Test
    void parseAiGraphResponseStripsMarkdownFences() {
        String json = """
                ```json
                {
                  "nodes": [{"id": "scene_1", "label": "Scene", "summary": "Summary"}],
                  "edges": []
                }
                ```
                """;

        GraphResponse response = service.parseAiGraphResponse(json);

        assertEquals(1, response.nodes().size());
        assertEquals("scene_1", response.nodes().get(0).id());
    }

    @Test
    void validateGraphResponseRejectsEmptyNodeList() {
        GraphResponse response = new GraphResponse(null, null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.validateGraphResponse(response));

        assertEquals(502, error.getStatusCode().value());
    }

    @Test
    void validateGraphResponseDropsEdgeReferencingUnknownNode() {
        GraphResponse response = service.parseAiGraphResponse("""
                {
                  "nodes": [
                    {
                      "id": "scene_1",
                      "label": "Opening Scene",
                      "summary": "A hero wakes up in a strange room."
                    }
                  ],
                  "edges": [
                    {
                      "id": "edge_1",
                      "source": "scene_1",
                      "target": "scene_2",
                      "label": "Open the door"
                    }
                  ]
                }
                """);

        GraphResponse validated = service.validateGraphResponse(response);

        assertEquals(1, validated.nodes().size());
        assertTrue(validated.edges().isEmpty(), "Edge referencing unknown node should be dropped");
    }

    @Test
    void validateGraphResponseDeduplicatesNodeIds() {
        GraphResponse response = new GraphResponse(
            List.of(
                new SceneNode("scene_1", "First", "Summary A"),
                new SceneNode("scene_1", "Duplicate", "Summary B"),
                new SceneNode("scene_2", "Second", "Summary C")
            ),
            List.of()
        );

        GraphResponse validated = service.validateGraphResponse(response);

        assertEquals(2, validated.nodes().size());
        assertEquals("First", validated.nodes().get(0).label());
    }
}
