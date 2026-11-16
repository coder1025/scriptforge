package dev.scriptforge.store;

import dev.scriptforge.domain.ForgeException;
import dev.scriptforge.domain.Json;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ForgeStore {
  private final JdbcTemplate jdbc;
  private final Json json;
  private final TransactionTemplate tx;

  public ForgeStore(JdbcTemplate jdbc, Json json, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.json = json;
    this.tx = tx;
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  public Json json() {
    return json;
  }

  public <T> T tx(Supplier<T> work) {
    return tx.execute(status -> work.get());
  }

  public static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String str(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  public static int integer(Map<String, Object> row, String key) {
    return ((Number) row.getOrDefault(key, 0)).intValue();
  }

  public static long number(Map<String, Object> row, String key) {
    return ((Number) row.getOrDefault(key, 0)).longValue();
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, n) -> {
          var result = new LinkedHashMap<String, Object>();
          var meta = rs.getMetaData();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Clob) v = rs.getString(i);
            result.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), v);
          }
          return result;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var found = rows(sql, args);
    return found.isEmpty() ? null : found.getFirst();
  }

  public Map<String, Object> task(String taskId, boolean lock) {
    var t = one("SELECT * FROM tasks WHERE id=?" + (lock ? " FOR UPDATE" : ""), taskId);
    if (t == null) throw new ForgeException("NOT_FOUND", "Task not found");
    return t;
  }

  public Map<String, Object> execution(String execId, boolean lock) {
    var e = one("SELECT * FROM executions WHERE id=?" + (lock ? " FOR UPDATE" : ""), execId);
    if (e == null) throw new ForgeException("NOT_FOUND", "Execution not found");
    return e;
  }

  public Map<String, Object> artifact(String taskId, String kind, int revision) {
    return one(
        "SELECT * FROM artifacts WHERE task_id=? AND kind=? AND revision=?",
        taskId,
        kind,
        revision);
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> artifactItems(String taskId, String kind, int revision) {
    var a = artifact(taskId, kind, revision);
    return a == null
        ? new ArrayList<>()
        : (List<Map<String, Object>>) json.read(str(a, "value_json"));
  }

  public void artifact(String taskId, String kind, int revision, int planRevision, Object value) {
    jdbc.update(
        "INSERT INTO artifacts(id,task_id,kind,revision,plan_revision,hash,value_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
        id(),
        taskId,
        kind,
        revision,
        planRevision,
        json.hash(value),
        json.write(value),
        now());
  }

  public String newExecution(String taskId, String role, int plan, int draft, Long parent) {
    String execId = id();
    jdbc.update(
        "INSERT INTO executions(id,task_id,role,status,plan_revision,draft_revision,parent_step_id,coverage_json,created_at) VALUES(?,?,?,'RUNNABLE',?,?,?,?,?)",
        execId,
        taskId,
        role,
        plan,
        draft,
        parent,
        json.write(
            Json.object(
                "inspectedSections",
                List.of(),
                "inspectedEdges",
                List.of(),
                "sectionChecks",
                Map.of())),
        now());
    return execId;
  }

  public long step(
      String taskId,
      String executionId,
      String kind,
      String name,
      String status,
      Long parent,
      String callId,
      Object input,
      long reservation,
      int estimated,
      long epoch) {
    var keys = new GeneratedKeyHolder();
    var time = now();
    jdbc.update(
        conn -> {
          var ps =
              conn.prepareStatement(
                  "INSERT INTO steps(task_id,execution_id,kind,name,status,parent_model_id,provider_call_id,input_json,reserved_tokens,usage_status,estimated_tokens,lease_epoch,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          Object[] args = {
            taskId,
            executionId,
            kind,
            name,
            status,
            parent,
            callId,
            json.write(input),
            reservation,
            "MODEL".equals(kind) ? "RESERVED" : "NONE",
            estimated,
            epoch,
            time,
            time
          };
          for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
          return ps;
        },
        keys);
    return Objects.requireNonNull(keys.getKey()).longValue();
  }

  public void completeStep(long stepId, String status, Object output) {
    jdbc.update(
        "UPDATE steps SET status=?,output_json=?,updated_at=? WHERE id=?",
        status,
        json.write(output),
        now(),
        stepId);
  }

  public Map<String, Object> latestReport(String taskId) {
    var r = one("SELECT * FROM reports WHERE task_id=? ORDER BY created_at DESC LIMIT 1", taskId);
    return r == null ? null : json.map(str(r, "value_json"));
  }

  public Map<String, Object> state(Map<String, Object> task, Map<String, Object> execution) {
    var state = new LinkedHashMap<>(json.map(str(task, "input_json")));
    boolean frozen = "REVIEWER".equals(str(execution, "role"));
    int p = frozen ? integer(execution, "plan_revision") : integer(task, "plan_revision");
    int d = frozen ? integer(execution, "draft_revision") : integer(task, "draft_revision");
    state.putAll(
        Json.object(
            "taskId",
            str(task, "id"),
            "executionId",
            str(execution, "id"),
            "mode",
            str(task, "mode"),
            "planRevision",
            p,
            "draftRevision",
            d,
            "plan",
            artifactItems(str(task, "id"), "PLAN", p),
            "sections",
            artifactItems(str(task, "id"), "DRAFT", d),
            "report",
            latestReport(str(task, "id")),
            "reviewRound",
            integer(task, "review_round"),
            "coverage",
            json.map(str(execution, "coverage_json")),
            "budget",
            Json.object(
                "modelCalls",
                integer(task, "model_calls"),
                "maxModelCalls",
                integer(task, "max_model_calls"),
                "usedTokens",
                number(task, "used_tokens"),
                "reservedTokens",
                number(task, "reserved_tokens"),
                "maxTokens",
                number(task, "max_tokens"))));
    var pending =
        one(
            "SELECT input_json FROM steps WHERE execution_id=? AND kind='TOOL' AND name='patch_script' AND status='SUCCEEDED' ORDER BY id DESC LIMIT 1",
            str(execution, "id"));
    if (pending != null) {
      var edits = json.tree(str(pending, "input_json")).path("edits");
      if (edits.isArray() && !edits.isEmpty())
        state.put("focusSectionId", edits.get(0).path("sectionId").asText());
    }
    return state;
  }

  public List<Map<String, Object>> recentMessages(String executionId) {
    var pairs =
        rows(
            "SELECT m.output_json model_json,t.output_json tool_json,t.provider_call_id FROM steps m JOIN steps t ON t.parent_model_id=m.id WHERE m.execution_id=? AND m.status='SUCCEEDED' AND t.status IN ('SUCCEEDED','FAILED') ORDER BY m.id DESC LIMIT 8",
            executionId);
    Collections.reverse(pairs);
    var result = new ArrayList<Map<String, Object>>();
    for (var p : pairs) {
      result.add(json.map(str(p, "model_json")));
      result.add(
          Json.object(
              "role",
              "tool",
              "tool_call_id",
              str(p, "provider_call_id"),
              "content",
              str(p, "tool_json")));
    }
    return result;
  }

  public Map<String, Object> detail(String taskId) {
    var t = task(taskId, false);
    var input = json.map(str(t, "input_json"));
    var result = new LinkedHashMap<>(input);
    result.putAll(
        Json.object(
            "id",
            taskId,
            "title",
            str(t, "title"),
            "status",
            str(t, "status"),
            "mode",
            str(t, "mode"),
            "createdAt",
            str(t, "created_at"),
            "updatedAt",
            str(t, "updated_at"),
            "planRevision",
            integer(t, "plan_revision"),
            "draftRevision",
            integer(t, "draft_revision"),
            "reviewRound",
            integer(t, "review_round"),
            "modelCalls",
            integer(t, "model_calls"),
            "maxModelCalls",
            integer(t, "max_model_calls"),
            "usedTokens",
            number(t, "used_tokens"),
            "reservedTokens",
            number(t, "reserved_tokens"),
            "maxTokens",
            number(t, "max_tokens"),
            "error",
            t.get("error"),
            "plan",
            artifactItems(taskId, "PLAN", integer(t, "plan_revision")),
            "sections",
            artifactItems(taskId, "DRAFT", integer(t, "draft_revision")),
            "report",
            latestReport(taskId)));
    result.put(
        "steps",
        rows(
                "SELECT s.*,e.role FROM steps s JOIN executions e ON e.id=s.execution_id WHERE s.task_id=? ORDER BY s.id",
                taskId)
            .stream()
            .map(
                s ->
                    Json.object(
                        "id",
                        number(s, "id"),
                        "executionId",
                        str(s, "execution_id"),
                        "role",
                        str(s, "role"),
                        "kind",
                        str(s, "kind"),
                        "name",
                        str(s, "name"),
                        "status",
                        str(s, "status"),
                        "createdAt",
                        str(s, "created_at"),
                        "input",
                        "MODEL".equals(str(s, "kind"))
                            ? Json.object("estimatedTokens", integer(s, "estimated_tokens"))
                            : json.read(str(s, "input_json")),
                        "output",
                        s.get("output_json") == null ? null : json.read(str(s, "output_json")),
                        "estimatedTokens",
                        integer(s, "estimated_tokens"),
                        "inputTokens",
                        s.get("input_tokens"),
                        "outputTokens",
                        s.get("output_tokens"),
                        "usageStatus",
                        str(s, "usage_status")))
            .toList());
    result.put(
        "executions",
        rows("SELECT * FROM executions WHERE task_id=? ORDER BY created_at", taskId).stream()
            .map(
                e ->
                    Json.object(
                        "id",
                        str(e, "id"),
                        "role",
                        str(e, "role"),
                        "status",
                        str(e, "status"),
                        "draftRevision",
                        integer(e, "draft_revision"),
                        "coverage",
                        json.read(str(e, "coverage_json"))))
            .toList());
    result.put(
        "versions",
        rows("SELECT * FROM artifacts WHERE task_id=? AND kind='DRAFT' ORDER BY revision", taskId)
            .stream()
            .map(
                a ->
                    Json.object(
                        "revision",
                        integer(a, "revision"),
                        "createdAt",
                        str(a, "created_at"),
                        "hash",
                        str(a, "hash"),
                        "sections",
                        json.read(str(a, "value_json"))))
            .toList());
    result.put(
        "checkpoints",
        rows("SELECT * FROM checkpoints WHERE task_id=? ORDER BY id", taskId).stream()
            .map(
                c ->
                    Json.object(
                        "executionId",
                        str(c, "execution_id"),
                        "createdAt",
                        str(c, "created_at"),
                        "estimatedTokens",
                        integer(c, "estimated_tokens"),
                        "compacted",
                        c.get("compacted"),
                        "state",
                        json.read(str(c, "state_json"))))
            .toList());
    var reviewer =
        one(
            "SELECT coverage_json FROM executions WHERE task_id=? AND role='REVIEWER' ORDER BY created_at DESC LIMIT 1",
            taskId);
    result.put("coverage", reviewer == null ? Map.of() : json.read(str(reviewer, "coverage_json")));
    return result;
  }
}
