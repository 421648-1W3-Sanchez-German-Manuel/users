package ar.edu.utn.frc.tup.p4.usersservice.auth.dto;

public record TokenResponse(String accessToken, String refreshToken, long expiresIn) { }