package com.example.story_backend.model;

import java.util.List;

public record GraphResponse(List <SceneNode> nodes, List<SceneEdge> edges) {
}
