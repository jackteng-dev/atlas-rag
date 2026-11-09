package dev.knowledge;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static dev.knowledge.Models.*;

@RestController @RequestMapping("/api/question-jobs")
public class QuestionJobController {
    private final QuestionJobs jobs;
    public QuestionJobController(QuestionJobs jobs) { this.jobs=jobs; }
    @PostMapping public ResponseEntity<QuestionJobs.View> submit(@Valid @RequestBody AskRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(jobs.submit(request));
    }
    @GetMapping("/{id}") public ResponseEntity<QuestionJobs.View> poll(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.get(id));
    }
}
