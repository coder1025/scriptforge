package dev.scriptforge.tools;

import static dev.scriptforge.domain.ForgeException.require;
import static dev.scriptforge.store.ForgeStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.scriptforge.domain.Json;
import dev.scriptforge.store.ForgeStore;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ToolService {
  public record Result(Object data, boolean waiting) {
    static Result done(Object data) {
      return new Result(data, false);
    }
  }

  private final ForgeStore db;
  private final ToolValidator validator;
  private final QualityChecks checks;

  public ToolService(ForgeStore db, ToolValidator validator, QualityChecks checks) {
    this.db = db;
    this.validator = validator;
    this.checks = checks;
  }

  public Result execute(
      Map<String, Object> task, Map<String, Object> execution, Map<String, Object> step) {
    String name = str(step, "name"), role = str(execution, "role");
    var args = validator.validate(role, name, str(step, "input_json"));
    var state = db.state(task, execution);
    return switch (name) {
      case "read_context" -> Result.done(read(state, args));
      case "search_history" -> Result.done(history(str(task, "id"), args));
      case "save_plan" -> Result.done(plan(task, args));
      case "patch_script" -> Result.done(patch(task, args));
      case "evaluate_candidate" -> review(task, execution, step, args);
      case "inspect_sections" -> Result.done(inspect(execution, state, args));
      case "submit_review" -> Result.done(submit(task, execution, state, args));
      case "finish_run" -> Result.done(finish(task, execution, args));
      default -> throw new IllegalStateException("Catalog and executor differ");
    };
  }

  private Object read(Map<String, Object> state, JsonNode args) {
    String kind = args.path("kind").asText();
    Object result =
        switch (kind) {
          case "facts" -> state.get("facts");
          case "plan" -> state.get("plan");
          case "draft" -> state.get("sections");
          case "report" -> state.get("report");
          case "section" ->
              items(state, "sections").stream()
                  .filter(s -> strings(args.path("ids")).contains(s.get("sectionId").toString()))
                  .toList();
          case "ledger" ->
              Json.object(
                  "facts",
                  state.get("facts"),
                  "plan",
                  state.get("plan"),
                  "sectionDirectory",
                  items(state, "sections").stream()
                      .map(
                          s ->
                              Json.object(
                                  "sectionId",
                                  s.get("sectionId"),
                                  "factIds",
                                  s.get("factIds"),
                                  "hash",
                                  db.json().hash(s)))
                      .toList(),
                  "openIssues",
                  state.get("report"));
          default -> Map.of();
        };
    require(
        db.json().write(result).length() <= 16000,
        "RESULT_TOO_LARGE",
        "Request individual section IDs; full original remains in storage");
    return Json.object(
        "kind",
        kind,
        "planRevision",
        state.get("planRevision"),
        "draftRevision",
        state.get("draftRevision"),
        "complete",
        true,
        "value",
        result);
  }

  private Object history(String taskId, JsonNode args) {
    String query = args.path("query").asText().strip();
    require(
        !query.isEmpty() && query.length() <= 200,
        "INVALID_ARGUMENT",
        "Search query must be 1..200 characters");
    int limit = args.path("limit").asInt();
    List<Map<String, Object>> matches = new ArrayList<>();
    var candidates =
        db.rows(
            "SELECT a.*,t.title,t.mode FROM artifacts a JOIN tasks t ON t.id=a.task_id WHERE a.kind='DRAFT' AND a.revision=t.draft_revision AND t.status IN ('READY','DEMO_READY') AND t.mode=(SELECT mode FROM tasks WHERE id=?) AND t.id<>? ORDER BY a.created_at DESC LIMIT 100",
            taskId,
            taskId);
    for (var a : candidates) {
      for (var s : db.artifactItems(str(a, "task_id"), "DRAFT", integer(a, "revision"))) {
        String text = s.get("text").toString();
        int found = text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (found >= 0)
          matches.add(
              Json.object(
                  "taskId",
                  str(a, "task_id"),
                  "title",
                  str(a, "title"),
                  "provenance",
                  str(a, "mode"),
                  "revision",
                  integer(a, "revision"),
                  "sectionId",
                  s.get("sectionId"),
                  "excerpt",
                  text.substring(
                      Math.max(0, found - 100),
                      Math.min(text.length(), found + query.length() + 200))));
        if (matches.size() >= limit) break;
      }
      if (matches.size() >= limit) break;
    }
    return Json.object(
        "matches",
        matches,
        "scope",
        "Latest 100 completed local single-user tasks of the same DEMO/MODEL mode; lexical search only",
        "completeCorpus",
        false);
  }

  private Object plan(Map<String, Object> task, JsonNode args) {
    String taskId = str(task, "id");
    int current = integer(task, "plan_revision");
    require(
        args.path("expectedPlanRevision").asInt() == current,
        "VERSION_CONFLICT",
        "Plan version changed; read current plan");
    require(
        integer(task, "draft_revision") == 0,
        "PLAN_LOCKED",
        "Plan is frozen after drafting begins; create a new task for structural changes");
    var sections = args.path("sections");
    require(
        !sections.isEmpty() && sections.size() <= 24,
        "INVALID_ARGUMENT",
        "Plan must contain 1..24 sections");
    Set<String> validFacts =
        items(db.json().map(str(task, "input_json")), "facts").stream()
            .map(f -> f.get("id").toString())
            .collect(Collectors.toSet());
    Set<String> ids = new LinkedHashSet<>();
    int duration = 0;
    for (var section : sections) {
      String id = section.path("id").asText();
      require(
          id.matches("[A-Za-z0-9_-]{1,40}") && !ids.contains(id),
          "INVALID_ARGUMENT",
          "Section IDs must be unique and URL-safe");
      require(
          ids.containsAll(strings(section.path("dependsOn"))),
          "INVALID_ARGUMENT",
          "Dependencies must reference earlier sections");
      require(
          !strings(section.path("factIds")).isEmpty()
              && validFacts.containsAll(strings(section.path("factIds"))),
          "INVALID_ARGUMENT",
          "Plan must cite known facts");
      require(
          section.path("targetSeconds").asInt() >= 5,
          "INVALID_ARGUMENT",
          "Each section must target at least 5 seconds");
      duration += section.path("targetSeconds").asInt();
      ids.add(id);
    }
    int target = db.json().tree(str(task, "input_json")).path("targetSeconds").asInt();
    require(
        duration == target,
        "INVALID_ARGUMENT",
        "Section targetSeconds must sum to task targetSeconds=" + target);
    int next = current + 1;
    db.artifact(taskId, "PLAN", next, next, sections);
    db.jdbc()
        .update("UPDATE tasks SET plan_revision=?,updated_at=? WHERE id=?", next, now(), taskId);
    return Json.object(
        "planRevision", next, "sectionCount", sections.size(), "targetSeconds", duration);
  }

  private Object patch(Map<String, Object> task, JsonNode args) {
    String taskId = str(task, "id");
    int draft = integer(task, "draft_revision"), planRev = integer(task, "plan_revision");
    require(
        planRev > 0
            && args.path("planRevision").asInt() == planRev
            && args.path("expectedDraftRevision").asInt() == draft,
        "VERSION_CONFLICT",
        "Read the latest plan and draft revisions before editing");
    var plan = db.artifactItems(taskId, "PLAN", planRev);
    Set<String> ids = plan.stream().map(p -> p.get("id").toString()).collect(Collectors.toSet());
    Set<String> factIds =
        items(db.json().map(str(task, "input_json")), "facts").stream()
            .map(f -> f.get("id").toString())
            .collect(Collectors.toSet());
    var sections = new LinkedHashMap<String, Map<String, Object>>();
    for (var s : db.artifactItems(taskId, "DRAFT", draft))
      sections.put(s.get("sectionId").toString(), s);
    Set<String> edited = new LinkedHashSet<>();
    var edits = args.path("edits");
    require(
        !edits.isEmpty() && edits.size() <= 8,
        "INVALID_ARGUMENT",
        "Patch must contain 1..8 sections");
    for (var edit : edits) {
      String id = edit.path("sectionId").asText(), text = edit.path("text").asText();
      require(
          ids.contains(id) && edited.add(id),
          "INVALID_ARGUMENT",
          "Unknown or duplicate section ID");
      require(
          text.strip().length() >= 20 && text.length() <= 6000,
          "INVALID_ARGUMENT",
          "Section text must be 20..6000 characters");
      require(
          !strings(edit.path("factIds")).isEmpty()
              && factIds.containsAll(strings(edit.path("factIds"))),
          "INVALID_ARGUMENT",
          "Each section needs known fact references");
      sections.put(
          id, Json.object("sectionId", id, "text", text, "factIds", strings(edit.path("factIds"))));
    }
    var ordered =
        plan.stream()
            .map(p -> sections.get(p.get("id").toString()))
            .filter(Objects::nonNull)
            .toList();
    var previous = db.artifact(taskId, "DRAFT", draft);
    String hash = db.json().hash(ordered);
    require(
        previous == null || !hash.equals(str(previous, "hash")),
        "NO_CHANGE",
        "Patch does not change the draft");
    db.artifact(taskId, "DRAFT", draft + 1, planRev, ordered);
    db.jdbc()
        .update(
            "UPDATE tasks SET draft_revision=?,updated_at=? WHERE id=?", draft + 1, now(), taskId);
    return Json.object(
        "draftRevision",
        draft + 1,
        "planRevision",
        planRev,
        "hash",
        hash,
        "changedSections",
        edited,
        "invalidated",
        "All previous whole-draft reports; re-review required");
  }

  private Result review(
      Map<String, Object> task,
      Map<String, Object> execution,
      Map<String, Object> step,
      JsonNode args) {
    String taskId = str(task, "id");
    int draft = integer(task, "draft_revision");
    require(
        draft > 0 && args.path("draftRevision").asInt() == draft,
        "VERSION_CONFLICT",
        "Review must bind current draft");
    var plan = db.artifactItems(taskId, "PLAN", integer(task, "plan_revision"));
    var sections = db.artifactItems(taskId, "DRAFT", draft);
    require(
        plan.size() == sections.size(),
        "INCOMPLETE_DRAFT",
        "Finish every planned section before whole-draft review");
    require(
        integer(task, "review_round") < 4,
        "REVIEW_LIMIT",
        "At most four review rounds; preserve draft for human review");
    var last = db.latestReport(taskId);
    require(
        last == null || ((Number) last.get("draftRevision")).intValue() != draft,
        "NO_PROGRESS",
        "This exact version already has a report; revise the draft or submit it");
    String reviewer =
        db.newExecution(
            taskId, "REVIEWER", integer(task, "plan_revision"), draft, number(step, "id"));
    db.jdbc()
        .update("UPDATE executions SET status='WAITING_REVIEW' WHERE id=?", str(execution, "id"));
    db.jdbc()
        .update(
            "UPDATE tasks SET status='REVIEWING',review_round=review_round+1,updated_at=? WHERE id=?",
            now(),
            taskId);
    return new Result(Json.object("reviewerExecutionId", reviewer, "draftRevision", draft), true);
  }

  @SuppressWarnings("unchecked")
  private Object inspect(Map<String, Object> execution, Map<String, Object> state, JsonNode args) {
    List<String> ids = strings(args.path("sectionIds")), requested = strings(args.path("checks"));
    require(
        !ids.isEmpty() && !requested.isEmpty(), "INVALID_ARGUMENT", "Select sections and checks");
    var selected =
        items(state, "sections").stream()
            .filter(s -> ids.contains(s.get("sectionId").toString()))
            .toList();
    require(
        selected.size() == new HashSet<>(ids).size(),
        "NOT_FOUND",
        "A requested section is absent in frozen draft");
    require(
        db.json().write(selected).length() <= 14000,
        "RESULT_TOO_LARGE",
        "Inspect smaller groups; originals remain available");
    var coverage = db.json().map(str(execution, "coverage_json"));
    var sectionChecks =
        new LinkedHashMap<>((Map<String, Object>) coverage.getOrDefault("sectionChecks", Map.of()));
    for (String id : ids) {
      var names = new LinkedHashSet<>((List<String>) sectionChecks.getOrDefault(id, List.of()));
      names.addAll(requested);
      sectionChecks.put(id, new ArrayList<>(names));
    }
    var inspected =
        new LinkedHashSet<>((List<String>) coverage.getOrDefault("inspectedSections", List.of()));
    for (var entry : sectionChecks.entrySet())
      if (((List<String>) entry.getValue())
          .containsAll(List.of("FACTS", "REPETITION", "CONTINUITY"))) inspected.add(entry.getKey());
    var edges =
        new LinkedHashSet<>((List<String>) coverage.getOrDefault("inspectedEdges", List.of()));
    if (requested.contains("CONTINUITY"))
      for (String edge : QualityChecks.requiredEdges(items(state, "plan"))) {
        var ends = edge.split("->");
        if (ids.contains(ends[0]) && ids.contains(ends[1])) edges.add(edge);
      }
    coverage =
        Json.object(
            "inspectedSections",
            inspected,
            "inspectedEdges",
            edges,
            "sectionChecks",
            sectionChecks);
    db.jdbc()
        .update(
            "UPDATE executions SET coverage_json=? WHERE id=?",
            db.json().write(coverage),
            str(execution, "id"));
    var deterministic = checks.inspect(state);
    return Json.object(
        "draftRevision",
        state.get("draftRevision"),
        "sections",
        selected,
        "facts",
        state.get("facts"),
        "coverage",
        coverage,
        "requiredEdges",
        QualityChecks.requiredEdges(items(state, "plan")),
        "deterministic",
        deterministic,
        "semanticReviewRequired",
        true);
  }

  @SuppressWarnings("unchecked")
  private Object submit(
      Map<String, Object> task,
      Map<String, Object> execution,
      Map<String, Object> state,
      JsonNode args) {
    String taskId = str(task, "id");
    int draft = integer(execution, "draft_revision"), plan = integer(execution, "plan_revision");
    require(
        args.path("draftRevision").asInt() == draft
            && integer(task, "draft_revision") == draft
            && integer(task, "plan_revision") == plan,
        "VERSION_CONFLICT",
        "Frozen review no longer matches current draft");
    var coverage = db.json().map(str(execution, "coverage_json"));
    var expected =
        items(state, "plan").stream().map(p -> p.get("id").toString()).collect(Collectors.toSet());
    require(
        new HashSet<>((List<String>) coverage.get("inspectedSections")).containsAll(expected),
        "INCOMPLETE_COVERAGE",
        "Inspect all sections with all mandatory checks before reporting");
    require(
        new HashSet<>((List<String>) coverage.get("inspectedEdges"))
            .containsAll(QualityChecks.requiredEdges(items(state, "plan"))),
        "INCOMPLETE_COVERAGE",
        "Inspect all adjacent and declared dependency pairs together");
    var original =
        items(state, "sections").stream()
            .collect(
                Collectors.toMap(
                    s -> s.get("sectionId").toString(), s -> s.get("text").toString()));
    List<Map<String, Object>> issues = new ArrayList<>();
    Set<String> issueIds = new HashSet<>();
    for (var issue : args.path("issues")) {
      String section = issue.path("sectionId").asText(), quote = issue.path("quote").asText();
      require(
          original.containsKey(section)
              && !quote.isBlank()
              && original.get(section).contains(quote),
          "INVALID_EVIDENCE",
          "Issue quote must occur exactly in the referenced frozen section");
      require(
          issueIds.add(issue.path("id").asText())
              && !issue.path("evidence").asText().isBlank()
              && !issue.path("repair").asText().isBlank(),
          "INVALID_EVIDENCE",
          "Issues require unique IDs, evidence and repair targets");
      issues.add(db.json().map(issue.toString()));
    }
    var deterministic = checks.inspect(state);
    issues.addAll((List<Map<String, Object>>) deterministic.get("issues"));
    boolean hostPass = Boolean.TRUE.equals(deterministic.get("passed"));
    String verdict = args.path("verdict").asText();
    if ("PASS".equals(verdict)
        && (!hostPass || issues.stream().anyMatch(i -> "BLOCKER".equals(i.get("severity")))))
      verdict = "REVISE";
    var artifact = db.artifact(taskId, "DRAFT", draft);
    String reportId = id();
    Map<String, Object> report =
        Json.object(
            "id",
            reportId,
            "executionId",
            str(execution, "id"),
            "draftRevision",
            draft,
            "planRevision",
            plan,
            "draftHash",
            str(artifact, "hash"),
            "rulesVersion",
            QualityChecks.VERSION,
            "verdict",
            verdict,
            "issues",
            issues,
            "summary",
            args.path("summary").asText(),
            "checks",
            deterministic.get("checks"),
            "coverage",
            coverage,
            "coverageStatus",
            "FULL",
            "hostChecksPassed",
            hostPass,
            "provenance",
            str(task, "mode"),
            "semanticReview",
            "Model assessment, not a guarantee",
            "createdAt",
            now().toString());
    db.jdbc()
        .update(
            "INSERT INTO reports(id,task_id,execution_id,plan_revision,draft_revision,draft_hash,value_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
            reportId,
            taskId,
            str(execution, "id"),
            plan,
            draft,
            str(artifact, "hash"),
            db.json().write(report),
            now());
    db.jdbc().update("UPDATE executions SET status='COMPLETE' WHERE id=?", str(execution, "id"));
    var parent = db.one("SELECT * FROM steps WHERE id=?", number(execution, "parent_step_id"));
    require(
        parent != null && "WAITING".equals(str(parent, "status")),
        "CONFLICT",
        "Writer review wait is absent");
    db.completeStep(number(parent, "id"), "SUCCEEDED", envelope(number(parent, "id"), report));
    db.jdbc()
        .update(
            "UPDATE executions SET status='RUNNABLE',lease_owner=NULL,lease_until=NULL WHERE id=? AND status='WAITING_REVIEW'",
            str(parent, "execution_id"));
    db.jdbc().update("UPDATE tasks SET status='RUNNING',updated_at=? WHERE id=?", now(), taskId);
    return report;
  }

  private Object finish(Map<String, Object> task, Map<String, Object> execution, JsonNode args) {
    String outcome = args.path("outcome").asText(), taskId = str(task, "id");
    String status;
    if ("SUBMIT".equals(outcome)) {
      int draft = integer(task, "draft_revision"), plan = integer(task, "plan_revision");
      var row =
          db.one(
              "SELECT * FROM reports WHERE id=? AND task_id=?",
              args.path("reportId").asText(),
              taskId);
      require(
          row != null
              && args.path("draftRevision").asInt() == draft
              && integer(row, "draft_revision") == draft
              && integer(row, "plan_revision") == plan,
          "STALE_REPORT",
          "Delivery requires a report bound to the latest plan and draft");
      var report = db.json().map(str(row, "value_json"));
      var artifact = db.artifact(taskId, "DRAFT", draft);
      require(
          artifact != null
              && str(artifact, "hash").equals(str(row, "draft_hash"))
              && QualityChecks.VERSION.equals(report.get("rulesVersion"))
              && "PASS".equals(report.get("verdict"))
              && Boolean.TRUE.equals(report.get("hostChecksPassed"))
              && "FULL".equals(report.get("coverageStatus")),
          "QUALITY_GATE",
          "Latest report has blocking findings, outdated rules or incomplete coverage");
      status = "DEMO".equals(str(task, "mode")) ? "DEMO_READY" : "READY";
    } else status = outcome;
    db.jdbc()
        .update(
            "UPDATE tasks SET status=?,error=?,updated_at=? WHERE id=?",
            status,
            "SUBMIT".equals(outcome) ? null : args.path("reason").asText(),
            now(),
            taskId);
    db.jdbc().update("UPDATE executions SET status='COMPLETE' WHERE id=?", str(execution, "id"));
    return Json.object(
        "accepted", true, "actualState", status, "draftRevision", integer(task, "draft_revision"));
  }

  public static Map<String, Object> envelope(long stepId, Object value) {
    return Json.object("operationId", stepId, "status", "OK", "data", value);
  }

  public static List<String> strings(JsonNode array) {
    var values = new ArrayList<String>();
    for (var item : array) values.add(item.asText());
    return values;
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> items(Map<String, Object> state, String key) {
    return (List<Map<String, Object>>) state.getOrDefault(key, List.of());
  }
}
