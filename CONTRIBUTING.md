# Contributing

ScriptForge is a Java project for studying durable, inspectable writing agents. Contributions should make the runtime easier to reason about and reproduce.

## Local development

1. Install JDK 21 and Maven 3.9+.
2. Run `mvn clean verify`. Tests must not require a paid model account.
3. Run `mvn spring-boot:run` and open `http://localhost:8080`.
4. Use synthetic facts and the built-in demo when reproducing bugs.

Please keep credentials, local databases, proprietary text, production logs and private model responses out of commits and issues. The demo is deliberately fixed; changes must keep its label and must not imply that fixture output proves model quality.

## Pull requests

Explain the concrete failure or user need, the resulting behavior, and how it was verified. Add focused tests for state transitions, stale-version rejection, cancellation, recovery, budget accounting and role permissions when those behaviors change. For UI changes, attach a screenshot using synthetic data and check a narrow viewport.

Tool changes require updating all three layers: the model-visible JSON Schema, Java-side validation/authorization, and the relevant documentation. A schema-valid tool call is not automatically a valid business operation.

Database schema changes belong in a new Flyway migration. Do not edit a released migration. Preserve compatibility with the documented local database or clearly document the migration procedure.

The project uses Apache-2.0. By contributing, you agree to license your contribution under that license. Do not submit code you are not entitled to publish.

