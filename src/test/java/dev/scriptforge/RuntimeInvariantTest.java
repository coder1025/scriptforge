package dev.scriptforge;

import static dev.scriptforge.store.ForgeStore.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.scriptforge.api.CreateTask;
import dev.scriptforge.domain.ForgeException;
import dev.scriptforge.domain.Json;
import dev.scriptforge.runtime.AgentWorker;
import dev.scriptforge.runtime.TaskService;
import dev.scriptforge.store.ForgeStore;
import dev.scriptforge.tools.ToolService;
import dev.scriptforge.tools.ToolValidator;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:invariants;DB_CLOSE_DELAY=-1",
      "scriptforge.scheduler-enabled=false",
      "scriptforge.mode=DEMO"
    })
class RuntimeInvariantTest {
  @Autowired TaskService tasks;
  @Autowired AgentWorker worker;
  @Autowired ForgeStore db;
  @Autowired ToolService tools;
  @Autowired ToolValidator validator;

  @BeforeEach
  void clean() {
    for (String table :
        List.of(
            "reports",
            "checkpoints",
            "commands",
            "steps",
            "artifacts",
            "executions",
            "tasks",
            "templates")) db.jdbc().update("DELETE FROM " + table);
  }

  @Test
  void roleBoundaryAndSchemaRejectMutationsAndExtraFieldsBeforeStorage() {
    assertThrows(
        ForgeException.class,
        () ->
            validator.validate(
                "WRITER", "read_context", "{\"kind\":\"facts\",\"ids\":[]} {\"kind\":\"draft\"}"));
    assertThrows(
        ForgeException.class,
        () ->
            validator.validate(
                "WRITER", "read_context", "{\"kind\":\"facts\",\"kind\":\"draft\",\"ids\":[]}"));
    assertEquals(
        "FORBIDDEN",
        assertThrows(
                ForgeException.class, () -> validator.validate("REVIEWER", "patch_script", "{}"))
            .code());
    assertEquals(
        "INVALID_ARGUMENT",
        assertThrows(
                ForgeException.class,
                () ->
                    validator.validate(
                        "WRITER",
                        "read_context",
                        "{\"kind\":\"facts\",\"ids\":[],\"ownerId\":\"another-user\"}"))
            .code());
    assertThrows(
        ForgeException.class,
        () -> validator.validate("WRITER", "evaluate_candidate", "{\"draftRevision\":1.5}"));
    assertThrows(
        ForgeException.class,
        () ->
            validator.validate(
                "REVIEWER",
                "submit_review",
                "{\"draftRevision\":1,\"verdict\":\"SKIP\",\"issues\":[],\"summary\":\"\"}"));
    assertEquals(0L, db.jdbc().queryForObject("SELECT COUNT(*) FROM artifacts", Long.class));
  }

  @Test
  void reviewerCannotPassWithoutActualCoverageOrInventedEvidence() {
    String id = create();
    untilStatus(id, "REVIEWING");
    String reviewer =
        str(db.one("SELECT * FROM executions WHERE task_id=? AND role='REVIEWER'", id), "id");
    var premature = reportArgs(3, List.of());
    assertEquals(
        "INCOMPLETE_COVERAGE",
        assertThrows(ForgeException.class, () -> execute(id, reviewer, "submit_review", premature))
            .code());
    assertEquals(0L, db.jdbc().queryForObject("SELECT COUNT(*) FROM reports", Long.class));
    execute(
        id,
        reviewer,
        "inspect_sections",
        Map.of(
            "sectionIds",
            List.of("s1", "s2", "s3"),
            "checks",
            List.of("FACTS", "REPETITION", "CONTINUITY")));
    var fake =
        Map.of(
            "id",
            "invented",
            "sectionId",
            "s2",
            "type",
            "FACTS",
            "severity",
            "BLOCKER",
            "quote",
            "this text does not exist",
            "evidence",
            "claim",
            "repair",
            "fix");
    assertEquals(
        "INVALID_EVIDENCE",
        assertThrows(
                ForgeException.class,
                () -> execute(id, reviewer, "submit_review", reportArgs(3, List.of(fake))))
            .code());
    assertEquals(0L, db.jdbc().queryForObject("SELECT COUNT(*) FROM reports", Long.class));
  }

  @Test
  void fullCoverageCannotOverrideDeterministicBlockingClaim() {
    String id = create();
    untilStatus(id, "REVIEWING");
    String reviewer =
        str(db.one("SELECT * FROM executions WHERE task_id=? AND role='REVIEWER'", id), "id");
    execute(
        id,
        reviewer,
        "inspect_sections",
        Map.of(
            "sectionIds",
            List.of("s1", "s2", "s3"),
            "checks",
            List.of("FACTS", "REPETITION", "CONTINUITY")));
    execute(id, reviewer, "submit_review", reportArgs(3, List.of()));
    var report = db.latestReport(id);
    assertEquals("REVISE", report.get("verdict"));
    assertEquals(false, report.get("hostChecksPassed"));
    assertEquals("RUNNING", db.detail(id).get("status"));
    assertTrue(report.get("issues").toString().contains("100公斤"));
  }

  @Test
  void frozenReviewRejectsNewDraftAndDeliveryRejectsOldReport() {
    String id = create();
    untilStatus(id, "REVIEWING");
    var reviewer = db.one("SELECT * FROM executions WHERE task_id=? AND role='REVIEWER'", id);
    List<Map<String, Object>> old = db.artifactItems(id, "DRAFT", 3);
    db.artifact(id, "DRAFT", 4, 1, old);
    db.jdbc().update("UPDATE tasks SET draft_revision=4 WHERE id=?", id);
    var frozen = db.state(db.task(id, false), reviewer);
    assertEquals(3, frozen.get("draftRevision"));
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(
                ForgeException.class,
                () -> execute(id, str(reviewer, "id"), "submit_review", reportArgs(3, List.of())))
            .code());
    String writer =
        str(db.one("SELECT * FROM executions WHERE task_id=? AND role='WRITER'", id), "id");
    assertEquals(
        "STALE_REPORT",
        assertThrows(
                ForgeException.class,
                () ->
                    execute(
                        id,
                        writer,
                        "finish_run",
                        Map.of(
                            "outcome",
                            "SUBMIT",
                            "draftRevision",
                            4,
                            "reportId",
                            "absent-report",
                            "reason",
                            "attempted bypass")))
            .code());
  }

  @Test
  void oversizedPinnedContextPausesBeforeAnyModelCall() {
    String id = create();
    var input = db.json().map(str(db.task(id, false), "input_json"));
    var facts = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 25; i++)
      facts.add(Map.of("id", "f" + i, "text", "重要原始事实".repeat(220), "source", "source"));
    input.put("facts", facts);
    db.jdbc().update("UPDATE tasks SET input_json=? WHERE id=?", db.json().write(input), id);
    assertTrue(worker.runOne());
    assertEquals("PAUSED", db.detail(id).get("status"));
    assertEquals(0, db.detail(id).get("modelCalls"));
    assertTrue(db.detail(id).get("error").toString().contains("CONTEXT_TOO_LARGE"));
    assertFalse(worker.runOne());
  }

  private String create() {
    var facts =
        List.of(
            new CreateTask.Fact("f1", "盒体采用可折叠设计，不用时可以折叠收起。", "虚构说明"),
            new CreateTask.Fact("f2", "两侧有提手，便于拿取和移动。", "虚构说明"),
            new CreateTask.Fact("f3", "适合分类收纳衣物与日常杂物。", "虚构说明"));
    return tasks
        .create(
            new CreateTask(
                UUID.randomUUID().toString(),
                "折叠收纳盒",
                "制作一个仅用于测试的折叠收纳盒口播样例。",
                "zh-CN",
                90,
                facts,
                List.of("不得虚构承重"),
                40,
                400000L,
                null))
        .get("id")
        .toString();
  }

  private void untilStatus(String id, String status) {
    for (int i = 0; i < 30 && !status.equals(db.detail(id).get("status")); i++)
      assertTrue(worker.runOne());
    assertEquals(status, db.detail(id).get("status"), () -> db.detail(id).toString());
  }

  private Map<String, Object> reportArgs(int revision, List<?> issues) {
    return Map.of(
        "draftRevision",
        revision,
        "verdict",
        "PASS",
        "issues",
        issues,
        "summary",
        "testing host gate");
  }

  private ToolService.Result execute(String taskId, String executionId, String name, Object args) {
    return db.tx(
        () ->
            tools.execute(
                db.task(taskId, true),
                db.execution(executionId, true),
                Json.object("id", -1L, "name", name, "input_json", db.json().write(args))));
  }
}
