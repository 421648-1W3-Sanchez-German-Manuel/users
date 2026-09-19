package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;

/** What a successful `callback` returns: the linked account. */
public record GitLinkResponse(GitProvider provider, String username) { }
