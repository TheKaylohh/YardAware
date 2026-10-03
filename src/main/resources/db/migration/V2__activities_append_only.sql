-- The audit trail is append-only at the database level too: no UPDATE, DELETE or TRUNCATE, whoever asks.
-- To correct a mistake, add a new activity that explains it; never rewrite history.
--
-- Recommended on top of this: the application's database user should not own the tables, e.g.
--   grant select, insert on activities to yard_app;
--   grant select, insert, update on items, hulls, zones to yard_app;
-- and back up the database with retention that matches your records policy.

create or replace function reject_activity_change() returns trigger as $$
begin
    raise exception 'activities is append-only (% blocked)', tg_op;
end;
$$ language plpgsql;

create trigger activities_no_update_delete
    before update or delete on activities
    for each row execute function reject_activity_change();

create trigger activities_no_truncate
    before truncate on activities
    for each statement execute function reject_activity_change();
