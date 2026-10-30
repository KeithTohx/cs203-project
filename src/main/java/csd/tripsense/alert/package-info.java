/**
 * CS203-5: session-authenticated in-app and SMTP disruption alerts.
 *
 * <p>Flow: a new impact assessment commits, an eligible alert is persisted once, then
 * connected recipients receive an SSE event and a background worker attempts email.
 * A recovery scan recreates missing alerts without another AI call. Assessments from
 * before this feature are excluded. Trip/activity impacts are separate notifications;
 * grouping different news articles about one event belongs to CS203-26.
 *
 * <p>API contract for future frontend integration (frontend files are unchanged):
 * <ul>
 * <li>GET /api/alerts?page=0&amp;size=20&amp;unreadOnly=false: saved history and unreadCount.</li>
 * <li>GET /api/alerts/stream: ready/alert SSE events, session cookie required. Fetch history
 * on every ready event to recover any missed messages. Streams expire after 30 minutes
 * and clients can reconnect; heartbeats are sent every 15 seconds.</li>
 * <li>PATCH /api/alerts/{id}/read: idempotent, owner-only, requires the normal CSRF token.</li>
 * </ul>
 *
 * <p>Email remains disabled until configured externally. No resource, frontend or
 * deployment file needs to contain credentials. Standard environment settings:
 * <pre>
 * ALERTS_EMAIL_ENABLED=true
 * ALERTS_EMAIL_FROM=alerts@your-domain.example
 * SPRING_MAIL_HOST=your-smtp-host
 * SPRING_MAIL_PORT=587
 * SPRING_MAIL_USERNAME=your-smtp-username
 * SPRING_MAIL_PASSWORD=your-smtp-password
 * </pre>
 * STARTTLS is required by default for SMTP. For implicit TLS, use SPRING_MAIL_PROTOCOL=smtps
 * and the provider's port. Use the provider's app password where required. Secrets belong
 * in the deployment environment, never in source files. Enabling email without a host or
 * sender fails startup with a configuration error. Disabling email preserves pending rows.
 *
 * <p>SMTP retries use a two-minute claim lease, at most three attempts, and a stable
 * Message-ID. SENT means accepted by the configured SMTP transport, not confirmed inbox
 * delivery. SMTP cannot guarantee exactly-once delivery if acceptance succeeds but the
 * acknowledgement is lost. The saved in-app alert remains available if email fails.
 *
 * <p>Real-time delivery begins after impact assessment, not at the time a news event occurs.
 * Feed polling and model runtime still determine detection latency. SSE subscribers are
 * held in memory on one server; a shared broker would be needed for multi-instance push.
 */
package csd.tripsense.alert;
