package com.example.story_backend.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Service
public class DocumentReaderService {

    private static final int MAX_TEXT_CHARS = 12_000;

    /**
     * Reads a .docx or .txt file, joins its text, and truncates to the safe limit.
     * Throws 400 if the file cannot be read or is blank.
     */
    public String extractAndTruncate(MultipartFile file) {
        String text = extractText(file);
        if (text.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The uploaded document did not contain readable text."
            );
        }
        return truncate(text);
    }

    private String extractText(MultipartFile file) {
        String name = file.getOriginalFilename() != null
            ? file.getOriginalFilename().toLowerCase() : "";

        if (name.endsWith(".txt")) {
            return readTxt(file);
        }
        return readDocx(file);
    }

    private String readDocx(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             XWPFDocument doc = new XWPFDocument(in)) {

            return doc.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .map(String::trim)
                .filter(p -> !p.isBlank())
                .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Failed to read the document. Make sure it is a valid .docx file.",
                e
            );
        }
    }

    private String readTxt(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Failed to read the .txt file.",
                e
            );
        }
    }

    private String truncate(String text) {
        if (text.length() <= MAX_TEXT_CHARS) return text;
        System.out.println("Warning: Story text truncated from " + text.length()
            + " to " + MAX_TEXT_CHARS + " characters.");
        return text.substring(0, MAX_TEXT_CHARS);
    }
}
