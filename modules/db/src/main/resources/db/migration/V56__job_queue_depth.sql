-- How deep a job queue is, for the metric of the rendering queue (DMS-137, ADR 0079; business concept chapter 13).
--
-- Jobs are visible per tenant only (row-level security), while the queue of an installation runs across tenants, so
-- the numbers come from a security definer function. It gives counts and an age, nothing of a job, and reads the
-- index job_due_kind (V38).
--
-- States: due (waiting for a worker), delayed (waiting for a retry or a later time), running (claimed by a worker).
-- oldest_seconds: how long the oldest job of the state has been due, 0 without jobs or in the future.
create function dms.job_queue_depth(job_kind text)
    returns table (state text, jobs bigint, oldest_seconds double precision)
    language sql stable security definer
    set search_path = dms, pg_temp as
$$
select s.state, count(j.id), greatest(0, coalesce(extract(epoch from now() - min(j.run_at)), 0))::double precision
from (values ('due'), ('delayed'), ('running')) s (state)
left join (select id, run_at,
                  case when status = 'running' then 'running' when run_at <= now() then 'due' else 'delayed' end as state
           from dms.job
           where kind = job_kind and status in ('queued', 'running')) j on j.state = s.state
group by s.state
order by s.state
$$;

revoke all on function dms.job_queue_depth(text) from public;
grant execute on function dms.job_queue_depth(text) to dms_app;
