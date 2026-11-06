-- DMS-171, ADR 0081: a job error whose code says that another attempt cannot cure it ends the job at once. A document
-- over the rendering worker's limits fails the same way on every attempt, and until now the job queue sent it again up
-- to ten times, an hour apart at the end, before anyone was told. dms.job_dead_event (V40) writes the event as for any
-- dead job; dms.job_retry (V36) stays open for those who may retry, for example after the limit was raised.

-- Keep in step with RenderingError.NoRetry; a test compares the two.
create function dms.job_failure_is_final(error text) returns boolean
    language sql immutable
    set search_path = dms, pg_temp as
$$
select coalesce(dms.job_failure_code(error) in ('too_long', 'time_limit'), false)
$$;

revoke all on function dms.job_failure_is_final(text) from public;

create or replace function dms.job_fail(job_id bigint, worker text, error text, retry_after interval) returns text
    language sql security definer
    set search_path = dms, pg_temp as
$$
update dms.job
set status       = case when attempts >= max_attempts or dms.job_failure_is_final(error) then 'dead' else 'queued' end,
    run_at       = now() + retry_after,
    finished_at  = case when attempts >= max_attempts or dms.job_failure_is_final(error) then now() end,
    locked_until = null,
    last_error   = error
where id = job_id and status = 'running' and locked_by = worker
returning status
$$;
