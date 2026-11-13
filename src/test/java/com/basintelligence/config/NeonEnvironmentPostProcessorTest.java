package com.basintelligence.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class NeonEnvironmentPostProcessorTest {

    private final NeonEnvironmentPostProcessor processor = new NeonEnvironmentPostProcessor();

    @Test
    void convertsNeonUriToJdbcWithTlsAndSeparateCredentials() {
        var props = NeonEnvironmentPostProcessor.toJdbc(
                "postgresql://app_user:p%40ss@ep-x-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

        assertThat(props.get("DATABASE_URL")).isEqualTo(
                "jdbc:postgresql://ep-x-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channelBinding=require"
                        + "&ApplicationName=bas-intelligence");
        assertThat(props.get("DATABASE_USERNAME")).isEqualTo("app_user");
        assertThat(props.get("DATABASE_PASSWORD")).isEqualTo("p@ss");
    }

    @Test
    void rejectsNonPostgresUris() {
        assertThatThrownBy(() -> NeonEnvironmentPostProcessor.toJdbc("mysql://u:p@h/db"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsDotEnvLocalPrefersUnpooledAndLoadsAppSecrets(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env.local");
        Files.writeString(env, """
                # written by neon init
                DATABASE_URL="postgresql://u:pw@pooled.example.neon.tech/neondb?sslmode=require"
                DATABASE_URL_UNPOOLED='postgresql://u:pw@direct.example.neon.tech/neondb?sslmode=require'
                export BAS_EDGE_API_KEY=edge-secret
                """);
        MockEnvironment environment = new MockEnvironment().withProperty("bas.neon.env-file", env.toString());
        environment.setActiveProfiles("neon");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("DATABASE_URL")).startsWith("jdbc:postgresql://direct.example.neon.tech/neondb");
        assertThat(environment.getProperty("DATABASE_PASSWORD")).isEqualTo("pw");
        assertThat(environment.getProperty("BAS_EDGE_API_KEY")).isEqualTo("edge-secret");
    }

    @Test
    void explicitJdbcUrlAndOtherProfilesAreLeftAlone(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env.local");
        Files.writeString(env, "DATABASE_URL_UNPOOLED=postgresql://u:pw@h/db\n");

        MockEnvironment explicit = new MockEnvironment().withProperty("bas.neon.env-file", env.toString())
                .withProperty("DATABASE_URL", "jdbc:postgresql://mine/db");
        explicit.setActiveProfiles("neon");
        processor.postProcessEnvironment(explicit, null);
        assertThat(explicit.getProperty("DATABASE_URL")).isEqualTo("jdbc:postgresql://mine/db");

        MockEnvironment demo = new MockEnvironment().withProperty("bas.neon.env-file", env.toString());
        demo.setActiveProfiles("demo");
        processor.postProcessEnvironment(demo, null);
        assertThat(demo.getProperty("DATABASE_URL")).isNull();
    }
}
