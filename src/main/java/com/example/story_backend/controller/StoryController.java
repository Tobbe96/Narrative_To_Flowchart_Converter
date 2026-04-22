package com.example.story_backend.controller;

import com.example.story_backend.model.GraphResponse;
import com.example.story_backend.service.StoryProcessingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping ("/api/narrative")
@CrossOrigin(origins = "http://localhost:5173")
public class StoryController {
    private final StoryProcessingService storyService;

    public StoryController (StoryProcessingService storyService) {
        this.storyService = storyService;
    }

    @PostMapping("/parse")
    public ResponseEntity <GraphResponse> parseStory (@RequestParam("file")MultipartFile file) {
        System.out.println("Received a file: " + file.getOriginalFilename());


        GraphResponse graphData = storyService.processDocument(file);
        return ResponseEntity.ok(graphData);
    }

}
