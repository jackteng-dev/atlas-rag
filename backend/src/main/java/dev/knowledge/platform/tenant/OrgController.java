package dev.knowledge.platform.tenant;

import dev.knowledge.platform.identity.CurrentUser;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("platform") @RequestMapping("/api/v2/orgs")
public class OrgController {
    public record CreateOrg(String name, String slug) {}
    public record Rename(String name) {}
    public record MemberPatch(String role, String status) {}
    public record InviteRequest(String email, String role) {}
    public record WorkspaceRequest(String name, String description) {}
    public record RoleRequest(String role) {}

    private final OrgService orgs;
    private final TenantAccess access;
    private final CurrentUser current;
    public OrgController(OrgService orgs, TenantAccess access, CurrentUser current) { this.orgs = orgs; this.access = access; this.current = current; }

    @PostMapping ResponseEntity<OrgService.Org> create(@RequestBody CreateOrg body) { return ResponseEntity.status(201).body(orgs.create(current.require(), body.name(), body.slug())); }
    @GetMapping("/{orgId}") OrgService.Org get(@PathVariable String orgId) { return orgs.get(access.require(orgId)); }
    @PatchMapping("/{orgId}") OrgService.Org rename(@PathVariable String orgId, @RequestBody Rename body) { return orgs.rename(access.require(orgId, Role.ADMIN), body.name()); }

    @GetMapping("/{orgId}/members") List<OrgService.Member> members(@PathVariable String orgId) { return orgs.members(access.require(orgId)); }
    @PatchMapping("/{orgId}/members/{userId}") OrgService.Member updateMember(@PathVariable String orgId, @PathVariable String userId, @RequestBody MemberPatch body) {
        return orgs.updateMember(access.require(orgId, Role.ADMIN), userId, body.role(), body.status());
    }
    @DeleteMapping("/{orgId}/members/{userId}") ResponseEntity<Void> removeMember(@PathVariable String orgId, @PathVariable String userId) {
        orgs.removeMember(access.require(orgId, Role.ADMIN), userId); return ResponseEntity.noContent().build();
    }

    @GetMapping("/{orgId}/invitations") List<OrgService.Invitation> invitations(@PathVariable String orgId) { return orgs.invitations(access.require(orgId, Role.ADMIN)); }
    @PostMapping("/{orgId}/invitations") ResponseEntity<OrgService.Invitation> invite(@PathVariable String orgId, @RequestBody InviteRequest body) {
        return ResponseEntity.status(201).body(orgs.invite(access.require(orgId, Role.ADMIN), body.email(), body.role()));
    }
    @DeleteMapping("/{orgId}/invitations/{id}") ResponseEntity<Void> revoke(@PathVariable String orgId, @PathVariable String id) {
        orgs.revokeInvitation(access.require(orgId, Role.ADMIN), id); return ResponseEntity.noContent().build();
    }

    @GetMapping("/{orgId}/workspaces") List<OrgService.Workspace> workspaces(@PathVariable String orgId) { return orgs.workspaces(access.require(orgId)); }
    @PostMapping("/{orgId}/workspaces") ResponseEntity<OrgService.Workspace> createWorkspace(@PathVariable String orgId, @RequestBody WorkspaceRequest body) {
        return ResponseEntity.status(201).body(orgs.createWorkspace(access.require(orgId, Role.ADMIN), body.name(), body.description()));
    }
    @GetMapping("/{orgId}/workspaces/{wsId}/members") List<OrgService.WorkspaceMember> workspaceMembers(@PathVariable String orgId, @PathVariable String wsId) {
        return orgs.workspaceMembers(access.require(orgId), wsId);
    }
    @PutMapping("/{orgId}/workspaces/{wsId}/members/{userId}") ResponseEntity<Void> setWorkspaceMember(@PathVariable String orgId, @PathVariable String wsId,
                                                                                                     @PathVariable String userId, @RequestBody RoleRequest body) {
        orgs.setWorkspaceMember(access.require(orgId, Role.ADMIN), wsId, userId, body.role()); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/{orgId}/workspaces/{wsId}/members/{userId}") ResponseEntity<Void> removeWorkspaceMember(@PathVariable String orgId, @PathVariable String wsId, @PathVariable String userId) {
        orgs.removeWorkspaceMember(access.require(orgId, Role.ADMIN), wsId, userId); return ResponseEntity.noContent().build();
    }
}
