package dev.knowledge.platform.agent;

import java.util.*;

/** Agent / workflow definitions. Definitions are versioned and immutable once a run references them. */
public final class AgentModels {
    private AgentModels() {}
    public static final Set<String> STEP_TYPES = Set.of("RETRIEVE", "EXTRACT", "RULE", "LLM", "APPROVAL", "TOOL", "OUTPUT");

    public record StepDefinition(String type, String name, Map<String, Object> config) {}
    public record AgentDefinition(String model, List<String> kbIds, String systemPrompt, List<String> tools, List<StepDefinition> steps, Integer maxRunsPerDay) {
        public AgentDefinition {
            model = model == null ? "fast" : model;
            kbIds = kbIds == null ? List.of() : List.copyOf(kbIds);
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            tools = tools == null ? List.of() : List.copyOf(tools);
            steps = steps == null ? List.of() : List.copyOf(steps);
            maxRunsPerDay = maxRunsPerDay == null ? 200 : maxRunsPerDay;
        }
    }
    public record InputField(String field, String label, String type, List<String> options) {}
    public record TemplateInfo(String template, String name, String description, AgentDefinition definition, List<InputField> inputSchema) {}

    static StepDefinition step(String type, String name, Map<String, Object> config) { return new StepDefinition(type, name, config); }

    /** Built-in, working templates. Users copy and adjust them; nothing here calls external services by default. */
    public static List<TemplateInfo> templates() {
        return List.of(
            new TemplateInfo("KNOWLEDGE_QA", "知識問答 Agent", "在指定知識庫中檢索並以可核對引用回答問題；證據不足時拒答。",
                new AgentDefinition("fast", List.of(), "只根據授權知識庫回答，附上引用。", List.of(),
                    List.of(step("LLM", "依知識庫回答", Map.of("mode", "GROUNDED_ANSWER", "question", "{{input.question}}")),
                            step("OUTPUT", "回傳結果", Map.of("target", "RUN"))), 500),
                List.of(new InputField("question", "問題", "text", null))),
            new TemplateInfo("DOCUMENT_COMPARE", "文件擷取／比對 Agent", "從選定文件擷取規格欄位，依規則比對門檻，送人工審核後寫入受控結果。",
                new AgentDefinition("fast", List.of(), "擷取規格並比對，所有寫入需人工核准。", List.of("output.record"),
                    List.of(step("EXTRACT", "擷取規格欄位", Map.of("documents", "{{input.documentIds}}", "fields", List.of(
                                    Map.of("name", "voltageMax", "pattern", "(?i)(?:to|–|-|~)\\s*(\\d+(?:\\.\\d+)?)\\s*V\\b"),
                                    Map.of("name", "tempMax", "pattern", "(?i)to\\s*\\+?(\\d+)\\s*°?\\s*C\\b")))),
                            step("RULE", "比對門檻", Map.of("rules", List.of(
                                    Map.of("field", "voltageMax", "op", "gte", "value", "{{input.minVoltage}}"),
                                    Map.of("field", "tempMax", "op", "gte", "value", "{{input.minTempC}}")))),
                            step("APPROVAL", "人工審核", Map.of("summary", "請確認文件比對結果後再寫入")),
                            step("OUTPUT", "寫入比對紀錄", Map.of("target", "AGENT_OUTPUTS"))), 200),
                List.of(new InputField("documentIds", "比對文件", "documents", null), new InputField("minVoltage", "最低電壓上限 (V)", "text", null),
                        new InputField("minTempC", "最低工作溫度上限 (°C)", "text", null))),
            new TemplateInfo("READONLY_TOOL", "唯讀工具 Agent", "以白名單唯讀 SQL 查詢示範料號主檔與庫存，依規則判斷並摘要；不允許任何寫入。",
                new AgentDefinition("fast", List.of(), "只能呼叫白名單唯讀工具。", List.of("sql.part_lookup", "sql.inventory_by_part"),
                    List.of(step("TOOL", "查詢料號", Map.of("tool", "sql.part_lookup", "params", Map.of("partNo", "{{input.partNo}}"))),
                            step("TOOL", "查詢庫存", Map.of("tool", "sql.inventory_by_part", "params", Map.of("partNo", "{{input.partNo}}"))),
                            step("RULE", "庫存檢查", Map.of("rules", List.of(Map.of("field", "totalOnHand", "op", "gte", "value", "1")))),
                            step("LLM", "摘要結果", Map.of("mode", "SUMMARIZE_STEPS", "question", "請摘要料號 {{input.partNo}} 的規格、庫存與檢查結果")),
                            step("OUTPUT", "回傳結果", Map.of("target", "RUN"))), 500),
                List.of(new InputField("partNo", "料號", "select", List.of("MCU-100", "MCU-200", "REG-33", "SNS-T1", "CAP-47U")))));
    }
}
