package dev.scriptforge.runtime;

import static dev.scriptforge.domain.ForgeException.require;
import static dev.scriptforge.store.ForgeStore.*;

import dev.scriptforge.api.CreateTask;
import dev.scriptforge.config.ForgeProperties;
import dev.scriptforge.domain.Json;
import dev.scriptforge.store.ForgeStore;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
  private final ForgeStore db;
  private final ForgeProperties config;

  public TaskService(ForgeStore db, ForgeProperties config) {
    this.db = db;
    this.config = config;
  }

  public Map<String, Object> create(CreateTask request) {
    require(!config.readOnly(), "READ_ONLY", "This server is read-only");
    require(
        config.modelConfigured(),
        "MODEL_NOT_CONFIGURED",
        "Set MODEL_API_KEY and MODEL_NAME before creating a model task");
    require(
        request.facts().stream().map(CreateTask.Fact::id).distinct().count()
            == request.facts().size(),
        "INVALID_ARGUMENT",
        "Fact IDs must be unique");
    String hash = db.json().hash(request);
    var old = db.one("SELECT * FROM tasks WHERE request_id=?", request.requestId());
    if (old != null) {
      require(
          hash.equals(str(old, "request_hash")),
          "CONFLICT",
          "Request ID already used with different input");
      return db.detail(str(old, "id"));
    }
    try {
      String taskId =
          db.tx(
              () -> {
                require(
                    db.jdbc()
                            .queryForObject(
                                "SELECT COUNT(*) FROM tasks WHERE status IN ('QUEUED','RUNNING','REVIEWING')",
                                Long.class)
                        < 10,
                    "CAPACITY",
                    "At most 10 active tasks");
                require(
                    db.jdbc().queryForObject("SELECT COUNT(*) FROM tasks", Long.class) < 1000,
                    "CAPACITY",
                    "Local demo task limit reached");
                String id = id();
                var input = db.json().map(db.json().write(request));
                input.remove("requestId");
                if (request.templateId() != null && !request.templateId().isBlank()) {
                  var template = db.one("SELECT * FROM templates WHERE id=?", request.templateId());
                  require(template != null, "NOT_FOUND", "Prompt template not found");
                  input.put(
                      "template",
                      Json.object(
                          "id",
                          str(template, "id"),
                          "name",
                          str(template, "name"),
                          "version",
                          integer(template, "version"),
                          "instructions",
                          str(template, "instructions")));
                }
                db.jdbc()
                    .update(
                        "INSERT INTO tasks(id,request_id,request_hash,title,status,mode,input_json,max_model_calls,max_tokens,created_at,updated_at) VALUES(?,?,?,?,'QUEUED',?,?,?,?,?,?)",
                        id,
                        request.requestId(),
                        hash,
                        request.title(),
                        config.mode(),
                        db.json().write(input),
                        request.maxModelCalls() == null ? 40 : request.maxModelCalls(),
                        request.maxTokens() == null ? 400000L : request.maxTokens(),
                        now(),
                        now());
                db.newExecution(id, "WRITER", 0, 0, null);
                return id;
              });
      return db.detail(taskId);
    } catch (DuplicateKeyException ex) {
      var existing = db.one("SELECT * FROM tasks WHERE request_id=?", request.requestId());
      if (existing == null) throw ex;
      require(
          hash.equals(str(existing, "request_hash")),
          "CONFLICT",
          "Request ID already used with different input");
      return db.detail(str(existing, "id"));
    }
  }

  public Map<String, Object> command(String taskId, String requestId, String command) {
    require(!config.readOnly(), "READ_ONLY", "This server is read-only");
    db.tx(
        () -> {
          var t = db.task(taskId, true);
          var previous =
              db.one(
                  "SELECT command_type FROM commands WHERE task_id=? AND request_id=?",
                  taskId,
                  requestId);
          if (previous != null) {
            require(command.equals(str(previous, "command_type")), "CONFLICT", "Command ID reused");
            return null;
          }
          String status = str(t, "status");
          if ("CANCEL".equals(command)) {
            require(
                !Set.of("READY", "DEMO_READY", "CANCELLED").contains(status),
                "CONFLICT",
                "Task already terminal");
            db.jdbc()
                .update(
                    "UPDATE tasks SET status='CANCELLED',error=NULL,updated_at=? WHERE id=?",
                    now(),
                    taskId);
            db.jdbc()
                .update(
                    "UPDATE executions SET status='CANCELLED',lease_epoch=lease_epoch+1,lease_owner=NULL,lease_until=NULL WHERE task_id=? AND status<>'COMPLETE'",
                    taskId);
          } else {
            require(
                "PAUSED".equals(status),
                "CONFLICT",
                "Only paused executions can resume. A review decision requires a new task with revised input.");
            require(
                integer(t, "model_calls") < integer(t, "max_model_calls")
                    && number(t, "used_tokens") + number(t, "reserved_tokens")
                        < number(t, "max_tokens"),
                "BUDGET_EXHAUSTED",
                "No budget left; export partial work or create a new task");
            db.jdbc()
                .update(
                    "UPDATE tasks SET status='RUNNING',error=NULL,updated_at=? WHERE id=?",
                    now(),
                    taskId);
            db.jdbc()
                .update(
                    "UPDATE executions SET status='RUNNABLE',lease_epoch=lease_epoch+1,lease_owner=NULL,lease_until=NULL WHERE task_id=? AND status='PAUSED'",
                    taskId);
          }
          db.jdbc()
              .update(
                  "INSERT INTO commands(task_id,request_id,command_type,created_at) VALUES(?,?,?,?)",
                  taskId,
                  requestId,
                  command,
                  now());
          return null;
        });
    return db.detail(taskId);
  }
}
