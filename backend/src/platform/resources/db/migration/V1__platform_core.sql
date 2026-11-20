-- Atlas AI Platform core schema (PostgreSQL 15+).
-- Tenant invariant: every tenant-owned row carries org_id, and child rows reference parents through
-- composite (org_id, id) foreign keys so a row of org A can never point at a parent of org B.

create table users (
    id uuid primary key,
    email varchar(254) not null,
    display_name varchar(120) not null,
    password_hash varchar(100) not null,
    status varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
    system_role varchar(16) not null default 'NONE' check (system_role in ('NONE','SYSTEM_ADMIN')),
    created_at timestamptz not null default now(),
    password_changed_at timestamptz not null default now()
);
create unique index users_email_unique on users (lower(email));

create table organizations (
    id uuid primary key,
    name varchar(120) not null,
    slug varchar(64) not null unique check (slug ~ '^[a-z0-9][a-z0-9-]{1,63}$'),
    status varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','SUSPENDED')),
    plan varchar(32) not null default 'SELF_HOSTED',
    created_at timestamptz not null default now()
);

create table memberships (
    org_id uuid not null references organizations(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    role varchar(16) not null check (role in ('OWNER','ADMIN','EDITOR','VIEWER')),
    status varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
    created_at timestamptz not null default now(),
    primary key (org_id, user_id)
);
create index memberships_user on memberships(user_id);

create table invitations (
    id uuid primary key,
    org_id uuid not null references organizations(id) on delete cascade,
    email varchar(254) not null,
    role varchar(16) not null check (role in ('ADMIN','EDITOR','VIEWER')),
    token_hash char(64) not null unique,
    expires_at timestamptz not null,
    accepted_at timestamptz,
    revoked_at timestamptz,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now()
);

create table workspaces (
    id uuid primary key,
    org_id uuid not null references organizations(id) on delete cascade,
    name varchar(120) not null,
    description varchar(500) not null default '',
    created_at timestamptz not null default now(),
    unique (org_id, id),
    unique (org_id, name)
);

create table workspace_members (
    org_id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null references users(id) on delete cascade,
    role varchar(16) not null check (role in ('MANAGER','EDITOR','VIEWER')),
    primary key (workspace_id, user_id),
    foreign key (org_id, workspace_id) references workspaces(org_id, id) on delete cascade,
    foreign key (org_id, user_id) references memberships(org_id, user_id) on delete cascade
);

create table knowledge_bases (
    id uuid primary key,
    org_id uuid not null,
    workspace_id uuid not null,
    name varchar(120) not null,
    description varchar(1000) not null default '',
    -- ORG: every active member may read; RESTRICTED: only org admins, workspace managers and kb_members.
    visibility varchar(16) not null default 'ORG' check (visibility in ('ORG','RESTRICTED')),
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    deleted_at timestamptz,
    unique (org_id, id),
    foreign key (org_id, workspace_id) references workspaces(org_id, id) on delete cascade
);

create table kb_members (
    org_id uuid not null,
    kb_id uuid not null,
    user_id uuid not null,
    role varchar(16) not null check (role in ('EDITOR','VIEWER')),
    primary key (kb_id, user_id),
    foreign key (org_id, kb_id) references knowledge_bases(org_id, id) on delete cascade,
    foreign key (org_id, user_id) references memberships(org_id, user_id) on delete cascade
);

create table documents (
    id uuid primary key,
    org_id uuid not null,
    kb_id uuid not null,
    title varchar(200) not null,
    owner_id uuid not null references users(id),
    -- Lifecycle of the latest upload; READY means current_version_id points at a fully indexed version.
    status varchar(16) not null check (status in ('PROCESSING','READY','FAILED','NEEDS_OCR','CANCELLED','DELETING')),
    current_version_id uuid,
    latest_version_no int not null default 0,
    expires_at timestamptz,
    tags text[] not null default '{}',
    legacy_id varchar(64),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    unique (org_id, id),
    foreign key (org_id, kb_id) references knowledge_bases(org_id, id) on delete cascade
);
create index documents_kb on documents(org_id, kb_id) where deleted_at is null;
create unique index documents_legacy on documents(org_id, legacy_id) where legacy_id is not null;

create table document_versions (
    id uuid primary key,
    org_id uuid not null,
    document_id uuid not null,
    version_no int not null check (version_no > 0),
    sha256 char(64) not null,
    object_key varchar(300) not null,
    filename varchar(255) not null,
    mime_type varchar(120) not null,
    size_bytes bigint not null check (size_bytes >= 0),
    parser_version varchar(40),
    pipeline_state varchar(16) not null check (pipeline_state in ('RECEIVED','VALIDATING','STORED','PARSING','CHUNKING','EMBEDDING','INDEXING','READY','FAILED','NEEDS_OCR','CANCELLED','SUPERSEDED')),
    error_code varchar(64),
    error_message varchar(500),
    page_count int,
    chunk_count int not null default 0,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    ready_at timestamptz,
    unique (document_id, version_no),
    unique (org_id, id),
    foreign key (org_id, document_id) references documents(org_id, id) on delete cascade
);
alter table documents add constraint documents_current_version
    foreign key (org_id, current_version_id) references document_versions(org_id, id) deferrable initially deferred;

create table document_chunks (
    org_id uuid not null,
    document_version_id uuid not null,
    chunk_id varchar(120) not null,
    ordinal int not null,
    heading varchar(500) not null default '',
    content text not null,
    content_hash char(64) not null,
    -- page / lineStart / lineEnd / sheet / row: whatever the parser can prove about the source location.
    locator jsonb not null default '{}'::jsonb,
    primary key (document_version_id, chunk_id),
    foreign key (org_id, document_version_id) references document_versions(org_id, id) on delete cascade
);
create index document_chunks_org on document_chunks(org_id, document_version_id);

create table jobs (
    id uuid primary key,
    org_id uuid not null references organizations(id) on delete cascade,
    type varchar(32) not null,
    state varchar(16) not null check (state in ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    payload jsonb not null,
    result jsonb,
    attempts int not null default 0,
    max_attempts int not null default 3,
    priority int not null default 100,
    run_after timestamptz not null default now(),
    lease_owner varchar(80),
    lease_until timestamptz,
    idempotency_key varchar(120),
    cancel_requested boolean not null default false,
    last_error varchar(500),
    created_by uuid references users(id),
    trace_id varchar(64) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (org_id, id)
);
create unique index jobs_idempotency on jobs(org_id, type, idempotency_key) where idempotency_key is not null;
create index jobs_ready on jobs(state, run_after, priority) where state in ('QUEUED','RUNNING');

create table conversations (
    id uuid primary key,
    org_id uuid not null references organizations(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    title varchar(200) not null,
    kb_ids uuid[] not null default '{}',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    unique (org_id, id)
);
create index conversations_user on conversations(org_id, user_id, updated_at desc) where deleted_at is null;

create table messages (
    id uuid primary key,
    org_id uuid not null,
    conversation_id uuid not null,
    role varchar(16) not null check (role in ('USER','ASSISTANT')),
    content text not null,
    status varchar(16) not null check (status in ('COMPLETE','PENDING','REFUSED','FAILED')),
    job_id uuid,
    model varchar(100),
    meta jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique (org_id, id),
    foreign key (org_id, conversation_id) references conversations(org_id, id) on delete cascade
);
create index messages_conversation on messages(conversation_id, created_at);

create table message_citations (
    org_id uuid not null,
    message_id uuid not null,
    ordinal int not null,
    document_id uuid not null,
    document_version_id uuid not null,
    version_no int not null,
    chunk_id varchar(120) not null,
    title varchar(200) not null,
    quote text not null,
    locator jsonb not null default '{}'::jsonb,
    primary key (message_id, ordinal),
    foreign key (org_id, message_id) references messages(org_id, id) on delete cascade
);

create table agents (
    id uuid primary key,
    org_id uuid not null references organizations(id) on delete cascade,
    name varchar(120) not null,
    template varchar(32) not null check (template in ('KNOWLEDGE_QA','DOCUMENT_COMPARE','READONLY_TOOL')),
    description varchar(1000) not null default '',
    enabled boolean not null default true,
    current_version int not null default 1,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (org_id, id)
);

create table agent_versions (
    org_id uuid not null,
    agent_id uuid not null,
    version_no int not null,
    definition jsonb not null,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    primary key (agent_id, version_no),
    foreign key (org_id, agent_id) references agents(org_id, id) on delete cascade
);

create table workflow_runs (
    id uuid primary key,
    org_id uuid not null,
    agent_id uuid not null,
    agent_version int not null,
    actor_id uuid not null references users(id),
    status varchar(24) not null check (status in ('QUEUED','RUNNING','WAITING_APPROVAL','SUCCEEDED','FAILED','REJECTED','CANCELLED')),
    input jsonb not null,
    output jsonb,
    error varchar(500),
    trace_id varchar(64) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (org_id, id),
    foreign key (org_id, agent_id) references agents(org_id, id) on delete cascade
);
create index workflow_runs_agent on workflow_runs(org_id, agent_id, created_at desc);

create table workflow_steps (
    id uuid primary key,
    org_id uuid not null,
    run_id uuid not null,
    ordinal int not null,
    type varchar(24) not null,
    name varchar(120) not null,
    status varchar(24) not null check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED','WAITING_APPROVAL','REJECTED','SKIPPED','CANCELLED')),
    output jsonb,
    error varchar(500),
    started_at timestamptz,
    finished_at timestamptz,
    unique (run_id, ordinal),
    unique (org_id, id),
    foreign key (org_id, run_id) references workflow_runs(org_id, id) on delete cascade
);

create table connectors (
    id uuid primary key,
    org_id uuid not null references organizations(id) on delete cascade,
    type varchar(24) not null check (type in ('HTTP_GET','SQL_READONLY','MCP')),
    name varchar(120) not null,
    -- Non-secret configuration only. Credentials are referenced by name and resolved from the server environment.
    config jsonb not null,
    secret_ref varchar(120),
    enabled boolean not null default true,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    unique (org_id, id),
    unique (org_id, name)
);

create table tool_calls (
    id uuid primary key,
    org_id uuid not null,
    run_id uuid not null,
    step_id uuid,
    tool varchar(80) not null,
    side_effect boolean not null,
    request jsonb not null,
    response jsonb,
    status varchar(16) not null check (status in ('SUCCEEDED','FAILED','DENIED')),
    created_at timestamptz not null default now(),
    foreign key (org_id, run_id) references workflow_runs(org_id, id) on delete cascade
);

create table approvals (
    id uuid primary key,
    org_id uuid not null,
    run_id uuid not null,
    step_id uuid not null,
    status varchar(16) not null check (status in ('PENDING','APPROVED','REJECTED','CANCELLED')),
    summary varchar(500) not null,
    payload jsonb not null,
    requested_by uuid not null references users(id),
    decided_by uuid references users(id),
    decided_at timestamptz,
    comment varchar(500),
    created_at timestamptz not null default now(),
    unique (org_id, id),
    foreign key (org_id, run_id) references workflow_runs(org_id, id) on delete cascade
);
create index approvals_pending on approvals(org_id, status, created_at);

-- The only side-effecting tool target in v1: an internal, auditable output record written after approval.
create table agent_outputs (
    id uuid primary key,
    org_id uuid not null,
    run_id uuid not null,
    approval_id uuid not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    unique (run_id),
    foreign key (org_id, run_id) references workflow_runs(org_id, id) on delete cascade
);

create table quotas (
    org_id uuid primary key references organizations(id) on delete cascade,
    monthly_token_limit bigint not null default 2000000,
    monthly_request_limit int not null default 5000,
    requests_per_minute int not null default 30,
    updated_at timestamptz not null default now()
);

create table usage_events (
    id bigserial primary key,
    org_id uuid not null references organizations(id) on delete cascade,
    user_id uuid references users(id) on delete set null,
    agent_id uuid,
    kind varchar(32) not null,
    model varchar(100),
    input_tokens int not null default 0,
    output_tokens int not null default 0,
    latency_ms bigint not null default 0,
    status varchar(16) not null,
    trace_id varchar(64),
    created_at timestamptz not null default now()
);
create index usage_events_org_time on usage_events(org_id, created_at);

create table audit_events (
    id bigserial primary key,
    org_id uuid references organizations(id) on delete cascade,
    actor_id uuid references users(id) on delete set null,
    action varchar(64) not null,
    resource_type varchar(40),
    resource_id varchar(80),
    result varchar(16) not null check (result in ('SUCCESS','DENIED','FAILURE')),
    request_id varchar(64),
    trace_id varchar(64),
    ip varchar(64),
    detail jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index audit_events_org_time on audit_events(org_id, created_at desc);
