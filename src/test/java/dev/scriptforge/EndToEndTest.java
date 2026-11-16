package dev.scriptforge;

import static dev.scriptforge.store.ForgeStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.scriptforge.api.CreateTask;
import dev.scriptforge.config.ForgeProperties;
import dev.scriptforge.model.DemoModelGateway;
import dev.scriptforge.model.ModelGateway;
import dev.scriptforge.runtime.AgentWorker;
import dev.scriptforge.runtime.ContextAssembler;
import dev.scriptforge.runtime.TaskService;
import dev.scriptforge.store.ForgeStore;
import dev.scriptforge.tools.ToolService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1",
      "scriptforge.scheduler-enabled=false",
      "scriptforge.mode=DEMO"
    })
@AutoConfigureMockMvc
class EndToEndTest {
  @Autowired ForgeStore db;
  @Autowired TaskService tasks;
  @Autowired AgentWorker worker;
  @Autowired ForgeProperties config;
  @Autowired ContextAssembler context;
  @Autowired ToolService tools;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc http;
  @MockitoBean ModelGateway gateway;

  @BeforeEach
  void setup() {
    for (String table :
        List.of(
            "commands",
            "checkpoints",
            "reports",
            "artifacts",
            "steps",
            "executions",
            "tasks",
            "templates")) db.jdbc().update("DELETE FROM " + table);
    reset(gateway);
    when(gateway.complete(any()))
        .thenAnswer(i -> new DemoModelGateway(mapper).complete(i.getArgument(0)));
  }

  static CreateTask input(String request) {
    return new CreateTask(
        request,
        "折叠收纳盒 · 春日整理",
        "围绕家庭分类整理场景，创作自然口播，承接折叠、收起与日常拿取。",
        "zh-CN",
        90,
        List.of(
            new CreateTask.Fact("f1", "收纳盒可折叠，闲置时能收起。", "虚构产品说明"),
            new CreateTask.Fact("f2", "盒体带两侧提手，方便双手搬动。", "虚构产品说明"),
            new CreateTask.Fact("f3", "适合分类收纳衣物与日常杂物。", "虚构产品说明")),
        List.of("不编造价格促销和承重数据", "不使用绝对化承诺"),
        40,
        400000L,
        null);
  }

  String create() {
    return tasks.create(input(UUID.randomUUID().toString())).get("id").toString();
  }

  void finish(String id) {
    for (int i = 0;
        i < 100
            && Set.of("QUEUED", "RUNNING", "REVIEWING").contains(str(db.task(id, false), "status"));
        i++) assertThat(worker.runOne()).isTrue();
  }

  @Test
  void writerReviewerRevisionAndDeliveryRunThroughRealTools() throws Exception {
    String id = create();
    finish(id);
    var t = db.task(id, false);
    assertThat(str(t, "status")).as("task error: " + str(t, "error")).isEqualTo("DEMO_READY");
    assertThat(integer(t, "draft_revision")).isEqualTo(4);
    assertThat(integer(t, "review_round")).isEqualTo(2);
    assertThat(number(t, "reserved_tokens")).isZero();
    assertThat(number(t, "used_tokens")).isPositive();
    assertThat(db.rows("SELECT * FROM reports WHERE task_id=?", id)).hasSize(2);
    assertThat(db.latestReport(id))
        .containsEntry("verdict", "PASS")
        .containsEntry("draftRevision", 4)
        .containsEntry("coverageStatus", "FULL");
    var before = db.artifactItems(id, "DRAFT", 3);
    var after = db.artifactItems(id, "DRAFT", 4);
    assertThat(after.get(0)).isEqualTo(before.get(0));
    assertThat(after.get(2)).isEqualTo(before.get(2));
    assertThat(before.get(1).get("text").toString()).contains("承重100公斤");
    assertThat(after.get(1).get("text").toString()).doesNotContain("承重100公斤");
    assertThat(
            db.rows(
                "SELECT * FROM executions WHERE role='REVIEWER' AND task_id=? ORDER BY created_at",
                id))
        .extracting(e -> integer(e, "draft_revision"))
        .containsExactly(3, 4);
    assertThat(
            db.rows(
                "SELECT * FROM steps WHERE task_id=? AND status IN ('PREPARED','WAITING','IN_FLIGHT')",
                id))
        .isEmpty();
    http.perform(get("/api/tasks/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEMO_READY"))
        .andExpect(jsonPath("$.versions.length()").value(4));
    http.perform(get("/api/tasks/" + id + "/export"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("固定虚构演示样例")));
  }

  @Test
  void createAndCancelCommandsAreIdempotent() {
    var request = input("same-request");
    String id = tasks.create(request).get("id").toString();
    assertThat(tasks.create(request).get("id")).isEqualTo(id);
    assertThat(db.rows("SELECT * FROM tasks")).hasSize(1);
    tasks.command(id, "cancel-1", "CANCEL");
    tasks.command(id, "cancel-1", "CANCEL");
    assertThat(db.rows("SELECT * FROM commands")).hasSize(1);
    assertThat(worker.runOne()).isFalse();
    assertThat(str(db.task(id, false), "status")).isEqualTo("CANCELLED");
  }

  @Test
  void acceptedToolIntentReplaysWithoutRepeatingModelCall() {
    String id = create();
    worker.runOne();
    assertThat(integer(db.task(id, false), "plan_revision")).isZero();
    assertThat(db.rows("SELECT * FROM steps WHERE kind='TOOL' AND status='PREPARED'")).hasSize(1);
    var restarted = new AgentWorker(db, config, gateway, context, tools);
    restarted.runOne();
    assertThat(integer(db.task(id, false), "plan_revision")).isEqualTo(1);
    verify(gateway, times(1)).complete(any());
    assertThat(db.rows("SELECT * FROM artifacts WHERE kind='PLAN'")).hasSize(1);
  }

  @Test
  void lateModelResultCannotWriteAfterCancellationButUsageIsAccounted() {
    String id = create();
    doAnswer(
            i -> {
              var reply = new DemoModelGateway(mapper).complete(i.getArgument(0));
              tasks.command(id, "during-http", "CANCEL");
              return reply;
            })
        .when(gateway)
        .complete(any());
    worker.runOne();
    assertThat(str(db.task(id, false), "status")).isEqualTo("CANCELLED");
    assertThat(db.rows("SELECT * FROM artifacts")).isEmpty();
    assertThat(db.rows("SELECT * FROM steps WHERE kind='TOOL'")).isEmpty();
    assertThat(db.rows("SELECT * FROM steps").getFirst())
        .containsEntry("status", "DISCARDED")
        .containsEntry("usage_status", "KNOWN");
    assertThat(number(db.task(id, false), "used_tokens")).isPositive();
    assertThat(number(db.task(id, false), "reserved_tokens")).isZero();
  }

  @Test
  void unknownModelFailurePausesAndChargesReservationWithoutAutomaticRetry() {
    String id = create();
    doThrow(new IllegalStateException("simulated connection loss")).when(gateway).complete(any());
    worker.runOne();
    assertThat(str(db.task(id, false), "status")).isEqualTo("PAUSED");
    assertThat(worker.runOne()).isFalse();
    assertThat(number(db.task(id, false), "used_tokens")).isPositive();
    assertThat(number(db.task(id, false), "reserved_tokens")).isZero();
    assertThat(db.rows("SELECT * FROM steps").getFirst()).containsEntry("usage_status", "UNKNOWN");
    verify(gateway, times(1)).complete(any());
  }

  @Test
  void requestValidationAndCrossOriginProtection() throws Exception {
    http.perform(post("/api/tasks").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/api/tasks")
                .header("Origin", "https://untrusted.example")
                .contentType("application/json")
                .content(mapper.writeValueAsString(input("cross-origin"))))
        .andExpect(status().isForbidden());
    http.perform(get("/api/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("DEMO"));
    http.perform(get("/api/tasks").header("Host", "attacker.example"))
        .andExpect(status().isForbidden());
  }

  @Test
  void templatesAreFrozenPerTaskAndRevisioned() throws Exception {
    String first =
        http.perform(
                post("/api/templates")
                    .contentType("application/json")
                    .content("{\"name\":\"口播\",\"instructions\":\"先场景后事实\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var base = input("template-freeze");
    var request =
        new CreateTask(
            base.requestId(),
            base.title(),
            base.brief(),
            base.language(),
            base.targetSeconds(),
            base.facts(),
            base.constraints(),
            40,
            400000L,
            mapper.readTree(first).path("id").asText());
    String id = tasks.create(request).get("id").toString();
    http.perform(
            post("/api/templates")
                .contentType("application/json")
                .content("{\"name\":\"口播\",\"instructions\":\"全新模板\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    assertThat(
            db.json()
                .tree(str(db.task(id, false), "input_json"))
                .path("template")
                .path("instructions")
                .asText())
        .isEqualTo("先场景后事实");
  }
}
