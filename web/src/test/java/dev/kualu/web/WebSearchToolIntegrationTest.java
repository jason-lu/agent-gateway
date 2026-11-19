package dev.kualu.web;

import dev.kualu.llm.tools.WebSearchTool;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The web-search tool is registered only when enabled and an API key is present:
 * deployments without credentials see no search tool at all, rather than a tool
 * that errors on every call.
 */
class WebSearchToolIntegrationTest {

    @Nested
    @SpringBootTest(properties = {
            "agent.store=memory",
            "agent.memory.semantic.enabled=false",
            "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
            "llm.base-url=http://localhost",
            "llm.api-key=test",
            "llm.model=test-model",
            "agent.tools.exec.enabled=false",
            "agent.moderation.enabled=false",
            "agent.tools.websearch.api-key=tvly-test-key"
    })
    class WithApiKey {

        @Autowired
        private ObjectProvider<WebSearchTool> tools;

        @Test
        void registersTheWebSearchTool() {
            WebSearchTool tool = tools.getIfAvailable();
            assertThat(tool).isNotNull();
            assertThat(tool.name()).isEqualTo("web_search");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "agent.store=memory",
            "agent.memory.semantic.enabled=false",
            "agent.security.jwt-secret=test-secret-test-secret-test-secret-1234",
            "llm.base-url=http://localhost",
            "llm.api-key=test",
            "llm.model=test-model",
            "agent.tools.exec.enabled=false",
            "agent.moderation.enabled=false",
            // Blank on purpose: DotenvEnvironmentPostProcessor loads the project
            // .env (lowest precedence), so a dev machine's TAVILY_API_KEY would
            // otherwise leak in and defeat the no-key case under test.
            "agent.tools.websearch.api-key="
    })
    class WithoutApiKey {

        @Autowired
        private ObjectProvider<WebSearchTool> tools;

        @Test
        void registersNothing() {
            assertThat(tools.getIfAvailable()).isNull();
        }
    }
}
