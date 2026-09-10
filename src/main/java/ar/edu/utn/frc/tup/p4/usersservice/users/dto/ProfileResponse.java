package ar.edu.utn.frc.tup.p4.usersservice.users.dto;

/** DEC-36 - what any classmate sees. NO e-mail, no legajo, no status. */
public record ProfileResponse(String id, String firstNames, String lastNames,
                             String githubUsername, String avatarRef) { }
