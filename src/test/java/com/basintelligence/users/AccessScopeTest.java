package com.basintelligence.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

class AccessScopeTest {

    static final UUID PLANT = UUID.randomUUID();

    @Test
    void scopeFollowsTheMostPrivilegedRole() {
        assertThat(AccessScope.forPerson("a", List.of("ADMIN", "ENGINEER", "VIEWER"), PLANT).kind()).isEqualTo(AccessScope.Kind.ALL);
        assertThat(AccessScope.forPerson("e", List.of("ENGINEER", "VIEWER"), PLANT))
                .isEqualTo(new AccessScope(AccessScope.Kind.FACTORY, "e", PLANT));
        assertThat(AccessScope.forPerson("t", List.of("TECHNICIAN", "VIEWER"), PLANT).kind()).isEqualTo(AccessScope.Kind.ASSIGNED);
        assertThat(AccessScope.forPerson("v", List.of("VIEWER"), PLANT).kind()).isEqualTo(AccessScope.Kind.FACTORY);
    }

    @Test
    void failsClosedWithoutFactoryOrRoles() {
        assertThat(AccessScope.forPerson("e", List.of("ENGINEER", "VIEWER"), null)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.forPerson("x", List.of(), PLANT)).isEqualTo(AccessScope.NONE);
    }

    @Test
    void machineClientsAndAnonymousRequests() {
        var service = new AccessScopeService(null);
        assertThat(service.resolve(null)).isEqualTo(AccessScope.ALL); // background job
        assertThat(service.resolve(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isEqualTo(AccessScope.NONE);
        assertThat(service.resolve(UsernamePasswordAuthenticationToken.authenticated("kiro-agent", null,
                AuthorityUtils.createAuthorityList("ROLE_AGENT")))).isEqualTo(AccessScope.ALL);
        assertThat(service.resolve(UsernamePasswordAuthenticationToken.authenticated("gw", null,
                AuthorityUtils.createAuthorityList("ROLE_EDGE")))).isEqualTo(AccessScope.NONE);
    }
}
