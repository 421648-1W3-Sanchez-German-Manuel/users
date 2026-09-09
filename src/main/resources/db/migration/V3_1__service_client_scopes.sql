CREATE TABLE service_client_scopes (
    service_client_id CHAR(36)    NOT NULL,
    scope             VARCHAR(64) NOT NULL,
    PRIMARY KEY (service_client_id, scope),
    CONSTRAINT fk_scopes_client FOREIGN KEY (service_client_id) REFERENCES service_clients(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
