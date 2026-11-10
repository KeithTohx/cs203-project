/**
 * CS203-5: session-authenticated in-app and SMTP disruption alerts.
 *
 * <p>Flow: a new impact assessment commits, an eligible alert is persisted once, then
 * connected recipients receive an SSE event and a background worker attempts email.
 * A recovery scan recreates missing alerts without another AI call. Assessments from
 * before this feature are excluded. Trip/activity impacts are separate notifications;
 * grouping different news articles about one event belongs to CS203-26.
 *
 * <p>API contract for frontend integration:
 * <ul>
 * <li>GET /api/alerts?beforeId=&amp;size=20&amp;unreadOnly=false: saved history, newest first, and
 * unreadCount. Send the response's nextBeforeId back as beforeId to load older alerts.</li>
 * <li>GET /api/alerts/stream: ready/alert/alerts-changed SSE events, session cookie required. Fetch history
 * on every ready event and alerts-changed event to recover any missed messages. Streams expire after 30 minutes
 * and clients can reconnect; heartbeats are sent every 15 seconds. If the live-delivery queue
 * overflows, affected streams receive another ready event on their next heartbeat so clients
 * reload saved history even while the connection remains healthy. Each connection has at most
 * one active virtual-thread write, so a slow client cannot block another client's heartbeat.</li>
 * <li>PATCH /api/alerts/{id} with {"read": true}: marks one alert as read. Idempotent, owner-only,
 * requires the normal CSRF token.</li>
 * <li>PATCH /api/alerts/{id}/group with {"read": true}: marks visible members of the same
 * news/trip group read through the anchor id, including members on older pages. Use the newest
 * id displayed in that group; newer arrivals stay unread. False, null and missing read values
 * are rejected by request validation. Requires authentication and a CSRF token.</li>
 * <li>PATCH /api/alerts?upToId= with {"read": true}: marks every alert of the user as read. Pass the
 * newest alert id shown so alerts that arrive meanwhile stay unread. Returns the unreadCount left
 * afterwards. Idempotent, requires the normal CSRF token.</li>
 * </ul>
 *
 * <p>The browser batches live events into bounded 500 ms refresh windows and coalesces events
 * while a history request is running. Failed history loads retry after five seconds even on a
 * healthy stream; logout cancels these retries.
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
 * acknowledgement is lost. After SMTP acceptance, a database confirmation failure pauses only
 * that group and retries its confirmation independently while other groups continue sending.
 * While the process survives, confirmation retries do not resend an accepted message. Pending
 * confirmation is held in memory: a crash before it is persisted can cause a duplicate after
 * lease recovery, and a stable Message-ID does not guarantee inbox deduplication. Recording
 * success before SMTP acceptance would instead risk losing emails. The saved in-app alert
 * remains available if email fails.
 *
 * <p>Each email is an itinerary summary: ready news groups for the same itinerary are claimed
 * atomically and sent in one message, with one section per article and its affected activities.
 * Different itineraries are never mixed. Later news produces another summary; this is not a
 * one-email lifetime limit. Each constituent news group retains its delivery state so a summary
 * confirms all included alerts, retry recovery stays durable, and delivered articles are not resent.
 * An active SMTP lease or pending acceptance confirmation prevents another email for that itinerary.
 *
 * <p>The shipped application.properties sets a five-second initial grouping wait and a
 * 30-second maximum wait from the first saved alert for missing assessments. These waits
 * overlap; they are not added together. Running assessments and queued evaluation work for the
 * itinerary are considered across articles, so intermediate results can join the same summary.
 * After the maximum, available impacts are sent with
 * a partial-assessment notice. Incomplete groups are rechecked after five seconds, and the
 * retry scanner runs every five seconds. Queue backlog, SMTP requests and failure backoff can
 * add further delay. Configure alerts.email.initial-group-wait-seconds,
 * alerts.email.max-group-wait-seconds, alerts.email.group-recheck-seconds and
 * alerts.email.retry-interval-ms to override these timings.
 * Three failed delivery attempts exhaust a group; a single transient failure does not.
 * A terminally failed group marks late members FAILED too; it is not retried automatically.
 * Later assessments from a partial summary receive a follow-up email containing only unsent
 * impacts, with a fresh delivery identity. Additions after a complete summary are SUPPRESSED.
 * Suppression is a real email status; a transactional SQLite schema upgrade preserves existing
 * rows and converts the former compatibility marker on startup.
 * The initial grouping wait lets activities created just after the itinerary join its summary;
 * it does not wait indefinitely for activities a traveller might add later.
 *
 * <p>After itinerary/activity creation commits, a separate transaction queues recent
 * country-matched news. A queue failure is logged without rolling back the saved trip/activity;
 * the source transaction records a durable scheduling request, so a crash or failure before queuing is retried. Once queued, the new-item
 * worker resumes saved progress after failures, restarts, or its per-pass AI limit; its article
 * snapshot does not expire with the news lookback.
 * Deleting a source removes its pending tasks atomically through SQLite triggers, including
 * cascading deletions. New tasks have unique identities so an obsolete worker cannot advance
 * or delete a replacement task after SQLite reuses the source id.
 * Edits increment assessment revisions, clear obsolete verdicts and their advice/alerts, and queue fresh assessments.
 * Reads, rejections, edits and deletions invalidate live history across devices after commit.
 * In-flight model results also validate revisions and immutable trip/activity identities under the same
 * SQLite write lock as the verdict save, so a deleted source's result cannot alert a replacement.
 * Disable impact.eval.new-itinerary.enabled in tests that create unrelated fixtures.
 *
 * <p>Real-time delivery begins after impact assessment, not at the time a news event occurs.
 * Feed polling and model runtime still determine detection latency. SSE subscribers are
 * held in memory on one server; a shared broker would be needed for multi-instance push.
 */
package csd.tripsense.alert;
