package ar.edu.utn.frc.tup.p4.usersservice.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeCatalogTest {

    @Test
    void marketCatalogReadResolvesToMarketService() {
        assertThat(ScopeCatalog.isIssuable("market.catalog.read")).isTrue();
        assertThat(ScopeCatalog.audienceFor("market.catalog.read")).isEqualTo("market-service");
    }

    @Test
    void theCatalogStaysClosed() {
        assertThat(ScopeCatalog.isIssuable("market.catalog.write")).isFalse();
        assertThat(ScopeCatalog.audienceFor("market.catalog.write")).isNull();
    }
}
