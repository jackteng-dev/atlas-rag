package dev.knowledge.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowledge.ApiException;
import dev.knowledge.platform.core.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only tools an agent can call. Built-ins are read-only, parameterised and allow-listed: there is no generic SQL,
 * shell, or arbitrary URL tool, and model output is never interpreted as a command. Side-effecting tools are marked and
 * the run executor refuses them without an approved HITL step.
 */
@Component @Profile("platform")
public class ToolRegistry {
    public record ToolInfo(String id, String name, String description, boolean sideEffect, boolean requiresApproval, String connectorId) {}
    public record ToolResult(Object data, String summary) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ConnectorService connectors;
    private final Set<String> httpHosts;
    private final boolean allowPrivate;
    private final Set<String> mcpEndpoints;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** Named, parameterised SELECT templates over the fictional demo_erp schema. */
    static final Map<String, String> SQL = Map.of(
            "sql.part_lookup", "select part_no, name, category, voltage_min, voltage_max, temp_min_c, temp_max_c, lead_time_days, unit_price_twd from demo_erp.parts where part_no = :partNo",
            "sql.inventory_by_part", "select part_no, warehouse, on_hand from demo_erp.inventory where part_no = :partNo order by warehouse",
            "sql.parts_by_category", "select part_no, name, lead_time_days from demo_erp.parts where category = :category order by part_no limit 50");

    public ToolRegistry(JdbcClient db, TransactionTemplate tx, ObjectMapper mapper, ConnectorService connectors,
                        @Value("${atlas.tools.http.allowed-hosts:}") String hosts, @Value("${atlas.tools.http.allow-private:false}") boolean allowPrivate,
                        @Value("${atlas.tools.mcp.allowed-endpoints:}") String mcp) {
        this.db = db; this.tx = tx; this.mapper = mapper; this.connectors = connectors; this.allowPrivate = allowPrivate;
        this.httpHosts = split(hosts); this.mcpEndpoints = split(mcp);
    }
    private static Set<String> split(String csv) {
        var set = new LinkedHashSet<String>();
        for (var s : csv.split(",")) if (!s.isBlank()) set.add(s.strip().toLowerCase(Locale.ROOT));
        return Set.copyOf(set);
    }
    public Set<String> allowedHttpHosts() { return httpHosts; }
    public Set<String> allowedMcpEndpoints() { return mcpEndpoints; }

    public List<ToolInfo> catalog(UUID orgId) {
        var list = new ArrayList<ToolInfo>();
        list.add(new ToolInfo("sql.part_lookup", "料號主檔查詢（唯讀 SQL）", "以料號查詢示範 ERP 主檔", false, false, null));
        list.add(new ToolInfo("sql.inventory_by_part", "庫存查詢（唯讀 SQL）", "以料號查詢示範 ERP 各倉庫存", false, false, null));
        list.add(new ToolInfo("sql.parts_by_category", "分類料號清單（唯讀 SQL）", "以分類列出示範料號", false, false, null));
        list.add(new ToolInfo("output.record", "寫入受控結果", "寫入 Atlas 內部 agent_outputs（需人工核准）", true, true, null));
        for (var c : connectors.enabled(orgId)) {
            switch (c.type()) {
                case "HTTP_GET" -> list.add(new ToolInfo("http.get:" + c.id(), c.name(), "受限 HTTP GET（主機白名單、無轉址、私網封鎖）", false, false, c.id()));
                case "MCP" -> list.add(new ToolInfo("mcp.call:" + c.id(), c.name(), "受控 MCP 工具呼叫（端點白名單、工具白名單）",
                        !Boolean.TRUE.equals(c.config().get("readOnly")), !Boolean.TRUE.equals(c.config().get("readOnly")), c.id()));
                case "SQL_READONLY" -> list.add(new ToolInfo("sql.connector:" + c.id(), c.name(), "連接器設定的具名唯讀查詢", false, false, c.id()));
                default -> { }
            }
        }
        return list;
    }
    public Optional<ToolInfo> find(UUID orgId, String id) { return catalog(orgId).stream().filter(t -> t.id().equals(id)).findFirst(); }

    /** Executes a read-only tool. Side-effect tools are not executable here (see AgentRunHandler OUTPUT step). */
    public ToolResult execute(UUID orgId, String toolId, Map<String, Object> params) {
        var info = find(orgId, toolId).orElseThrow(() -> new ApiException(403, "TOOL_NOT_ALLOWED", "Unknown or disabled tool."));
        if (info.sideEffect()) throw new ApiException(403, "TOOL_REQUIRES_APPROVAL", "Side-effecting tools only run through an approved OUTPUT step.");
        if (SQL.containsKey(toolId)) return sql(SQL.get(toolId), params);
        if (toolId.startsWith("http.get:")) return httpGet(connectors.require(orgId, info.connectorId()), params);
        if (toolId.startsWith("mcp.call:")) return mcpCall(connectors.require(orgId, info.connectorId()), params);
        if (toolId.startsWith("sql.connector:")) {
            var c = connectors.require(orgId, info.connectorId());
            String query = String.valueOf(params.getOrDefault("query", ""));
            var named = c.config().get("queries") instanceof Map<?, ?> m ? m.get(query) : null;
            if (named == null) throw new ApiException(400, "UNKNOWN_QUERY", "The connector has no such named query.");
            String sql = String.valueOf(named);
            if (!sql.strip().toLowerCase(Locale.ROOT).startsWith("select ") || sql.contains(";")) throw new ApiException(400, "QUERY_REJECTED", "Only single SELECT statements are allowed.");
            return sql(sql, params);
        }
        throw new ApiException(403, "TOOL_NOT_ALLOWED", "Tool is not executable.");
    }

    private ToolResult sql(String sql, Map<String, Object> params) {
        var bound = new HashMap<String, Object>();
        var matcher = java.util.regex.Pattern.compile(":([a-zA-Z][a-zA-Z0-9]*)").matcher(sql);
        while (matcher.find()) {
            var name = matcher.group(1);
            var value = params.get(name);
            if (value == null || String.valueOf(value).isBlank()) throw new ApiException(400, "MISSING_PARAMETER", "Missing tool parameter: " + name);
            var text = String.valueOf(value);
            if (text.length() > 64 || !text.matches("[\\p{L}0-9 ._\\-/]+")) throw new ApiException(400, "INVALID_PARAMETER", "Invalid tool parameter: " + name);
            bound.put(name, text);
        }
        var rows = tx.execute(status -> {
            db.sql("set transaction read only").update();
            db.sql("set local statement_timeout = '3s'").update();
            return db.sql(sql).params(bound).query().listOfRows().stream().limit(200).map(r -> {
                var clean = new LinkedHashMap<String, Object>();
                r.forEach((k, v) -> clean.put(k, v instanceof java.math.BigDecimal bd ? bd.doubleValue() : v));
                return (Map<String, Object>) clean;
            }).toList();
        });
        return new ToolResult(rows, rows.size() + " row(s)");
    }

    /** SSRF-safe GET: allow-listed host, https by default, resolved addresses must be public (unless explicitly allowed), no redirects, capped body. */
    ToolResult httpGet(ConnectorService.Connector c, Map<String, Object> params) {
        String base = String.valueOf(c.config().get("baseUrl"));
        String path = String.valueOf(params.getOrDefault("path", "/"));
        if (!path.startsWith("/") || path.contains("..") || path.contains("@") || path.contains("\\") || path.length() > 300)
            throw new ApiException(400, "INVALID_PATH", "Invalid request path.");
        var allowedPaths = c.config().get("allowedPaths") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.<String>of();
        if (allowedPaths.stream().noneMatch(path::startsWith)) throw new ApiException(403, "PATH_NOT_ALLOWED", "This path is not allowed by the connector.");
        URI uri = URI.create(base.replaceAll("/+$", "") + path);
        validateTarget(uri);
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().header("Accept", "application/json, text/plain");
            if (c.secretRef() != null) {
                var secret = System.getenv(c.secretRef());
                if (secret == null || secret.isBlank()) throw new ApiException(503, "SECRET_MISSING", "The connector's credential is not configured on the server.");
                builder.header("Authorization", "Bearer " + secret);
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) throw new ApiException(502, "REDIRECT_BLOCKED", "Redirects are not followed.");
            byte[] body;
            try (var in = response.body()) { body = in.readNBytes(256 * 1024 + 1); }
            if (body.length > 256 * 1024) throw new ApiException(502, "RESPONSE_TOO_LARGE", "Response exceeds 256 KB.");
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            Object data;
            try { data = mapper.readValue(text, Object.class); } catch (Exception e) { data = text.length() > 4000 ? text.substring(0, 4000) : text; }
            return new ToolResult(Map.of("status", response.statusCode(), "body", data), "HTTP " + response.statusCode());
        } catch (ApiException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(503, "TOOL_INTERRUPTED", "Tool call interrupted."); }
        catch (Exception e) { throw new ApiException(502, "TOOL_FAILURE", "The HTTP tool call failed."); }
    }

    /** Throws unless the URI's host is server-allow-listed and every resolved address is acceptable. */
    public void validateTarget(URI uri) {
        if (uri.getScheme() == null || !(uri.getScheme().equals("https") || (allowPrivate && uri.getScheme().equals("http"))))
            throw new ApiException(400, "SCHEME_NOT_ALLOWED", "Only https targets are allowed.");
        if (uri.getUserInfo() != null || uri.getHost() == null) throw new ApiException(400, "INVALID_URL", "Invalid target URL.");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!httpHosts.contains(host)) throw new ApiException(403, "HOST_NOT_ALLOWED", "The host is not on the server allow-list (ATLAS_HTTP_TOOL_HOSTS).");
        try {
            for (var address : InetAddress.getAllByName(host))
                if (!allowPrivate && (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                        || address.isMulticastAddress() || isUniqueLocal(address) || isCgnat(address)))
                    throw new ApiException(403, "PRIVATE_ADDRESS_BLOCKED", "The target resolves to a private or local address.");
        } catch (UnknownHostException e) { throw new ApiException(502, "DNS_FAILURE", "The host cannot be resolved."); }
    }
    private static boolean isUniqueLocal(InetAddress a) { var b = a.getAddress(); return b.length == 16 && (b[0] & 0xfe) == 0xfc; }
    private static boolean isCgnat(InetAddress a) { var b = a.getAddress(); return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; }

    /** Minimal MCP (JSON-RPC 2.0 over HTTP) tools/call against an admin-allow-listed endpoint and a per-connector tool allow-list. */
    ToolResult mcpCall(ConnectorService.Connector c, Map<String, Object> params) {
        String endpoint = String.valueOf(c.config().get("endpoint"));
        if (!mcpEndpoints.contains(endpoint.toLowerCase(Locale.ROOT))) throw new ApiException(403, "MCP_ENDPOINT_NOT_ALLOWED", "This MCP endpoint is not on the server allow-list (ATLAS_MCP_ENDPOINTS).");
        String tool = String.valueOf(params.getOrDefault("tool", ""));
        var allowed = c.config().get("allowedTools") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.<String>of();
        if (!allowed.contains(tool)) throw new ApiException(403, "MCP_TOOL_NOT_ALLOWED", "This MCP tool is not allowed by the connector.");
        var uri = URI.create(endpoint);
        if (!(uri.getHost() != null && Set.of("127.0.0.1", "localhost", "::1").contains(uri.getHost()))) validateTarget(uri);
        try {
            var args = params.get("arguments") instanceof Map<?, ?> m ? m : Map.of();
            var body = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", "tools/call", "params", Map.of("name", tool, "arguments", args)));
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var in = response.body()) { bytes = in.readNBytes(256 * 1024 + 1); }
            if (bytes.length > 256 * 1024 || response.statusCode() != 200) throw new ApiException(502, "MCP_FAILURE", "The MCP server returned an invalid response.");
            var tree = mapper.readTree(bytes);
            if (tree.has("error")) throw new ApiException(502, "MCP_ERROR", "The MCP tool reported an error.");
            return new ToolResult(mapper.convertValue(tree.path("result"), Object.class), "MCP " + tool);
        } catch (ApiException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(503, "TOOL_INTERRUPTED", "Tool call interrupted."); }
        catch (Exception e) { throw new ApiException(502, "MCP_FAILURE", "The MCP tool call failed."); }
    }
}
