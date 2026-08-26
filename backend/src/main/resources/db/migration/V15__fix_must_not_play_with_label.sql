-- v0.6.0 audit-fix B8: the V2-seeded "mustNotPlayWith" field's Swedish label read as a double
-- negative ("Måste inte spela med" == "Must not play with" but easy to misread as its opposite).
-- Trivial, targeted UPDATE by id + old label so a club that has already renamed this field
-- themselves is left untouched.
UPDATE field_definition
SET label = 'Får inte spela med'
WHERE id = '019f1fda-0000-7000-8000-000000000001'
  AND label = 'Måste inte spela med';
