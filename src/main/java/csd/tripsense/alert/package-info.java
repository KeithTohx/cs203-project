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
 * <li>GET /api/alerts?beforeId=&amp;size=20&amp;unreadOnly=false: saved history, newest first, and
 * unreadCount. Send the response's nextBeforeId back as beforeId to load older alerts.</li>
 * <li>GET /api/alerts/stream: ready/alert SSE events, session cookie required. Fetch history
 * on every ready event to recover any missed messages. Streams expire after 30 minutes
 * and clients can reconnect; heartbeats are sent every 15 seconds.</li>
 * <li>PATCH /api/alerts/{id} with {"read": true}: marks one alert as read. Idempotent, owner-only,
 * requires the normal CSRF token.</li>
 * <li>PATCH /api/alerts/{id}/group with {"read": true}: marks visible members of the same
 * news/trip group read through the anchor id, including members on older pages.</li>
 * <li>PATCH /api/alerts?upToId= with {"read": true}: marks every alert of the user as read. Pass the
 * newest alert id shown so alerts that arrive meanwhile stay unread. Returns the unreadCount left
 * afterwards. Idempotent, requires the normal CSRF token.</li>
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
 * acknowledgement is lost. After SMTP acceptance, a database confirmation failure pauses only that group
 * and retries its confirmation independently while other groups continue sending. It does not
 * mark the accepted email failed or resend it. That pending confirmation is held in memory, so a process crash before it is persisted can
 * still cause a duplicate after lease recovery. The saved in-app alert remains available if email fails.
 *
 * <p>Email groups wait at most 120 seconds from the first saved alert for missing assessments,
 * then send the available impacts with a partial-assessment notice. Incomplete groups are
 * deferred for 30 seconds between checks, and due work is ordered by its next-attempt time.
 * Override with alerts.email.max-group-wait-seconds and alerts.email.group-recheck-seconds.
 * A terminally failed group marks late members FAILED too; it is not retried automatically.
 * Later assessments from a partial summary receive a follow-up email containing only unsent
 * impacts, with a fresh delivery identity. Additions after a complete summary are SUPPRESSED.
 * Suppression is a real email status; a transactional SQLite schema upgrade preserves existing
 * rows and converts the former compatibility marker on startup.
 * A first email also waits 30 seconds (alerts.email.initial-group-wait-seconds) so activities
 * created just after the itinerary can join the initial summary. This does not wait indefinitely
 * for activities a traveller might add later.
 *
 * <p>After itinerary/activity creation commits, a separate transaction queues recent
 * country-matched news. A queue failure is logged without rolling back the saved trip/activity;
 * a crash or failure before queuing can leave that item without a task. Once queued, the new-item
 * worker resumes saved progress after failures, restarts, or its per-pass AI limit; its article
 * snapshot does not expire with the news lookback.
 * Disable impact.eval.new-itinerary.enabled in tests that create unrelated fixtures.
 *
 * <p>Real-time delivery begins after impact assessment, not at the time a news event occurs.
 * Feed polling and model runtime still determine detection latency. SSE subscribers are
 * held in memory on one server; a shared broker would be needed for multi-instance push.
 */
package csd.tripsense.alert;
