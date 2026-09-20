package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.GitProvider;

import java.time.Instant;

public record GitProviderLinkView(GitProvider provider, String username, Instant linkedAt) { }
