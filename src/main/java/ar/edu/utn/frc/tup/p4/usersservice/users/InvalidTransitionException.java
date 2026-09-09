package ar.edu.utn.frc.tup.p4.usersservice.users;

import ar.edu.utn.frc.tup.p4.usersservice.users.enums.AccountStatus;

public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(AccountStatus from, AccountStatus to) {
        super("Invalid transition: " + from + " -> " + to);
    }
}
