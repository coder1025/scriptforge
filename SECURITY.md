# Security

## Supported deployment boundary

The public GitHub Pages site is a read-only viewer of a synthetic fixture. It contains no API key and cannot create real model jobs. The Java application is a single-workspace service, not a multi-tenant SaaS product.

Local development binds to loopback by default. The Docker Compose example also publishes its port only on the host loopback interface. If you expose the Java server remotely, use HTTPS, an `ADMIN_TOKEN`, network access controls, request limits at the reverse proxy, and a model account with an appropriate spending cap. The shared administrator token is not user/tenant isolation. A holder can access the whole workspace.

Never embed a model credential or administrator token in a static site, a query string, a public trace, or a Git commit. Task prompts and responses can contain sensitive business information; review exports before sharing them.

## Model and tool boundaries

- Task text, facts, search results and model output are untrusted data.
- The host enforces role-specific tools, validated arguments, immutable revision references and final submission rules.
- Reviewer access is read-only for drafts; it cannot edit text or mark a task ready directly.
- The runtime has no shell, arbitrary URL-fetch, or filesystem-execution tool.
- Budget and revision constraints limit damage; they do not make a model's semantic judgement infallible.

## Reporting

Use the repository's **Security → Report a vulnerability** feature if the maintainer has enabled private vulnerability reporting. If it is not available, open a minimal issue requesting a private contact without posting the exploit, credentials, personal data or database contents. Avoid sending live credentials as a reproduction.

