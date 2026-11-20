package dev.knowledge.platform.tenant;

import dev.knowledge.platform.tenant.TenantAccess.KbAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static dev.knowledge.platform.tenant.TenantAccess.KbAccess.EDIT;
import static dev.knowledge.platform.tenant.TenantAccess.KbAccess.MANAGE;
import static dev.knowledge.platform.tenant.TenantAccess.KbAccess.READ;
import static org.assertj.core.api.Assertions.assertThat;

class KbAccessMatrixTest {
    @ParameterizedTest(name = "org={0}, visibility={1}, workspace={2}, kb={3} -> {4}")
    @MethodSource("accessMatrix")
    void selectsStrongestAccessAcrossAllValidRoleCombinations(
            Role orgRole, String visibility, String workspaceRole, String kbRole, KbAccess expected) {
        assertThat(TenantAccess.effective(orgRole, visibility, workspaceRole, kbRole)).isEqualTo(expected);
    }

    static Stream<Arguments> accessMatrix() {
        // Columns: workspace absent / MANAGER / EDITOR / VIEWER;
        // within each workspace group: KB absent / EDITOR / VIEWER.
        // Explicit expectations keep the table independent of the production decision tree.
        List<Arguments> cases = new ArrayList<>();
        for (Role elevated : new Role[]{Role.OWNER, Role.ADMIN}) {
            for (String visibility : new String[]{"ORG", "RESTRICTED"}) {
                addRow(cases, elevated, visibility, new KbAccess[]{
                        MANAGE, MANAGE, MANAGE, MANAGE, MANAGE, MANAGE,
                        MANAGE, MANAGE, MANAGE, MANAGE, MANAGE, MANAGE});
            }
        }
        addRow(cases, Role.EDITOR, "ORG", new KbAccess[]{
                EDIT, EDIT, EDIT, MANAGE, MANAGE, MANAGE, EDIT, EDIT, EDIT, EDIT, EDIT, EDIT});
        addRow(cases, Role.VIEWER, "ORG", new KbAccess[]{
                READ, EDIT, READ, MANAGE, MANAGE, MANAGE, EDIT, EDIT, EDIT, READ, EDIT, READ});
        addRow(cases, Role.EDITOR, "RESTRICTED", new KbAccess[]{
                null, EDIT, READ, MANAGE, MANAGE, MANAGE, EDIT, EDIT, EDIT, READ, EDIT, READ});
        addRow(cases, Role.VIEWER, "RESTRICTED", new KbAccess[]{
                null, EDIT, READ, MANAGE, MANAGE, MANAGE, EDIT, EDIT, EDIT, READ, EDIT, READ});
        return cases.stream();
    }

    private static void addRow(List<Arguments> cases, Role orgRole, String visibility, KbAccess[] expected) {
        int column = 0;
        for (String workspace : new String[]{null, "MANAGER", "EDITOR", "VIEWER"}) {
            for (String kb : new String[]{null, "EDITOR", "VIEWER"}) {
                cases.add(Arguments.of(orgRole, visibility, workspace, kb, expected[column++]));
            }
        }
    }
}
