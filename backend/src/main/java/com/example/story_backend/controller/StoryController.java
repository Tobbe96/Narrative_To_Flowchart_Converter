package com.example.story_backend.controller;

import com.example.story_backend.model.GraphResponse;
import com.example.story_backend.service.StoryProcessingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping ("/api/narrative")
@CrossOrigin(origins = "http://localhost:5173")
public class StoryController {
    private final StoryProcessingService storyService;

    public StoryController (StoryProcessingService storyService) {
        this.storyService = storyService;
    }

    /** Convert an uploaded .docx or .txt file to a flowchart graph. */
    @PostMapping("/parse")
    public ResponseEntity<GraphResponse> parseStory(@RequestParam("file") MultipartFile file) {
        System.out.println("Received a file: " + file.getOriginalFilename());
        GraphResponse graphData = storyService.processDocument(file);
        return ResponseEntity.ok(graphData);
    }

    /** Convert plain story text (typed directly) to a flowchart graph. */
    @PostMapping("/convert-text")
    public ResponseEntity<GraphResponse> convertText(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        System.out.println("Received direct text input (" + text.length() + " chars)");
        GraphResponse graphData = storyService.processText(text);
        return ResponseEntity.ok(graphData);
    }

    /**
     * Rewrites the user's raw story text into the structured Scene:/go to format
     * and returns it as plain text for the user to preview and accept.
     */
    @PostMapping("/rewrite")
    public ResponseEntity<Map<String, String>> rewriteStory(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        System.out.println("AI Help rewrite requested (" + text.length() + " chars)");
        String rewritten = storyService.rewriteForHelp(text);
        return ResponseEntity.ok(Map.of("rewrittenText", rewritten));
    }
}
