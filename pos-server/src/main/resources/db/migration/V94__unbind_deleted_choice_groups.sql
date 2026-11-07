-- Unbind choice groups that no longer exist from their products.
--
-- Deleting a choice group is a soft delete (choice_group.is_active = false), and until now it left
-- every product_choice_group row behind. Each product kept listing the deleted group: the product
-- editor showed an empty entry and the till offered a group without a name or options.
-- Hard-deleted groups need no cleanup, the foreign key already cascades.
--
-- From now on deleting a group removes its assignments in the same transaction; this clears the
-- ones left over from before. Only assignment rows go; the group rows themselves are kept.
--
-- Not reversible, the removed assignments are recorded nowhere else. They pointed at deleted
-- groups, which a product cannot use anyway.

DELETE FROM product_choice_group
WHERE group_id IN (SELECT id FROM choice_group WHERE is_active = FALSE);
