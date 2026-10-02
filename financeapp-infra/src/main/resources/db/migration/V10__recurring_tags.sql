-- V0.2.5 : etiquettes des operations recurrentes, reprises par chaque occurrence validee.
CREATE TABLE recurring_tags (
    rule_id INTEGER NOT NULL REFERENCES recurring_transactions(id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, tag_id)
);
CREATE INDEX ix_recurring_tags_tag ON recurring_tags (tag_id);
