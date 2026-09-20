package ar.edu.utn.frc.tup.p4.usersservice.users.enums;

/**
 * Four values. MS is deliberately NOT here: it is a role exclusive to
 * service tokens and cannot be assigned to a person.
 *
 * GESTOR sits between ADMIN and PROFESSOR: it manages PROFESSOR and GESTOR
 * accounts (whitelist + role changes + deactivation) but can never touch an
 * ADMIN account or ADMIN-only settings.
 */
public enum Role { ADMIN, GESTOR, PROFESSOR, STUDENT }
