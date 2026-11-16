package dev.scriptforge.runtime;

import static dev.scriptforge.store.ForgeStore.*;

import dev.scriptforge.config.ForgeProperties;
import dev.scriptforge.domain.ForgeException;
import dev.scriptforge.domain.Json;
import dev.scriptforge.model.ModelGateway;
import dev.scriptforge.store.ForgeStore;
import dev.scriptforge.tools.ToolCatalog;
import dev.scriptforge.tools.ToolService;
import java.time.OffsetDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded turn per lease. Model I/O never holds a database transaction. */
@Component
public class AgentWorker {
  private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);
  private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "REVIEWING");
  private final ForgeStore db;
  private final ForgeProperties config;
  private final ModelGateway model;
  private final ContextAssembler assembler;
  private final ToolService tools;
  private final String workerId = "worker-" + id();

  public AgentWorker(
      ForgeStore db,
      ForgeProperties config,
      ModelGateway model,
      ContextAssembler assembler,
      ToolService tools) {
    this.db = db;
    this.config = config;
    this.model = model;
    this.assembler = assembler;
    this.tools = tools;
  }

  @Scheduled(fixedDelay = 350, initialDelay = 1000)
  public void scheduled() {
    if (config.schedulerEnabled() && !config.readOnly())
      try {
        runOne();
      } catch (Exception ex) {
        log.error("Worker cycle failed: {}", ex.getClass().getSimpleName());
      }
  }

  public boolean runOne() {
    var lease = claim();
    if (lease == null) return false;
    String execId = str(lease, "id"), taskId = str(lease, "task_id");
    long epoch = number(lease, "lease_epoch");
    try {
      var pending =
          db.one(
              "SELECT * FROM steps WHERE execution_id=? AND kind='TOOL' AND status='PREPARED' ORDER BY id LIMIT 1",
              execId);
      if (pending != null) {
        executeTool(taskId, execId, epoch, pending);
        return true;
      }
      var abandoned =
          db.one(
              "SELECT * FROM steps WHERE execution_id=? AND kind='MODEL' AND status='IN_FLIGHT' ORDER BY id LIMIT 1",
              execId);
      if (abandoned != null) {
        db.tx(
            () -> {
              var t = db.task(taskId, true);
              var e = db.execution(execId, true);
              if (valid(t, e, epoch)) {
                settle(abandoned, null, null);
                db.completeStep(
                    number(abandoned, "id"),
                    "UNKNOWN",
                    Json.object(
                        "error",
                        "Worker stopped while model result was uncertain; reserved usage charged conservatively"));
                pause(
                    taskId,
                    execId,
                    "UNKNOWN_MODEL_RESULT: the previous request may have been billed; explicit resume starts a new request");
              }
              return null;
            });
        return true;
      }
      var prepared = prepare(taskId, execId, epoch);
      if (prepared == null) return true;
      try {
        var reply = model.complete(prepared.request());
        accept(taskId, execId, epoch, prepared.stepId(), reply);
      } catch (Exception ex) {
        db.tx(
            () -> {
              var t = db.task(taskId, true);
              var e = db.execution(execId, true);
              var step = db.one("SELECT * FROM steps WHERE id=?", prepared.stepId());
              if ("RESERVED".equals(str(step, "usage_status"))) settle(step, null, null);
              if ("IN_FLIGHT".equals(str(step, "status")))
                db.completeStep(
                    prepared.stepId(),
                    "UNKNOWN",
                    Json.object(
                        "error",
                        "Model result unavailable or invalid; no tool operation accepted",
                        "exceptionType",
                        ex.getClass().getSimpleName()));
              if (valid(t, e, epoch))
                pause(
                    taskId,
                    execId,
                    "MODEL_CALL_UNCERTAIN: no automatic retry. Verify model configuration and explicitly resume.");
              return null;
            });
        log.warn("Model request paused task={} type={}", taskId, ex.getClass().getSimpleName());
      }
      return true;
    } catch (ForgeException ex) {
      db.tx(
          () -> {
            var t = db.task(taskId, true);
            var e = db.execution(execId, true);
            if (valid(t, e, epoch)) pause(taskId, execId, ex.code() + ": " + ex.getMessage());
            return null;
          });
      return true;
    } catch (RuntimeException ex) {
      // Preserve a prepared intent on infrastructure failure. Do not retry it in a tight background
      // loop.
      db.tx(
          () -> {
            var t = db.task(taskId, true);
            var e = db.execution(execId, true);
            if (valid(t, e, epoch))
              pause(
                  taskId,
                  execId,
                  "EXECUTION_ERROR: "
                      + ex.getClass().getSimpleName()
                      + "; inspect the saved operation before explicit resume");
            return null;
          });
      log.warn("Execution paused task={} type={}", taskId, ex.getClass().getSimpleName());
      return true;
    } finally {
      db.jdbc()
          .update(
              "UPDATE executions SET lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_epoch=? AND lease_owner=?",
              execId,
              epoch,
              workerId);
    }
  }

  private Map<String, Object> claim() {
    var candidates =
        db.rows(
            "SELECT e.id,e.task_id FROM executions e JOIN tasks t ON t.id=e.task_id WHERE e.status='RUNNABLE' AND t.status IN ('QUEUED','RUNNING','REVIEWING') AND t.mode=? AND (e.lease_until IS NULL OR e.lease_until<?) ORDER BY e.created_at LIMIT 10",
            config.mode(),
            now());
    for (var candidate : candidates) {
      var claimed =
          db.tx(
              () -> {
                var t = db.task(str(candidate, "task_id"), true);
                var e = db.execution(str(candidate, "id"), true);
                Object until = e.get("lease_until");
                if (!ACTIVE.contains(str(t, "status"))
                    || !"RUNNABLE".equals(str(e, "status"))
                    || (until instanceof OffsetDateTime date && date.isAfter(now()))) return null;
                db.jdbc()
                    .update(
                        "UPDATE executions SET lease_owner=?,lease_epoch=lease_epoch+1,lease_until=? WHERE id=?",
                        workerId,
                        now().plusSeconds(config.modelTimeoutSeconds() + 60L),
                        str(e, "id"));
                if ("QUEUED".equals(str(t, "status")))
                  db.jdbc()
                      .update(
                          "UPDATE tasks SET status='RUNNING',updated_at=? WHERE id=?",
                          now(),
                          str(t, "id"));
                return db.execution(str(e, "id"), false);
              });
      if (claimed != null) return claimed;
    }
    return null;
  }

  private boolean valid(Map<String, Object> task, Map<String, Object> execution, long epoch) {
    return ACTIVE.contains(str(task, "status"))
        && "RUNNABLE".equals(str(execution, "status"))
        && epoch == number(execution, "lease_epoch")
        && workerId.equals(str(execution, "lease_owner"))
        && execution.get("lease_until") instanceof OffsetDateTime until
        && until.isAfter(now());
  }

  private record Prepared(long stepId, ModelGateway.ModelRequest request) {}

  private Prepared prepare(String taskId, String execId, long epoch) {
    return db.tx(
        () -> {
          var t = db.task(taskId, true);
          var e = db.execution(execId, true);
          if (!valid(t, e, epoch)) return null;
          if (integer(t, "model_calls") >= integer(t, "max_model_calls")) {
            pause(taskId, execId, "BUDGET_EXHAUSTED: model call limit reached");
            return null;
          }
          var state = db.state(t, e);
          ContextAssembler.Assembly context;
          try {
            context =
                assembler.assemble(
                    str(e, "role"), state, db.recentMessages(execId), config.inputBudget());
          } catch (ContextAssembler.ContextOverflowException | IllegalArgumentException ex) {
            pause(
                taskId,
                execId,
                "CONTEXT_TOO_LARGE: reduce task scope; required original evidence was preserved");
            return null;
          }
          long reservation = (long) context.estimatedTokens() + config.maxOutputTokens();
          if (number(t, "used_tokens") + number(t, "reserved_tokens") + reservation
              > number(t, "max_tokens")) {
            pause(
                taskId,
                execId,
                "BUDGET_EXHAUSTED: insufficient budget for the next complete request");
            return null;
          }
          var request =
              new ModelGateway.ModelRequest(
                  str(e, "role"),
                  context.messages(),
                  ToolCatalog.forRole(str(e, "role")),
                  config.maxOutputTokens(),
                  state);
          long step =
              db.step(
                  taskId,
                  execId,
                  "MODEL",
                  "model.complete",
                  "IN_FLIGHT",
                  null,
                  null,
                  Json.object(
                      "messages",
                      context.messages(),
                      "role",
                      str(e, "role"),
                      "model",
                      config.modelName(),
                      "maxOutputTokens",
                      config.maxOutputTokens()),
                  reservation,
                  context.estimatedTokens(),
                  epoch);
          db.jdbc()
              .update(
                  "UPDATE tasks SET model_calls=model_calls+1,reserved_tokens=reserved_tokens+?,updated_at=? WHERE id=?",
                  reservation,
                  now(),
                  taskId);
          db.jdbc()
              .update(
                  "INSERT INTO checkpoints(task_id,execution_id,plan_revision,draft_revision,estimated_tokens,compacted,state_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                  taskId,
                  execId,
                  ((Number) state.get("planRevision")).intValue(),
                  ((Number) state.get("draftRevision")).intValue(),
                  context.estimatedTokens(),
                  context.compacted(),
                  db.json().write(context.checkpoint()),
                  now());
          return new Prepared(step, request);
        });
  }

  private void accept(
      String taskId, String execId, long epoch, long stepId, ModelGateway.ModelReply reply) {
    db.tx(
        () -> {
          var t = db.task(taskId, true);
          var e = db.execution(execId, true);
          var step = db.one("SELECT * FROM steps WHERE id=?", stepId);
          if (!"IN_FLIGHT".equals(str(step, "status"))) return null;
          settle(step, reply.inputTokens(), reply.outputTokens());
          if (!valid(t, e, epoch)) {
            db.completeStep(
                stepId,
                "DISCARDED",
                Json.object("reason", "Task or execution changed while model was running"));
            return null;
          }
          if (!str(t, "mode").equals(reply.provenance()))
            throw new ForgeException(
                "PROVENANCE_MISMATCH", "Gateway provenance differs from task mode");
          if (reply.toolCalls() == null || reply.toolCalls().size() != 1) {
            db.completeStep(
                stepId,
                "SUCCEEDED",
                Json.object(
                    "role", "assistant", "content", reply.text() == null ? "" : reply.text()));
            pause(
                taskId,
                execId,
                "MODEL_PROTOCOL: expected one tool action; plain text does not finish the task");
            return null;
          }
          var call = reply.toolCalls().getFirst();
          if (call.id() == null
              || call.id().isBlank()
              || call.id().length() > 200
              || call.name() == null
              || call.name().length() > 80)
            throw new ForgeException("MODEL_PROTOCOL", "Invalid tool call identity");
          // Parse before accepting; a truncated JSON response must never be applied as a partial
          // patch.
          var arguments = db.json().tree(call.arguments());
          var assistant =
              Json.object(
                  "role",
                  "assistant",
                  "content",
                  reply.text() == null ? "" : reply.text(),
                  "tool_calls",
                  List.of(
                      Json.object(
                          "id",
                          call.id(),
                          "type",
                          "function",
                          "function",
                          Json.object("name", call.name(), "arguments", call.arguments()))));
          db.completeStep(stepId, "SUCCEEDED", assistant);
          db.step(
              taskId,
              execId,
              "TOOL",
              call.name(),
              "PREPARED",
              stepId,
              call.id(),
              arguments,
              0,
              0,
              epoch);
          return null;
        });
  }

  /** Exactly-once local accounting. Unknown usage consumes reservation, never reports zero cost. */
  private void settle(Map<String, Object> step, Long input, Long output) {
    if (!"RESERVED".equals(str(step, "usage_status"))) return;
    boolean known = input != null && output != null && input >= 0 && output >= 0;
    long charge = known ? Math.addExact(input, output) : number(step, "reserved_tokens");
    db.jdbc()
        .update(
            "UPDATE steps SET usage_status=?,input_tokens=?,output_tokens=? WHERE id=? AND usage_status='RESERVED'",
            known ? "KNOWN" : "UNKNOWN",
            input,
            output,
            number(step, "id"));
    db.jdbc()
        .update(
            "UPDATE tasks SET reserved_tokens=reserved_tokens-?,used_tokens=used_tokens+?,updated_at=? WHERE id=?",
            number(step, "reserved_tokens"),
            charge,
            now(),
            str(step, "task_id"));
  }

  private void executeTool(String taskId, String execId, long epoch, Map<String, Object> pending) {
    try {
      db.tx(
          () -> {
            var t = db.task(taskId, true);
            var e = db.execution(execId, true);
            if (!valid(t, e, epoch)) return null;
            var step = db.one("SELECT * FROM steps WHERE id=? FOR UPDATE", number(pending, "id"));
            if (!"PREPARED".equals(str(step, "status"))) return null;
            var result = tools.execute(t, e, step);
            db.completeStep(
                number(step, "id"),
                result.waiting() ? "WAITING" : "SUCCEEDED",
                result.waiting()
                    ? result.data()
                    : ToolService.envelope(number(step, "id"), result.data()));
            return null;
          });
    } catch (ForgeException ex) {
      db.tx(
          () -> {
            var t = db.task(taskId, true);
            var e = db.execution(execId, true);
            if (!valid(t, e, epoch)) return null;
            db.completeStep(
                number(pending, "id"),
                "FAILED",
                Json.object(
                    "operationId",
                    number(pending, "id"),
                    "status",
                    "ERROR",
                    "error",
                    Json.object("code", ex.code(), "message", ex.getMessage()),
                    "currentVersions",
                    Json.object(
                        "planRevision",
                        integer(t, "plan_revision"),
                        "draftRevision",
                        integer(t, "draft_revision"))));
            var recent =
                db.rows(
                    "SELECT status FROM steps WHERE execution_id=? AND kind='TOOL' ORDER BY id DESC LIMIT 3",
                    execId);
            if ("REVIEW_LIMIT".equals(ex.code())
                || (recent.size() == 3
                    && recent.stream().allMatch(s -> "FAILED".equals(str(s, "status")))))
              pause(
                  taskId,
                  execId,
                  "TOOL_NO_PROGRESS: " + ex.code() + "; inspect trace before retrying");
            return null;
          });
    }
  }

  private void pause(String taskId, String execId, String reason) {
    db.jdbc()
        .update(
            "UPDATE tasks SET status='PAUSED',error=?,updated_at=? WHERE id=?",
            reason,
            now(),
            taskId);
    db.jdbc().update("UPDATE executions SET status='PAUSED' WHERE id=?", execId);
  }
}
