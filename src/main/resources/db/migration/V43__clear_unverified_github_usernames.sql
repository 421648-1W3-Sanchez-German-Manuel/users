-- DEC-GL-20: the typed handle never proved possession of the account.
UPDATE users
   SET github_username = NULL, updated_at = NOW(6)
 WHERE github_username IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM user_git_provider_links l
                    WHERE l.user_id = users.id AND l.deleted_at IS NULL);
