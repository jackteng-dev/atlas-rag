package dev.knowledge.platform.chat;

import dev.knowledge.platform.tenant.TenantAccess;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("platform") @RequestMapping("/api/v2/orgs/{orgId}/conversations")
public class ChatController {
    public record CreateConversation(String title, List<String> kbIds) {}
    public record PostMessage(String content, String model) {}
    private final TenantAccess access;
    private final ChatService chat;
    public ChatController(TenantAccess access, ChatService chat) { this.access = access; this.chat = chat; }

    @GetMapping List<ChatService.Conversation> list(@PathVariable String orgId) { return chat.list(access.require(orgId)); }
    @PostMapping ResponseEntity<ChatService.Conversation> create(@PathVariable String orgId, @RequestBody CreateConversation b) {
        return ResponseEntity.status(201).body(chat.create(access.require(orgId), b.title(), b.kbIds()));
    }
    @GetMapping("/{id}") ChatService.Detail detail(@PathVariable String orgId, @PathVariable String id) { return chat.detail(access.require(orgId), id); }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable String orgId, @PathVariable String id) {
        chat.delete(access.require(orgId), id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/messages") ResponseEntity<ChatService.Posted> post(@PathVariable String orgId, @PathVariable String id, @RequestBody PostMessage b) {
        return ResponseEntity.accepted().body(chat.post(access.require(orgId), id, b.content(), b.model()));
    }
}
