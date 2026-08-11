-- Remember the item's status at the moment it was marked handled, so the reopen-guard can clear
-- the flag only when the status actually changes (not merely because the item is still open).
ALTER TABLE work_item_flag ADD COLUMN handled_status VARCHAR(190);
